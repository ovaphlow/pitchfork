//! 用户 ↔ 部门归属。
//!
//! 一人一主部门（`UNIQUE(subject_id)`）。部门目录见 `departments`，本模块只回答
//! 「谁属于哪个部门」。`subject_id` 来自 IDP（跨服务引用），只校验 ULID 形态。
use axum::extract::{Path, Query, State};
use axum::routing::{get, put};
use axum::{Extension, Json, Router};
use serde::{Deserialize, Serialize};
use sqlx::{FromRow, QueryBuilder, Sqlite};
use ulid::Ulid;

use crate::auth::Identity;
use crate::error::{ApiError, ApiResult};
use crate::{AppState, PageQuery};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/", get(list))
        .route("/subjects/{subject_id}", put(replace))
}

#[derive(Deserialize)]
struct ListQuery {
    subject_ids: Option<String>,
    page: Option<i64>,
    page_size: Option<i64>,
}

#[derive(Deserialize)]
struct DepartmentInput {
    department_id: Option<String>,
}

#[derive(Serialize)]
struct Assignment {
    id: String,
    subject_id: String,
    department_id: String,
    department_code: String,
    department_name: String,
    granted_by_subject_id: String,
    created_at: String,
    updated_at: String,
}

#[derive(FromRow)]
struct AssignmentRow {
    id: String,
    subject_id: String,
    department_id: String,
    department_code: String,
    department_name: String,
    granted_by_subject_id: String,
    created_at: String,
    updated_at: String,
}

impl From<AssignmentRow> for Assignment {
    fn from(row: AssignmentRow) -> Self {
        Self {
            id: row.id,
            subject_id: row.subject_id,
            department_id: row.department_id,
            department_code: row.department_code,
            department_name: row.department_name,
            granted_by_subject_id: row.granted_by_subject_id,
            created_at: row.created_at,
            updated_at: row.updated_at,
        }
    }
}

#[derive(Serialize)]
struct AssignmentList {
    records: Vec<Assignment>,
    meta: Meta,
}

#[derive(Serialize)]
struct Meta {
    total: i64,
}

const SELECT_ASSIGNMENTS: &str = "\
SELECT assignment.id, assignment.subject_id, assignment.department_id, \
       department.code AS department_code, department.name AS department_name, \
       assignment.granted_by_subject_id, assignment.created_at, assignment.updated_at \
FROM subject_departments AS assignment \
JOIN departments AS department ON department.id = assignment.department_id";

async fn list(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> ApiResult<Json<AssignmentList>> {
    let subject_ids = parse_subject_ids(query.subject_ids.as_deref())?;
    let (limit, offset) = PageQuery {
        page: query.page,
        page_size: query.page_size,
    }
    .limit_offset()?;

    let mut count_builder = QueryBuilder::<Sqlite>::new(
        "SELECT COUNT(*) FROM subject_departments AS assignment",
    );
    push_subject_filter(&mut count_builder, &subject_ids);
    let total: i64 = count_builder.build_query_scalar().fetch_one(&state.database).await?;

    let mut builder = QueryBuilder::<Sqlite>::new(SELECT_ASSIGNMENTS);
    push_subject_filter(&mut builder, &subject_ids);
    builder.push(" ORDER BY assignment.subject_id");
    builder.push(" LIMIT ").push_bind(limit);
    builder.push(" OFFSET ").push_bind(offset);

    let rows = builder
        .build_query_as::<AssignmentRow>()
        .fetch_all(&state.database)
        .await?;
    Ok(Json(AssignmentList {
        records: rows.into_iter().map(Assignment::from).collect(),
        meta: Meta { total },
    }))
}

/// 全量替换某个主体的部门归属；`department_id: null` 表示清空。
async fn replace(
    State(state): State<AppState>,
    Extension(identity): Extension<Identity>,
    Path(subject_id): Path<String>,
    Json(input): Json<DepartmentInput>,
) -> ApiResult<Json<Vec<Assignment>>> {
    let subject_id = validate_ulid(&subject_id, "subject_id")?;
    let department_id = match input.department_id {
        Some(value) if !value.trim().is_empty() => {
            let candidate = value.trim().to_owned();
            let exists: Option<String> =
                sqlx::query_scalar("SELECT id FROM departments WHERE id = ?")
                    .bind(&candidate)
                    .fetch_optional(&state.database)
                    .await?;
            if exists.is_none() {
                return Err(ApiError::BadRequest(format!(
                    "unknown department_id: {candidate}"
                )));
            }
            Some(candidate)
        }
        _ => None,
    };

    let mut transaction = state.database.begin().await?;
    sqlx::query("DELETE FROM subject_departments WHERE subject_id = ?")
        .bind(&subject_id)
        .execute(&mut *transaction)
        .await?;
    if let Some(department_id) = &department_id {
        sqlx::query(
            "INSERT INTO subject_departments (id, subject_id, department_id, granted_by_subject_id) \
             VALUES (?, ?, ?, ?)",
        )
        .bind(Ulid::new().to_string())
        .bind(&subject_id)
        .bind(department_id)
        .bind(&identity.subject_id)
        .execute(&mut *transaction)
        .await?;
    }
    transaction.commit().await?;

    fetch_for_subject(&state, &subject_id).await.map(Json)
}

async fn fetch_for_subject(state: &AppState, subject_id: &str) -> ApiResult<Vec<Assignment>> {
    let rows = sqlx::query_as::<_, AssignmentRow>(&format!(
        "{SELECT_ASSIGNMENTS} WHERE assignment.subject_id = ?"
    ))
    .bind(subject_id)
    .fetch_all(&state.database)
    .await?;
    Ok(rows.into_iter().map(Assignment::from).collect())
}

fn push_subject_filter<'a>(builder: &mut QueryBuilder<'a, Sqlite>, subject_ids: &'a [String]) {
    if subject_ids.is_empty() {
        return;
    }
    builder.push(" WHERE assignment.subject_id IN (");
    let mut separated = builder.separated(", ");
    for subject_id in subject_ids {
        separated.push_bind(subject_id);
    }
    separated.push_unseparated(")");
}

fn validate_ulid(value: &str, field: &str) -> ApiResult<String> {
    let trimmed = value.trim();
    if Ulid::from_string(trimmed).is_err() {
        return Err(ApiError::BadRequest(format!(
            "{field} must be a 26 character ULID"
        )));
    }
    Ok(trimmed.to_owned())
}

/// 逗号分隔的 subject_ids；空段忽略，重复去重，非法 ULID 直接 400。
fn parse_subject_ids(raw: Option<&str>) -> ApiResult<Vec<String>> {
    let Some(raw) = raw else {
        return Ok(Vec::new());
    };
    let mut result: Vec<String> = Vec::new();
    for candidate in raw.split(',') {
        let candidate = candidate.trim();
        if candidate.is_empty() {
            continue;
        }
        let validated = validate_ulid(candidate, "subject_id")?;
        if !result.contains(&validated) {
            result.push(validated);
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::{parse_subject_ids, validate_ulid};
    use ulid::Ulid;

    #[test]
    fn subject_ids_must_be_ulids() {
        let subject_id = Ulid::new().to_string();
        assert!(parse_subject_ids(Some(&format!("{subject_id}, {subject_id}"))).is_ok());
        assert_eq!(
            parse_subject_ids(Some(&format!("{subject_id}, {subject_id}")))
                .unwrap()
                .len(),
            1
        );
        assert!(parse_subject_ids(Some("not-a-ulid")).is_err());
        assert!(validate_ulid("subject-1", "subject_id").is_err());
    }
}
