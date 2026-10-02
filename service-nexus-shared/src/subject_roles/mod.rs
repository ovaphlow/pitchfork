//! 用户 ↔ 角色分配。
//!
//! 角色目录（`roles`）回答"有哪些角色"，本模块回答"谁拥有哪些角色"。
//! 分配是集合语义：写入使用 `PUT /subject-roles/subjects/{subjectID}` 全量替换。
//! `subject_id` 来自 IDP（跨服务引用），只校验 ULID 形态，不做存在性探活。

use axum::extract::{Path, Query, State};
use axum::routing::{get, put};
use axum::{Extension, Json, Router};
use serde::{Deserialize, Serialize};
use sqlx::{FromRow, QueryBuilder, Sqlite};
use ulid::Ulid;

use crate::auth::Identity;
use crate::error::{ApiError, ApiResult};
use crate::{AppState, PageQuery};

const MAX_ROLE_CODES_PER_SUBJECT: usize = 100;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/", get(list))
        .route("/subjects/{subject_id}", put(replace))
}

/// 产品后端取"当前主体能用什么"的入口：只需一次调用，不必自己 join 角色与权限码。
pub fn permissions_router() -> Router<AppState> {
    Router::new().route("/", get(permissions))
}

#[derive(Deserialize)]
struct PermissionsQuery {
    subject_id: String,
}

#[derive(Serialize)]
struct SubjectPermissions {
    subject_id: String,
    role_codes: Vec<String>,
    permission_codes: Vec<String>,
    source: &'static str,
}

#[derive(FromRow)]
struct RolePermissionsRow {
    role_code: String,
    permission_codes: String,
}

#[derive(Deserialize)]
struct ListQuery {
    subject_ids: Option<String>,
    page: Option<i64>,
    page_size: Option<i64>,
}

#[derive(Deserialize)]
struct RoleCodesInput {
    role_codes: Vec<String>,
}

#[derive(Serialize)]
struct Assignment {
    id: String,
    subject_id: String,
    role_id: String,
    role_code: String,
    role_display_name: String,
    granted_by_subject_id: String,
    created_at: String,
    updated_at: String,
}

#[derive(FromRow)]
struct AssignmentRow {
    id: String,
    subject_id: String,
    role_id: String,
    role_code: String,
    role_display_name: String,
    granted_by_subject_id: String,
    created_at: String,
    updated_at: String,
}

impl From<AssignmentRow> for Assignment {
    fn from(row: AssignmentRow) -> Self {
        Self {
            id: row.id,
            subject_id: row.subject_id,
            role_id: row.role_id,
            role_code: row.role_code,
            role_display_name: row.role_display_name,
            granted_by_subject_id: row.granted_by_subject_id,
            created_at: row.created_at,
            updated_at: row.updated_at,
        }
    }
}

#[derive(FromRow)]
struct RoleIDRow {
    id: String,
    role_code: String,
}

const SELECT_ASSIGNMENTS: &str = "\
SELECT assignment.id, assignment.subject_id, assignment.role_id, \
       role.role_code, role.display_name AS role_display_name, \
       assignment.granted_by_subject_id, assignment.created_at, assignment.updated_at \
FROM subject_roles AS assignment \
JOIN roles AS role ON role.id = assignment.role_id";

async fn list(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> ApiResult<Json<Vec<Assignment>>> {
    let subject_ids = parse_subject_ids(query.subject_ids.as_deref())?;
    let (limit, offset) = PageQuery {
        page: query.page,
        page_size: query.page_size,
    }
    .limit_offset()?;

    let mut builder = QueryBuilder::<Sqlite>::new(SELECT_ASSIGNMENTS);
    if !subject_ids.is_empty() {
        builder.push(" WHERE assignment.subject_id IN (");
        let mut separated = builder.separated(", ");
        for subject_id in &subject_ids {
            separated.push_bind(subject_id);
        }
        separated.push_unseparated(")");
    }
    builder.push(" ORDER BY assignment.subject_id, role.role_code");
    builder.push(" LIMIT ").push_bind(limit);
    builder.push(" OFFSET ").push_bind(offset);

    let rows = builder
        .build_query_as::<AssignmentRow>()
        .fetch_all(&state.database)
        .await?;
    Ok(Json(rows.into_iter().map(Assignment::from).collect()))
}

/// 全量替换某个主体的角色集合。空数组表示清空。
async fn replace(
    State(state): State<AppState>,
    Extension(identity): Extension<Identity>,
    Path(subject_id): Path<String>,
    Json(input): Json<RoleCodesInput>,
) -> ApiResult<Json<Vec<Assignment>>> {
    let subject_id = validate_subject_id(&subject_id)?;
    let role_codes = normalize_role_codes(input.role_codes);
    if role_codes.len() > MAX_ROLE_CODES_PER_SUBJECT {
        return Err(ApiError::BadRequest(format!(
            "at most {MAX_ROLE_CODES_PER_SUBJECT} role_codes are allowed"
        )));
    }

    let mut transaction = state.database.begin().await?;
    let role_ids = resolve_role_ids(&mut transaction, &role_codes).await?;

    sqlx::query("DELETE FROM subject_roles WHERE subject_id = ?")
        .bind(&subject_id)
        .execute(&mut *transaction)
        .await?;
    for role_id in &role_ids {
        sqlx::query(
            "INSERT INTO subject_roles (id, subject_id, role_id, granted_by_subject_id) VALUES (?, ?, ?, ?)",
        )
        .bind(Ulid::new().to_string())
        .bind(&subject_id)
        .bind(role_id)
        .bind(&identity.subject_id)
        .execute(&mut *transaction)
        .await?;
    }
    transaction.commit().await?;

    fetch_for_subject(&state, &subject_id).await.map(Json)
}

/// 把角色码解析成目录里的角色 ID；出现目录中不存在的角色码即 400。
async fn resolve_role_ids(
    transaction: &mut sqlx::Transaction<'_, Sqlite>,
    role_codes: &[String],
) -> ApiResult<Vec<String>> {
    if role_codes.is_empty() {
        return Ok(Vec::new());
    }

    let mut builder =
        QueryBuilder::<Sqlite>::new("SELECT id, role_code FROM roles WHERE role_code IN (");
    let mut separated = builder.separated(", ");
    for role_code in role_codes {
        separated.push_bind(role_code);
    }
    separated.push_unseparated(")");
    let rows = builder
        .build_query_as::<RoleIDRow>()
        .fetch_all(&mut **transaction)
        .await?;

    let unknown = role_codes
        .iter()
        .filter(|code| !rows.iter().any(|row| &row.role_code == *code))
        .cloned()
        .collect::<Vec<_>>();
    if !unknown.is_empty() {
        return Err(ApiError::BadRequest(format!(
            "unknown role_code: {}",
            unknown.join(", ")
        )));
    }

    // 按请求顺序返回，保证插入顺序稳定。
    Ok(role_codes
        .iter()
        .filter_map(|code| {
            rows.iter()
                .find(|row| &row.role_code == code)
                .map(|row| row.id.clone())
        })
        .collect())
}

async fn fetch_for_subject(state: &AppState, subject_id: &str) -> ApiResult<Vec<Assignment>> {
    let rows = sqlx::query_as::<_, AssignmentRow>(&format!(
        "{SELECT_ASSIGNMENTS} WHERE assignment.subject_id = ? ORDER BY role.role_code"
    ))
    .bind(subject_id)
    .fetch_all(&state.database)
    .await?;
    Ok(rows.into_iter().map(Assignment::from).collect())
}

fn validate_subject_id(value: &str) -> ApiResult<String> {
    let trimmed = value.trim();
    if Ulid::from_string(trimmed).is_err() {
        return Err(ApiError::BadRequest(
            "subject_id must be a 26 character ULID".to_owned(),
        ));
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
        let validated = validate_subject_id(candidate)?;
        if !result.contains(&validated) {
            result.push(validated);
        }
    }
    Ok(result)
}

fn normalize_role_codes(codes: Vec<String>) -> Vec<String> {
    let mut result: Vec<String> = Vec::new();
    for code in codes {
        let trimmed = code.trim().to_owned();
        if !trimmed.is_empty() && !result.contains(&trimmed) {
            result.push(trimmed);
        }
    }
    result
}

/// 取主体的有效权限：角色码 + 角色权限码的并集（去重、保持角色顺序稳定）。
async fn permissions(
    State(state): State<AppState>,
    Query(query): Query<PermissionsQuery>,
) -> ApiResult<Json<SubjectPermissions>> {
    let subject_id = validate_subject_id(&query.subject_id)?;

    let rows = sqlx::query_as::<_, RolePermissionsRow>(
        "SELECT role.role_code, role.permission_codes \
         FROM subject_roles AS assignment \
         JOIN roles AS role ON role.id = assignment.role_id \
         WHERE assignment.subject_id = ? \
         ORDER BY role.role_code",
    )
    .bind(&subject_id)
    .fetch_all(&state.database)
    .await?;

    let mut role_codes = Vec::with_capacity(rows.len());
    let mut permission_codes: Vec<String> = Vec::new();
    for row in rows {
        role_codes.push(row.role_code.clone());
        for code in serde_json::from_str::<Vec<String>>(&row.permission_codes)? {
            let trimmed = code.trim().to_owned();
            if !trimmed.is_empty() && !permission_codes.contains(&trimmed) {
                permission_codes.push(trimmed);
            }
        }
    }

    Ok(Json(SubjectPermissions {
        subject_id,
        role_codes,
        permission_codes,
        source: "nexus",
    }))
}

#[cfg(test)]
mod tests {
    use super::{normalize_role_codes, parse_subject_ids, validate_subject_id};

    const SUBJECT_ID: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    #[test]
    fn subject_id_must_be_a_ulid() {
        assert_eq!(validate_subject_id(SUBJECT_ID).unwrap(), SUBJECT_ID);
        assert!(validate_subject_id("subject-1").is_err());
        assert!(validate_subject_id("").is_err());
    }

    #[test]
    fn subject_ids_are_split_deduplicated_and_validated() {
        let raw = format!("{SUBJECT_ID}, 01BX5ZZKBKACTAV9WEVGEMMVRZ ,{SUBJECT_ID}");
        assert_eq!(
            parse_subject_ids(Some(&raw)).unwrap(),
            vec![SUBJECT_ID, "01BX5ZZKBKACTAV9WEVGEMMVRZ"]
        );
        assert!(parse_subject_ids(None).unwrap().is_empty());
        assert!(parse_subject_ids(Some("")).unwrap().is_empty());
        assert!(parse_subject_ids(Some("not-a-subject")).is_err());
    }

    #[test]
    fn role_codes_are_trimmed_and_deduplicated() {
        assert_eq!(
            normalize_role_codes(vec![
                " nursing.staff ".to_owned(),
                "nursing.staff".to_owned(),
                "".to_owned(),
                "pharmacy.manager".to_owned(),
            ]),
            vec!["nursing.staff", "pharmacy.manager"]
        );
    }
}
