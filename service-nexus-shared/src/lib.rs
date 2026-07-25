pub mod auth;
pub mod config;
pub mod error;
pub mod files;
pub mod interactions;
pub mod messages;
pub mod settings;

use std::path::PathBuf;
use std::sync::Arc;

use axum::extract::DefaultBodyLimit;
use axum::middleware;
use axum::routing::get;
use axum::{Json, Router};
use serde_json::json;
use sqlx::SqlitePool;
use tower_http::trace::TraceLayer;

use crate::auth::IdentityClient;
use crate::error::{ApiError, ApiResult};

pub const API_PREFIX: &str = "/crate-api/shared/v1";

#[derive(Clone)]
pub struct AppState {
    pub database: SqlitePool,
    pub files_dir: Arc<PathBuf>,
    pub identity_client: IdentityClient,
}

#[derive(serde::Deserialize)]
pub struct PageQuery {
    pub page: Option<i64>,
    pub page_size: Option<i64>,
}

impl PageQuery {
    pub fn limit_offset(&self) -> ApiResult<(i64, i64)> {
        let page = self.page.unwrap_or(1);
        let page_size = self.page_size.unwrap_or(20);
        if page < 1 || !(1..=100).contains(&page_size) {
            return Err(ApiError::BadRequest(
                "page must be positive and page_size must be between 1 and 100".to_owned(),
            ));
        }
        Ok((page_size, (page - 1) * page_size))
    }
}

pub fn app(state: AppState, max_upload_bytes: usize) -> Router {
    let protected = Router::new()
        .nest("/settings", settings::router())
        .nest("/messages", messages::router())
        .nest("/files", files::router())
        .nest("/interactions", interactions::router())
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            auth::require_identity,
        ));

    Router::new()
        .route("/healthz", get(health))
        .nest(API_PREFIX, protected)
        .layer(DefaultBodyLimit::max(max_upload_bytes))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

async fn health() -> Json<serde_json::Value> {
    Json(json!({"status": "ok", "service": "nexus-shared"}))
}
