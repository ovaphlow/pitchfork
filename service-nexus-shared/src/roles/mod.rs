use axum::extract::{Path, Query, State};
use axum::routing::get;
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
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
}

#[derive(Deserialize)]
struct RoleInput {
    role_code: String,
    display_name: String,
    description: Option<String>,
    permission_codes: Option<Vec<String>>,
}

#[derive(Serialize)]
struct Role {
    id: String,
    role_code: String,
    display_name: String,
    description: String,
    permission_codes: Vec<String>,
    created_at: String,
    updated_at: String,
}

#[derive(FromRow)]
struct RoleRow {
    id: String,
    role_code: String,
    display_name: String,
    description: String,
    permission_codes: String,
    created_at: String,
    updated_at: String,
}

impl TryFrom<RoleRow> for Role {
    type Error = ApiError;

    fn try_from(row: RoleRow) -> Result<Self, Self::Error> {
        Ok(Self {
            id: row.id,
            role_code: row.role_code,
            display_name: row.display_name,
            description: row.description,
            permission_codes: serde_json::from_str(&row.permission_codes)?,
            created_at: row.created_at,
            updated_at: row.updated_at,
        })
    }
}

async fn list(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> ApiResult<Json<Vec<Role>>> {
    let (limit, offset) = PageQuery { page: query.page, page_size: query.page_size }.limit_offset()?;
    let rows = sqlx::query_as::<_, RoleRow>(
        "SELECT id, role_code, display_name, description, permission_codes, created_at, updated_at FROM roles ORDER BY role_code LIMIT ? OFFSET ?",
    )
    .bind(limit)
    .bind(offset)
    .fetch_all(&state.database)
    .await?;
    rows.into_iter()
        .map(Role::try_from)
        .collect::<ApiResult<Vec<_>>>()
        .map(Json)
}

async fn create(
    State(state): State<AppState>,
    Json(input): Json<RoleInput>,
) -> ApiResult<(axum::http::StatusCode, Json<Role>)> {
    validate(&input)?;
    let id = Ulid::new().to_string();
    let permission_codes = normalized_permissions(input.permission_codes)?;
    let result = sqlx::query(
        "INSERT INTO roles (id, role_code, display_name, description, permission_codes) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(input.role_code.trim())
    .bind(input.display_name.trim())
    .bind(input.description.as_deref().unwrap_or("").trim())
    .bind(serde_json::to_string(&permission_codes)?)
    .execute(&state.database)
    .await;
    if let Err(error) = result {
        return Err(map_write_error(error, "role_code already exists"));
    }
    Ok((
        axum::http::StatusCode::CREATED,
        Json(fetch(&state, &id).await?),
    ))
}

async fn get_one(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<Json<Role>> {
    Ok(Json(fetch(&state, &id).await?))
}

async fn replace(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(input): Json<RoleInput>,
) -> ApiResult<Json<Role>> {
    validate(&input)?;
    let existing = fetch(&state, &id).await?;
    if input.role_code.trim() != existing.role_code {
        return Err(ApiError::BadRequest(
            "role_code cannot be changed after creation".to_owned(),
        ));
    }
    let permission_codes = normalized_permissions(input.permission_codes)?;
    let result = sqlx::query(
        "UPDATE roles SET display_name = ?, description = ?, permission_codes = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?",
    )
    .bind(input.display_name.trim())
    .bind(input.description.as_deref().unwrap_or("").trim())
    .bind(serde_json::to_string(&permission_codes)?)
    .bind(&id)
    .execute(&state.database)
    .await;
    match result {
        Ok(result) if result.rows_affected() == 0 => {
            Err(ApiError::NotFound("role not found".to_owned()))
        }
        Ok(_) => Ok(Json(fetch(&state, &id).await?)),
        Err(error) => Err(map_write_error(error, "role_code already exists")),
    }
}

async fn delete(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<axum::http::StatusCode> {
    let result = sqlx::query("DELETE FROM roles WHERE id = ?")
        .bind(id)
        .execute(&state.database)
        .await?;
    if result.rows_affected() == 0 {
        return Err(ApiError::NotFound("role not found".to_owned()));
    }
    Ok(axum::http::StatusCode::NO_CONTENT)
}

async fn fetch(state: &AppState, id: &str) -> ApiResult<Role> {
    let row = sqlx::query_as::<_, RoleRow>(
        "SELECT id, role_code, display_name, description, permission_codes, created_at, updated_at FROM roles WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&state.database)
    .await?
    .ok_or_else(|| ApiError::NotFound("role not found".to_owned()))?;
    Role::try_from(row)
}

fn validate(input: &RoleInput) -> ApiResult<()> {
    let role_code = input.role_code.trim();
    if role_code.is_empty() || role_code.len() > 64 {
        return Err(ApiError::BadRequest(
            "role_code is required and must be at most 64 characters".to_owned(),
        ));
    }
    if !role_code
        .chars()
        .all(|ch| ch.is_ascii_lowercase() || ch.is_ascii_digit() || ch == '.')
    {
        return Err(ApiError::BadRequest(
            "role_code may only contain lowercase letters, digits, and dots".to_owned(),
        ));
    }
    if input.display_name.trim().is_empty() {
        return Err(ApiError::BadRequest("display_name is required".to_owned()));
    }
    Ok(())
}

fn normalized_permissions(codes: Option<Vec<String>>) -> ApiResult<Vec<String>> {
    let Some(codes) = codes else {
        return Ok(Vec::new());
    };
    let mut seen = std::collections::HashSet::new();
    let mut result = Vec::with_capacity(codes.len());
    for code in codes {
        let trimmed = code.trim().to_owned();
        if !trimmed.is_empty() && seen.insert(trimmed.clone()) {
            result.push(trimmed);
        }
    }
    Ok(result)
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
    use super::{RoleInput, normalized_permissions, validate};

    fn input(role_code: &str, display_name: &str) -> RoleInput {
        RoleInput {
            role_code: role_code.to_owned(),
            display_name: display_name.to_owned(),
            description: None,
            permission_codes: None,
        }
    }

    #[test]
    fn role_code_must_be_lowercase_alphanumeric_or_dots() {
        assert!(validate(&input("nursing.staff", "护理人员")).is_ok());
        assert!(validate(&input("pharmacy.manager", "药房管理员")).is_ok());
        assert!(validate(&input("Nursing.staff", "护理人员")).is_err());
        assert!(validate(&input("nursing staff", "护理人员")).is_err());
        assert!(validate(&input("nursing_staff", "护理人员")).is_err());
        assert!(validate(&input("", "护理人员")).is_err());
        assert!(validate(&input("   ", "护理人员")).is_err());
        assert!(validate(&input("a".repeat(65).as_str(), "护理人员")).is_err());
        assert!(validate(&input("a".repeat(64).as_str(), "护理人员")).is_ok());
    }

    #[test]
    fn display_name_is_required() {
        assert!(validate(&input("nursing.staff", "")).is_err());
        assert!(validate(&input("nursing.staff", "   ")).is_err());
        assert!(validate(&input("nursing.staff", "护理人员")).is_ok());
    }

    #[test]
    fn permissions_are_deduplicated_keeping_first_occurrence() {
        let deduped = normalized_permissions(Some(vec![
            "nursing:execute".to_owned(),
            "nursing:record".to_owned(),
            "nursing:execute".to_owned(),
            "  nursing:record  ".to_owned(),
            "".to_owned(),
            "pharmacy:manage".to_owned(),
        ]))
        .expect("normalization succeeds");
        assert_eq!(
            deduped,
            vec!["nursing:execute", "nursing:record", "pharmacy:manage"]
        );
    }

    #[test]
    fn missing_permissions_means_empty_array() {
        let empty = normalized_permissions(None).expect("normalization succeeds");
        assert!(empty.is_empty());
    }
}