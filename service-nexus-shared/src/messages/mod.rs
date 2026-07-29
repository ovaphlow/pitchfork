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
        .route("/{id}/status", axum::routing::patch(update_status))
}

#[derive(Deserialize)]
struct ListQuery {
    page: Option<i64>,
    page_size: Option<i64>,
    message_type: Option<String>,
    sender_id: Option<String>,
    receiver_id: Option<String>,
    status: Option<String>,
}

#[derive(Deserialize)]
struct CreateInput {
    message_type: String,
    sender_type: String,
    receiver_id: String,
    receiver_type: String,
    #[serde(default = "empty_object")]
    payload: Value,
}

#[derive(Deserialize)]
struct UpdateInput {
    status: Option<String>,
    payload: Option<Value>,
}

#[derive(Deserialize)]
struct StatusInput {
    status: String,
}

#[derive(Serialize)]
struct Message {
    id: String,
    message_type: String,
    sender_id: String,
    sender_type: String,
    receiver_id: String,
    receiver_type: String,
    status: String,
    payload: Value,
    created_at: String,
    updated_at: String,
}

#[derive(FromRow)]
struct MessageRow {
    id: String,
    message_type: String,
    sender_id: String,
    sender_type: String,
    receiver_id: String,
    receiver_type: String,
    status: String,
    payload: String,
    created_at: String,
    updated_at: String,
}

impl TryFrom<MessageRow> for Message {
    type Error = ApiError;

    fn try_from(row: MessageRow) -> Result<Self, Self::Error> {
        Ok(Self {
            id: row.id,
            message_type: row.message_type,
            sender_id: row.sender_id,
            sender_type: row.sender_type,
            receiver_id: row.receiver_id,
            receiver_type: row.receiver_type,
            status: row.status,
            payload: serde_json::from_str(&row.payload)?,
            created_at: row.created_at,
            updated_at: row.updated_at,
        })
    }
}

async fn list(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> ApiResult<Json<Vec<Message>>> {
    let (limit, offset) = PageQuery { page: query.page, page_size: query.page_size }.limit_offset()?;
    let mut builder: QueryBuilder<Sqlite> = QueryBuilder::new(
        "SELECT id, message_type, sender_id, sender_type, receiver_id, receiver_type, status, payload, created_at, updated_at FROM messages WHERE 1 = 1",
    );
    if let Some(value) = query.message_type.filter(|value| !value.is_empty()) {
        builder.push(" AND message_type = ").push_bind(value);
    }
    if let Some(value) = query.sender_id.filter(|value| !value.is_empty()) {
        builder.push(" AND sender_id = ").push_bind(value);
    }
    if let Some(value) = query.receiver_id.filter(|value| !value.is_empty()) {
        builder.push(" AND receiver_id = ").push_bind(value);
    }
    if let Some(value) = query.status.filter(|value| !value.is_empty()) {
        builder.push(" AND status = ").push_bind(value);
    }
    builder
        .push(" ORDER BY created_at DESC LIMIT ")
        .push_bind(limit)
        .push(" OFFSET ")
        .push_bind(offset);
    builder
        .build_query_as::<MessageRow>()
        .fetch_all(&state.database)
        .await?
        .into_iter()
        .map(Message::try_from)
        .collect::<ApiResult<Vec<_>>>()
        .map(Json)
}

async fn create(
    State(state): State<AppState>,
    Extension(identity): Extension<Identity>,
    Json(input): Json<CreateInput>,
) -> ApiResult<(axum::http::StatusCode, Json<Message>)> {
    validate_create(&input)?;
    let id = Ulid::new().to_string();
    let payload = serde_json::to_string(&input.payload)
        .map_err(|_| ApiError::BadRequest("payload must be JSON".to_owned()))?;
    sqlx::query(
        "INSERT INTO messages (id, message_type, sender_id, sender_type, receiver_id, receiver_type, payload) VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(&input.message_type)
    .bind(&identity.subject_id)
    .bind(&input.sender_type)
    .bind(&input.receiver_id)
    .bind(&input.receiver_type)
    .bind(payload)
    .execute(&state.database)
    .await?;
    Ok((
        axum::http::StatusCode::CREATED,
        Json(fetch(&state, &id).await?),
    ))
}

async fn get_one(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<Json<Message>> {
    Ok(Json(fetch(&state, &id).await?))
}

async fn update(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(input): Json<UpdateInput>,
) -> ApiResult<Json<Message>> {
    if input.status.is_none() && input.payload.is_none() {
        return Err(ApiError::BadRequest(
            "status or payload is required".to_owned(),
        ));
    }
    if let Some(payload) = &input.payload {
        validate_payload(payload)?;
    }
    let existing = fetch(&state, &id).await?;
    let status = input.status.unwrap_or(existing.status);
    if status.trim().is_empty() {
        return Err(ApiError::BadRequest("status cannot be empty".to_owned()));
    }
    let payload = input.payload.unwrap_or(existing.payload);
    sqlx::query(
        "UPDATE messages SET status = ?, payload = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?",
    )
    .bind(status)
    .bind(serde_json::to_string(&payload)?)
    .bind(&id)
    .execute(&state.database)
    .await?;
    Ok(Json(fetch(&state, &id).await?))
}

async fn update_status(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(input): Json<StatusInput>,
) -> ApiResult<Json<Message>> {
    if input.status.trim().is_empty() {
        return Err(ApiError::BadRequest("status is required".to_owned()));
    }
    let result = sqlx::query(
        "UPDATE messages SET status = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?",
    )
    .bind(input.status)
    .bind(&id)
    .execute(&state.database)
    .await?;
    if result.rows_affected() == 0 {
        return Err(ApiError::NotFound("message not found".to_owned()));
    }
    Ok(Json(fetch(&state, &id).await?))
}

async fn delete(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> ApiResult<axum::http::StatusCode> {
    let result = sqlx::query("DELETE FROM messages WHERE id = ?")
        .bind(id)
        .execute(&state.database)
        .await?;
    if result.rows_affected() == 0 {
        return Err(ApiError::NotFound("message not found".to_owned()));
    }
    Ok(axum::http::StatusCode::NO_CONTENT)
}

async fn fetch(state: &AppState, id: &str) -> ApiResult<Message> {
    let row = sqlx::query_as::<_, MessageRow>(
        "SELECT id, message_type, sender_id, sender_type, receiver_id, receiver_type, status, payload, created_at, updated_at FROM messages WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&state.database)
    .await?
    .ok_or_else(|| ApiError::NotFound("message not found".to_owned()))?;
    Message::try_from(row)
}

fn validate_create(input: &CreateInput) -> ApiResult<()> {
    if input.message_type.trim().is_empty()
        || input.sender_type.trim().is_empty()
        || input.receiver_id.trim().is_empty()
        || input.receiver_type.trim().is_empty()
    {
        return Err(ApiError::BadRequest(
            "message_type, sender_type, receiver_id, and receiver_type are required".to_owned(),
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

fn empty_object() -> Value {
    json!({})
}
