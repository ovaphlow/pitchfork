use axum::extract::{Extension, Path, Query, State};
use axum::routing::get;
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::{FromRow, QueryBuilder, Sqlite};
use ulid::Ulid;

use crate::auth::Identity;
use crate::error::{ApiError, ApiResult};
use crate::{AppState, PageQuery};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/", get(list).post(create))
        .route("/{id}", get(get_one).put(update).delete(delete))
}

#[derive(Deserialize)]
struct ListQuery {
    page: Option<i64>,
    page_size: Option<i64>,
    actor_id: Option<String>,
    target_type: Option<String>,
    target_id: Option<String>,
    interaction_type: Option<String>,
}

#[derive(Deserialize)]
struct CreateInput {
    target_type: String,
    target_id: String,
    interaction_type: String,
    value: Option<f64>,
    #[serde(default = "empty_object")]
    payload: Value,
}

#[derive(Deserialize)]
struct UpdateInput {
    value: Option<f64>,
    payload: Option<Value>,
}

#[derive(Serialize)]
struct Interaction {
    id: String,
    actor_id: String,
    target_type: String,
    target_id: String,
    interaction_type: String,
    value: Option<f64>,
    payload: Value,
    created_at: String,
    updated_at: String,
}

#[derive(FromRow)]
struct InteractionRow {
    id: String,
    actor_id: String,
    target_type: String,
    target_id: String,
    interaction_type: String,
    value: Option<f64>,
    payload: String,
    created_at: String,
    updated_at: String,
}

impl TryFrom<InteractionRow> for Interaction {
    type Error = ApiError;

    fn try_from(row: InteractionRow) -> Result<Self, Self::Error> {
        Ok(Self {
            id: row.id,
            actor_id: row.actor_id,
            target_type: row.target_type,
            target_id: row.target_id,
            interaction_type: row.interaction_type,
            value: row.value,
            payload: serde_json::from_str(&row.payload)?,
            created_at: row.created_at,
            updated_at: row.updated_at,
        })
    }
}

async fn list(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> ApiResult<Json<Vec<Interaction>>> {
    let (limit, offset) = PageQuery { page: query.page, page_size: query.page_size }.limit_offset()?;
    let mut builder: QueryBuilder<Sqlite> = QueryBuilder::new(
        "SELECT id, actor_id, target_type, target_id, interaction_type, value, payload, created_at, updated_at FROM interactions WHERE 1 = 1",
    );
    if let Some(value) = query.actor_id.filter(|value| !value.is_empty()) {
        builder.push(" AND actor_id = ").push_bind(value);
    }
    if let Some(value) = query.target_type.filter(|value| !value.is_empty()) {
        builder.push(" AND target_type = ").push_bind(value);
    }
    if let Some(value) = query.target_id.filter(|value| !value.is_empty()) {
        builder.push(" AND target_id = ").push_bind(value);
    }
    if let Some(value) = query.interaction_type.filter(|value| !value.is_empty()) {
        builder.push(" AND interaction_type = ").push_bind(value);
    }
    builder
        .push(" ORDER BY created_at DESC LIMIT ")
        .push_bind(limit)
        .push(" OFFSET ")
        .push_bind(offset);
    builder
        .build_query_as::<InteractionRow>()
        .fetch_all(&state.database)
        .await?
        .into_iter()
        .map(Interaction::try_from)
        .collect::<ApiResult<Vec<_>>>()
        .map(Json)
}

async fn create(
    State(state): State<AppState>,
    Extension(identity): Extension<Identity>,
    Json(input): Json<CreateInput>,
) -> ApiResult<(axum::http::StatusCode, Json<Interaction>)> {
    validate_create(&input)?;
    let id = Ulid::new().to_string();
    let result = sqlx::query(
        "INSERT INTO interactions (id, actor_id, target_type, target_id, interaction_type, value, payload) VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(&identity.subject_id)
    .bind(&input.target_type)
    .bind(&input.target_id)
    .bind(&input.interaction_type)
    .bind(input.value)
    .bind(serde_json::to_string(&input.payload)?)
    .execute(&state.database)
    .await;
    match result {
        Ok(_) => Ok((
            axum::http::StatusCode::CREATED,
            Json(fetch(&state, &id).await?),
        )),
        Err(error) if is_unique_constraint(&error) => Err(ApiError::Conflict(
            "this interaction already exists for the current actor and target".to_owned(),
        )),
        Err(error) => Err(ApiError::from(error)),
    }
}

async fn get_one(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<Json<Interaction>> {
    Ok(Json(fetch(&state, &id).await?))
}

async fn update(
    State(state): State<AppState>,
    Extension(identity): Extension<Identity>,
    Path(id): Path<String>,
    Json(input): Json<UpdateInput>,
) -> ApiResult<Json<Interaction>> {
    if input.value.is_none() && input.payload.is_none() {
        return Err(ApiError::BadRequest(
            "value or payload is required".to_owned(),
        ));
    }
    if let Some(payload) = &input.payload {
        validate_payload(payload)?;
    }
    let existing = fetch_owned(&state, &id, &identity.subject_id).await?;
    let payload = input.payload.unwrap_or(existing.payload);
    let value = input.value.or(existing.value);
    sqlx::query(
        "UPDATE interactions SET value = ?, payload = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ? AND actor_id = ?",
    )
    .bind(value)
    .bind(serde_json::to_string(&payload)?)
    .bind(&id)
    .bind(&identity.subject_id)
    .execute(&state.database)
    .await?;
    Ok(Json(fetch(&state, &id).await?))
}

async fn delete(
    State(state): State<AppState>,
    Extension(identity): Extension<Identity>,
    Path(id): Path<String>,
) -> ApiResult<axum::http::StatusCode> {
    let result = sqlx::query("DELETE FROM interactions WHERE id = ? AND actor_id = ?")
        .bind(id)
        .bind(identity.subject_id)
        .execute(&state.database)
        .await?;
    if result.rows_affected() == 0 {
        return Err(ApiError::NotFound("interaction not found".to_owned()));
    }
    Ok(axum::http::StatusCode::NO_CONTENT)
}

async fn fetch(state: &AppState, id: &str) -> ApiResult<Interaction> {
    let row = sqlx::query_as::<_, InteractionRow>(
        "SELECT id, actor_id, target_type, target_id, interaction_type, value, payload, created_at, updated_at FROM interactions WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&state.database)
    .await?
    .ok_or_else(|| ApiError::NotFound("interaction not found".to_owned()))?;
    Interaction::try_from(row)
}

async fn fetch_owned(state: &AppState, id: &str, actor_id: &str) -> ApiResult<Interaction> {
    let row = sqlx::query_as::<_, InteractionRow>(
        "SELECT id, actor_id, target_type, target_id, interaction_type, value, payload, created_at, updated_at FROM interactions WHERE id = ? AND actor_id = ?",
    )
    .bind(id)
    .bind(actor_id)
    .fetch_optional(&state.database)
    .await?
    .ok_or_else(|| ApiError::NotFound("interaction not found".to_owned()))?;
    Interaction::try_from(row)
}

fn validate_create(input: &CreateInput) -> ApiResult<()> {
    if input.target_type.trim().is_empty()
        || input.target_id.trim().is_empty()
        || input.interaction_type.trim().is_empty()
    {
        return Err(ApiError::BadRequest(
            "target_type, target_id, and interaction_type are required".to_owned(),
        ));
    }
    validate_payload(&input.payload)
}

fn validate_payload(payload: &Value) -> ApiResult<()> {
    if !payload.is_object() {
        return Err(ApiError::BadRequest(
            "payload must be a JSON object".to_owned(),
        ));
    }
    Ok(())
}

fn is_unique_constraint(error: &sqlx::Error) -> bool {
    error
        .as_database_error()
        .and_then(|database_error| database_error.code())
        .is_some_and(|code| code == "2067" || code == "1555")
}

fn empty_object() -> Value {
    json!({})
}
