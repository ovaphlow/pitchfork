use axum::Json;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::Serialize;

use crate::API_PREFIX;

pub type ApiResult<T> = Result<T, ApiError>;

#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error("{0}")]
    BadRequest(String),
    #[error("not authenticated")]
    Unauthenticated,
    #[error("not authorized")]
    Forbidden,
    /// 043：权限门禁专用 403 —— 带出**缺哪个权限码**，前端才能说清"该找谁要哪权限"。
    #[error("permission required: {0}")]
    ForbiddenPermission(String),
    #[error("{0}")]
    NotFound(String),
    #[error("{0}")]
    Conflict(String),
    #[error("payload too large")]
    PayloadTooLarge,
    #[error("internal server error")]
    Internal,
}

#[derive(Serialize)]
struct ProblemDetails<'a> {
    #[serde(rename = "type")]
    problem_type: String,
    title: &'a str,
    status: u16,
    detail: String,
    /// 043：仅权限门禁的 403 才带（其余响应形状不变，`skip_serializing_if` 保证不出现空字段）。
    ///
    /// 与 Aceso 侧门禁的 `required_permission` **字段同名**，
    /// 因此前端 `problem-message.ts` 的 `ApiProblem`（两者的超集）无需改动就能消费。
    #[serde(skip_serializing_if = "Option::is_none")]
    required_permission: Option<String>,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, problem, detail, required_permission) = match self {
            Self::BadRequest(detail) => (StatusCode::BAD_REQUEST, "invalid-request", detail, None),
            Self::Unauthenticated => (
                StatusCode::UNAUTHORIZED,
                "not-authenticated",
                "not authenticated".to_owned(),
                None,
            ),
            Self::Forbidden => (
                StatusCode::FORBIDDEN,
                "not-authorized",
                "not authorized".to_owned(),
                None,
            ),
            Self::ForbiddenPermission(permission) => (
                StatusCode::FORBIDDEN,
                "permission-required",
                format!("permission required: {permission}"),
                Some(permission),
            ),
            Self::NotFound(detail) => (StatusCode::NOT_FOUND, "not-found", detail, None),
            Self::Conflict(detail) => (StatusCode::CONFLICT, "conflict", detail, None),
            Self::PayloadTooLarge => (
                StatusCode::PAYLOAD_TOO_LARGE,
                "payload-too-large",
                "payload too large".to_owned(),
                None,
            ),
            Self::Internal => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal-error",
                "internal server error".to_owned(),
                None,
            ),
        };
        (
            status,
            [("content-type", "application/problem+json")],
            Json(ProblemDetails {
                problem_type: format!("{API_PREFIX}/problems/{problem}"),
                title: status.canonical_reason().unwrap_or("Error"),
                status: status.as_u16(),
                detail,
                required_permission,
            }),
        )
            .into_response()
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(error: sqlx::Error) -> Self {
        tracing::error!(error = %error, "database operation failed");
        Self::Internal
    }
}

impl From<serde_json::Error> for ApiError {
    fn from(error: serde_json::Error) -> Self {
        tracing::error!(error = %error, "stored JSON could not be decoded");
        Self::Internal
    }
}
