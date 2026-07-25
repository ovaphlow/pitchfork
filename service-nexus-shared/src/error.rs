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
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, problem, detail) = match self {
            Self::BadRequest(detail) => (StatusCode::BAD_REQUEST, "invalid-request", detail),
            Self::Unauthenticated => (
                StatusCode::UNAUTHORIZED,
                "not-authenticated",
                "not authenticated".to_owned(),
            ),
            Self::Forbidden => (
                StatusCode::FORBIDDEN,
                "not-authorized",
                "not authorized".to_owned(),
            ),
            Self::NotFound(detail) => (StatusCode::NOT_FOUND, "not-found", detail),
            Self::Conflict(detail) => (StatusCode::CONFLICT, "conflict", detail),
            Self::PayloadTooLarge => (
                StatusCode::PAYLOAD_TOO_LARGE,
                "payload-too-large",
                "payload too large".to_owned(),
            ),
            Self::Internal => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal-error",
                "internal server error".to_owned(),
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
