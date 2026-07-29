use axum::extract::{Path, Query, State};
use axum::routing::get;
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::{FromRow, QueryBuilder, Sqlite};
use ulid::Ulid;

use crate::error::{ApiError, ApiResult};
use crate::{AppState, PageQuery};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/", get(list).post(create))
        .route("/{id}", get(get_one).put(replace).delete(delete))
}

#[derive(Deserialize)]
struct ListQuery {
    page: Option<i64>,
    page_size: Option<i64>,
    category: Option<String>,
}

#[derive(Deserialize)]
struct SettingInput {
    category: String,
    code: String,
    #[serde(default)]
    root_code: String,
    #[serde(default)]
    parent_code: String,
    payload: Value,
}

#[derive(Serialize)]
struct Setting {
    id: String,
    category: String,
    code: String,
    root_code: String,
    parent_code: String,
    payload: Value,
    created_at: String,
    updated_at: String,
}

#[derive(FromRow)]
struct SettingRow {
    id: String,
    category: String,
    code: String,
    root_code: String,
    parent_code: String,
    payload: String,
    created_at: String,
    updated_at: String,
}

impl TryFrom<SettingRow> for Setting {
    type Error = ApiError;

    fn try_from(row: SettingRow) -> Result<Self, Self::Error> {
        Ok(Self {
            id: row.id,
            category: row.category,
            code: row.code,
            root_code: row.root_code,
            parent_code: row.parent_code,
            payload: serde_json::from_str(&row.payload)?,
            created_at: row.created_at,
            updated_at: row.updated_at,
        })
    }
}

async fn list(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> ApiResult<Json<Vec<Setting>>> {
    let (limit, offset) = PageQuery { page: query.page, page_size: query.page_size }.limit_offset()?;
    let mut builder: QueryBuilder<Sqlite> = QueryBuilder::new(
        "SELECT id, category, code, root_code, parent_code, payload, created_at, updated_at FROM settings",
    );
    if let Some(category) = query.category.filter(|value| !value.is_empty()) {
        builder.push(" WHERE category = ").push_bind(category);
    }
    builder
        .push(" ORDER BY category, code LIMIT ")
        .push_bind(limit)
        .push(" OFFSET ")
        .push_bind(offset);
    let rows = builder
        .build_query_as::<SettingRow>()
        .fetch_all(&state.database)
        .await?;
    rows.into_iter()
        .map(Setting::try_from)
        .collect::<ApiResult<Vec<_>>>()
        .map(Json)
}

async fn create(
    State(state): State<AppState>,
    Json(input): Json<SettingInput>,
) -> ApiResult<(axum::http::StatusCode, Json<Setting>)> {
    validate(&input)?;
    let id = Ulid::new().to_string();
    let payload = serde_json::to_string(&input.payload)
        .map_err(|_| ApiError::BadRequest("payload must be JSON".to_owned()))?;
    let result = sqlx::query(
        "INSERT INTO settings (id, category, code, root_code, parent_code, payload) VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(&input.category)
    .bind(&input.code)
    .bind(&input.root_code)
    .bind(&input.parent_code)
    .bind(payload)
    .execute(&state.database)
    .await;
    if let Err(error) = result {
        return Err(map_write_error(
            error,
            "setting category and code already exist",
        ));
    }
    let setting = fetch(&state, &id).await?;
    Ok((axum::http::StatusCode::CREATED, Json(setting)))
}

async fn get_one(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<Json<Setting>> {
    Ok(Json(fetch(&state, &id).await?))
}

async fn replace(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(input): Json<SettingInput>,
) -> ApiResult<Json<Setting>> {
    validate(&input)?;
    let payload = serde_json::to_string(&input.payload)
        .map_err(|_| ApiError::BadRequest("payload must be JSON".to_owned()))?;
    let result = sqlx::query(
        "UPDATE settings SET category = ?, code = ?, root_code = ?, parent_code = ?, payload = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?",
    )
    .bind(&input.category)
    .bind(&input.code)
    .bind(&input.root_code)
    .bind(&input.parent_code)
    .bind(payload)
    .bind(&id)
    .execute(&state.database)
    .await;
    match result {
        Ok(result) if result.rows_affected() == 0 => {
            Err(ApiError::NotFound("setting not found".to_owned()))
        }
        Ok(_) => Ok(Json(fetch(&state, &id).await?)),
        Err(error) => Err(map_write_error(
            error,
            "setting category and code already exist",
        )),
    }
}

async fn delete(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<axum::http::StatusCode> {
    let result = sqlx::query("DELETE FROM settings WHERE id = ?")
        .bind(id)
        .execute(&state.database)
        .await?;
    if result.rows_affected() == 0 {
        return Err(ApiError::NotFound("setting not found".to_owned()));
    }
    Ok(axum::http::StatusCode::NO_CONTENT)
}

async fn fetch(state: &AppState, id: &str) -> ApiResult<Setting> {
    let row = sqlx::query_as::<_, SettingRow>(
        "SELECT id, category, code, root_code, parent_code, payload, created_at, updated_at FROM settings WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&state.database)
    .await?
    .ok_or_else(|| ApiError::NotFound("setting not found".to_owned()))?;
    Setting::try_from(row)
}

fn validate(input: &SettingInput) -> ApiResult<()> {
    if input.category.trim().is_empty() || input.code.trim().is_empty() {
        return Err(ApiError::BadRequest(
            "category and code are required".to_owned(),
        ));
    }
    if !input.payload.is_object() {
        return Err(ApiError::BadRequest(
            "payload must be a JSON object".to_owned(),
        ));
    }
    Ok(())
}

fn map_write_error(error: sqlx::Error, conflict_detail: &str) -> ApiError {
    if error
        .as_database_error()
        .and_then(|database_error| database_error.code())
        .is_some_and(|code| code == "2067" || code == "1555")
    {
        ApiError::Conflict(conflict_detail.to_owned())
    } else {
        ApiError::from(error)
    }
}

#[cfg(test)]
mod tests {
    use super::{SettingInput, validate};
    use serde_json::json;

    #[test]
    fn a_setting_requires_an_object_payload() {
        let input = SettingInput {
            category: "department".to_owned(),
            code: "care".to_owned(),
            root_code: String::new(),
            parent_code: String::new(),
            payload: json!("not an object"),
        };
        assert!(validate(&input).is_err());
    }
}
