use axum::body::Body;
use axum::extract::{Extension, Multipart, Path, Query, State};
use axum::http::{HeaderValue, Response, header};
use axum::routing::get;
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{FromRow, QueryBuilder, Sqlite};
use ulid::Ulid;

use crate::auth::Identity;
use crate::error::{ApiError, ApiResult};
use crate::{AppState, PageQuery};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/", get(list))
        .route("/upload", axum::routing::post(upload))
        .route("/{id}", get(get_one).delete(delete))
        .route("/{id}/content", get(download))
}

#[derive(Deserialize)]
struct ListQuery {
    page: Option<i64>,
    page_size: Option<i64>,
    uploaded_by: Option<String>,
}

#[derive(Serialize)]
struct FileMetadata {
    id: String,
    original_name: String,
    stored_name: String,
    mime_type: String,
    size_bytes: i64,
    storage_path: String,
    hash_sha256: String,
    uploaded_by: String,
    created_at: String,
}

#[derive(FromRow)]
struct FileRow {
    id: String,
    original_name: String,
    stored_name: String,
    mime_type: String,
    size_bytes: i64,
    storage_path: String,
    hash_sha256: String,
    uploaded_by: String,
    created_at: String,
}

impl From<FileRow> for FileMetadata {
    fn from(row: FileRow) -> Self {
        Self {
            id: row.id,
            original_name: row.original_name,
            stored_name: row.stored_name,
            mime_type: row.mime_type,
            size_bytes: row.size_bytes,
            storage_path: row.storage_path,
            hash_sha256: row.hash_sha256,
            uploaded_by: row.uploaded_by,
            created_at: row.created_at,
        }
    }
}

async fn list(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> ApiResult<Json<Vec<FileMetadata>>> {
    let (limit, offset) = PageQuery { page: query.page, page_size: query.page_size }.limit_offset()?;
    let mut builder: QueryBuilder<Sqlite> = QueryBuilder::new(
        "SELECT id, original_name, stored_name, mime_type, size_bytes, storage_path, hash_sha256, uploaded_by, created_at FROM files",
    );
    if let Some(uploaded_by) = query.uploaded_by.filter(|value| !value.is_empty()) {
        builder.push(" WHERE uploaded_by = ").push_bind(uploaded_by);
    }
    builder
        .push(" ORDER BY created_at DESC LIMIT ")
        .push_bind(limit)
        .push(" OFFSET ")
        .push_bind(offset);
    Ok(Json(
        builder
            .build_query_as::<FileRow>()
            .fetch_all(&state.database)
            .await?
            .into_iter()
            .map(FileMetadata::from)
            .collect(),
    ))
}

async fn upload(
    State(state): State<AppState>,
    Extension(identity): Extension<Identity>,
    mut multipart: Multipart,
) -> ApiResult<(axum::http::StatusCode, Json<FileMetadata>)> {
    let field = multipart
        .next_field()
        .await
        .map_err(|_| ApiError::BadRequest("invalid multipart form data".to_owned()))?
        .ok_or_else(|| ApiError::BadRequest("file is required".to_owned()))?;
    if field.name() != Some("file") {
        return Err(ApiError::BadRequest(
            "multipart field must be named file".to_owned(),
        ));
    }
    let original_name = field
        .file_name()
        .filter(|name| !name.is_empty())
        .ok_or_else(|| ApiError::BadRequest("file name is required".to_owned()))?
        .to_owned();
    let mime_type = field
        .content_type()
        .filter(|value| !value.is_empty())
        .unwrap_or("application/octet-stream")
        .to_owned();
    let contents = field
        .bytes()
        .await
        .map_err(|_| ApiError::BadRequest("could not read uploaded file".to_owned()))?;
    if multipart
        .next_field()
        .await
        .map_err(|_| ApiError::BadRequest("invalid multipart form data".to_owned()))?
        .is_some()
    {
        return Err(ApiError::BadRequest(
            "only one file is accepted per upload".to_owned(),
        ));
    }

    let id = Ulid::new().to_string();
    let stored_name = id.clone();
    let storage_path = stored_name.clone();
    let disk_path = state.files_dir.join(&stored_name);
    let hash_sha256 = format!("{:x}", Sha256::digest(&contents));
    tokio::fs::write(&disk_path, &contents)
        .await
        .map_err(|error| {
            tracing::error!(error = %error, path = %disk_path.display(), "write uploaded file");
            ApiError::Internal
        })?;

    let insert_result = sqlx::query(
        "INSERT INTO files (id, original_name, stored_name, mime_type, size_bytes, storage_path, hash_sha256, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(&original_name)
    .bind(&stored_name)
    .bind(&mime_type)
    .bind(contents.len() as i64)
    .bind(&storage_path)
    .bind(&hash_sha256)
    .bind(&identity.subject_id)
    .execute(&state.database)
    .await;
    if let Err(error) = insert_result {
        if let Err(remove_error) = tokio::fs::remove_file(&disk_path).await {
            tracing::error!(error = %remove_error, path = %disk_path.display(), "remove untracked uploaded file");
        }
        return Err(ApiError::from(error));
    }

    Ok((
        axum::http::StatusCode::CREATED,
        Json(fetch(&state, &id).await?.into()),
    ))
}

async fn get_one(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<Json<FileMetadata>> {
    Ok(Json(fetch(&state, &id).await?.into()))
}

async fn download(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<Response<Body>> {
    let file = fetch(&state, &id).await?;
    let path = state.files_dir.join(&file.stored_name);
    let contents = tokio::fs::read(&path).await.map_err(|error| {
        tracing::error!(error = %error, path = %path.display(), "read stored file");
        ApiError::NotFound("stored file not found".to_owned())
    })?;
    let mime_type = HeaderValue::from_str(&file.mime_type)
        .unwrap_or_else(|_| HeaderValue::from_static("application/octet-stream"));
    let disposition = content_disposition(&file.original_name);
    Response::builder()
        .header(header::CONTENT_TYPE, mime_type)
        .header(header::CONTENT_DISPOSITION, disposition)
        .body(Body::from(contents))
        .map_err(|_| ApiError::Internal)
}

async fn delete(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<axum::http::StatusCode> {
    let file = fetch(&state, &id).await?;
    let path = state.files_dir.join(&file.stored_name);
    match tokio::fs::remove_file(&path).await {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => {
            tracing::error!(error = %error, path = %path.display(), "delete stored file");
            return Err(ApiError::Internal);
        }
    }
    sqlx::query("DELETE FROM files WHERE id = ?")
        .bind(id)
        .execute(&state.database)
        .await?;
    Ok(axum::http::StatusCode::NO_CONTENT)
}

async fn fetch(state: &AppState, id: &str) -> ApiResult<FileRow> {
    sqlx::query_as::<_, FileRow>(
        "SELECT id, original_name, stored_name, mime_type, size_bytes, storage_path, hash_sha256, uploaded_by, created_at FROM files WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&state.database)
    .await?
    .ok_or_else(|| ApiError::NotFound("file not found".to_owned()))
}

fn safe_filename(name: &str) -> String {
    name.chars()
        .map(|character| {
            if character.is_ascii_graphic() && character != '"' && character != '\\' {
                character
            } else {
                '_'
            }
        })
        .collect()
}

fn content_disposition(name: &str) -> HeaderValue {
    let encoded_name = rfc5987_encode(name);
    HeaderValue::from_str(&format!(
        "attachment; filename=\"{}\"; filename*=UTF-8''{encoded_name}",
        safe_filename(name),
    ))
    .expect("RFC 5987 encoded file name must be a valid header value")
}

fn rfc5987_encode(name: &str) -> String {
    let mut encoded = String::new();
    for byte in name.bytes() {
        if byte.is_ascii_alphanumeric()
            || matches!(
                byte,
                b'!' | b'#' | b'$' | b'&' | b'+' | b'-' | b'.' | b'^' | b'_' | b'`' | b'|' | b'~'
            )
        {
            encoded.push(byte as char);
        } else {
            const HEX: &[u8; 16] = b"0123456789ABCDEF";
            encoded.push('%');
            encoded.push(HEX[(byte >> 4) as usize] as char);
            encoded.push(HEX[(byte & 0x0f) as usize] as char);
        }
    }
    encoded
}

#[cfg(test)]
mod tests {
    use super::{content_disposition, safe_filename};

    #[test]
    fn attachment_name_cannot_inject_headers() {
        assert_eq!(
            safe_filename("record\r\nX-Test: yes"),
            "record__X-Test:_yes"
        );
    }

    #[test]
    fn attachment_name_supports_non_ascii_original_names() {
        let disposition = content_disposition("护理计划.pdf");
        let value = disposition
            .to_str()
            .expect("valid content disposition header");
        assert!(value.contains("filename=\"____.pdf\""));
        assert!(value.contains("filename*=UTF-8''%E6%8A%A4%E7%90%86%E8%AE%A1%E5%88%92.pdf"));
    }
}
