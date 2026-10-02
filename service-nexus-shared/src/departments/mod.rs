//! 组织部门目录。
//!
//! 部门是 Nexus 的一等实体（039 从 settings KV 升格），可被 `subject_departments`
//! 以 `ON DELETE RESTRICT` 外键引用。业务侧的床位/入住/申领里的 `department`
//! 是「照护单元/病区」，与本目录无关。
use axum::extract::{Path, Query, State};
use axum::routing::get;
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use sqlx::{FromRow, SqlitePool};
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
struct DepartmentInput {
    code: String,
    name: String,
    description: Option<String>,
    parent_code: Option<String>,
    sort_order: Option<i64>,
}

#[derive(Serialize)]
struct Department {
    id: String,
    code: String,
    name: String,
    description: String,
    parent_code: String,
    sort_order: i64,
    member_count: i64,
    created_at: String,
    updated_at: String,
}

#[derive(FromRow)]
struct DepartmentRow {
    id: String,
    code: String,
    name: String,
    description: String,
    parent_code: String,
    sort_order: i64,
    member_count: i64,
    created_at: String,
    updated_at: String,
}

impl From<DepartmentRow> for Department {
    fn from(row: DepartmentRow) -> Self {
        Self {
            id: row.id,
            code: row.code,
            name: row.name,
            description: row.description,
            parent_code: row.parent_code,
            sort_order: row.sort_order,
            member_count: row.member_count,
            created_at: row.created_at,
            updated_at: row.updated_at,
        }
    }
}

#[derive(Serialize)]
struct DepartmentList {
    records: Vec<Department>,
    meta: Meta,
}

#[derive(Serialize)]
struct Meta {
    total: i64,
}

const SELECT_DEPARTMENTS: &str = "\
SELECT department.id, department.code, department.name, department.description, \
       department.parent_code, department.sort_order, \
       (SELECT COUNT(*) FROM subject_departments WHERE department_id = department.id) AS member_count, \
       department.created_at, department.updated_at \
FROM departments AS department";

async fn list(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> ApiResult<Json<DepartmentList>> {
    let (limit, offset) = PageQuery {
        page: query.page,
        page_size: query.page_size,
    }
    .limit_offset()?;
    let total: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM departments")
        .fetch_one(&state.database)
        .await?;
    let rows = sqlx::query_as::<_, DepartmentRow>(&format!(
        "{SELECT_DEPARTMENTS} ORDER BY department.sort_order, department.code LIMIT ? OFFSET ?"
    ))
    .bind(limit)
    .bind(offset)
    .fetch_all(&state.database)
    .await?;
    Ok(Json(DepartmentList {
        records: rows.into_iter().map(Department::from).collect(),
        meta: Meta { total },
    }))
}

async fn create(
    State(state): State<AppState>,
    Json(input): Json<DepartmentInput>,
) -> ApiResult<(axum::http::StatusCode, Json<Department>)> {
    validate(&input)?;
    ensure_parent_exists(&state.database, &input, None).await?;
    let id = Ulid::new().to_string();
    let result = sqlx::query(
        "INSERT INTO departments (id, code, name, description, parent_code, sort_order) \
         VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(input.code.trim())
    .bind(input.name.trim())
    .bind(input.description.as_deref().unwrap_or("").trim())
    .bind(input.parent_code.as_deref().unwrap_or("").trim())
    .bind(input.sort_order.unwrap_or(0))
    .execute(&state.database)
    .await;
    if let Err(error) = result {
        return Err(map_write_error(error, "department code already exists"));
    }
    Ok((
        axum::http::StatusCode::CREATED,
        Json(fetch(&state, &id).await?),
    ))
}

async fn get_one(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<Json<Department>> {
    Ok(Json(fetch(&state, &id).await?))
}

async fn replace(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(input): Json<DepartmentInput>,
) -> ApiResult<Json<Department>> {
    validate(&input)?;
    let existing = fetch(&state, &id).await?;
    if input.code.trim() != existing.code {
        return Err(ApiError::BadRequest(
            "code cannot be changed after creation".to_owned(),
        ));
    }
    ensure_parent_exists(&state.database, &input, Some(&id)).await?;
    let result = sqlx::query(
        "UPDATE departments SET name = ?, description = ?, parent_code = ?, sort_order = ?, \
         updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?",
    )
    .bind(input.name.trim())
    .bind(input.description.as_deref().unwrap_or("").trim())
    .bind(input.parent_code.as_deref().unwrap_or("").trim())
    .bind(input.sort_order.unwrap_or(0))
    .bind(&id)
    .execute(&state.database)
    .await;
    match result {
        Ok(result) if result.rows_affected() == 0 => {
            Err(ApiError::NotFound("department not found".to_owned()))
        }
        Ok(_) => Ok(Json(fetch(&state, &id).await?)),
        Err(error) => Err(map_write_error(error, "department code already exists")),
    }
}

async fn delete(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<axum::http::StatusCode> {
    let result = sqlx::query("DELETE FROM departments WHERE id = ?")
        .bind(&id)
        .execute(&state.database)
        .await;
    match result {
        Ok(result) if result.rows_affected() == 0 => {
            Err(ApiError::NotFound("department not found".to_owned()))
        }
        Ok(_) => Ok(axum::http::StatusCode::NO_CONTENT),
        Err(error) if is_foreign_key_violation(&error) => {
            let members: i64 =
                sqlx::query_scalar("SELECT COUNT(*) FROM subject_departments WHERE department_id = ?")
                    .bind(&id)
                    .fetch_one(&state.database)
                    .await
                    .unwrap_or(0);
            Err(ApiError::Conflict(format!(
                "department is assigned to {members} subject(s); unassign before deleting"
            )))
        }
        Err(error) => Err(ApiError::from(error)),
    }
}

async fn fetch(state: &AppState, id: &str) -> ApiResult<Department> {
    let row = sqlx::query_as::<_, DepartmentRow>(&format!("{SELECT_DEPARTMENTS} WHERE department.id = ?"))
        .bind(id)
        .fetch_optional(&state.database)
        .await?
        .ok_or_else(|| ApiError::NotFound("department not found".to_owned()))?;
    Ok(Department::from(row))
}

fn validate(input: &DepartmentInput) -> ApiResult<()> {
    let code = input.code.trim();
    if code.is_empty() || code.len() > 64 {
        return Err(ApiError::BadRequest(
            "code is required and must be at most 64 characters".to_owned(),
        ));
    }
    if !code
        .chars()
        .all(|ch| ch.is_ascii_alphanumeric() || ch == '.' || ch == '-' || ch == '_')
    {
        return Err(ApiError::BadRequest(
            "code may only contain letters, digits, dots, dashes, and underscores".to_owned(),
        ));
    }
    let name = input.name.trim();
    if name.is_empty() || name.chars().count() > 120 {
        return Err(ApiError::BadRequest(
            "name is required and must be at most 120 characters".to_owned(),
        ));
    }
    Ok(())
}

async fn ensure_parent_exists(
    database: &SqlitePool,
    input: &DepartmentInput,
    self_id: Option<&str>,
) -> ApiResult<()> {
    let parent_code = input.parent_code.as_deref().unwrap_or("").trim();
    if parent_code.is_empty() {
        return Ok(());
    }
    if parent_code == input.code.trim() {
        return Err(ApiError::BadRequest(
            "parent_code cannot point to the department itself".to_owned(),
        ));
    }
    let parent_id: Option<String> = sqlx::query_scalar("SELECT id FROM departments WHERE code = ?")
        .bind(parent_code)
        .fetch_optional(database)
        .await?;
    let Some(parent_id) = parent_id else {
        return Err(ApiError::BadRequest(format!(
            "unknown parent_code: {parent_code}"
        )));
    };
    if let Some(self_id) = self_id {
        if parent_id == self_id {
            return Err(ApiError::BadRequest(
                "parent_code cannot point to the department itself".to_owned(),
            ));
        }
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

fn is_foreign_key_violation(error: &sqlx::Error) -> bool {
    let Some(database_error) = error.as_database_error() else {
        return false;
    };
    if database_error.message().contains("FOREIGN KEY") {
        return true;
    }
    matches!(database_error.code().as_deref(), Some("19" | "787" | "1811"))
}

#[cfg(test)]
mod tests {
    use super::{DepartmentInput, validate};

    fn input(code: &str, name: &str) -> DepartmentInput {
        DepartmentInput {
            code: code.to_owned(),
            name: name.to_owned(),
            description: None,
            parent_code: None,
            sort_order: None,
        }
    }

    #[test]
    fn code_and_name_are_required() {
        assert!(validate(&input("nursing", "护理部")).is_ok());
        assert!(validate(&input("", "护理部")).is_err());
        assert!(validate(&input("   ", "护理部")).is_err());
        assert!(validate(&input("nursing", "")).is_err());
        assert!(validate(&input("nursing", "   ")).is_err());
        assert!(validate(&input("a".repeat(65).as_str(), "护理部")).is_err());
    }
}
