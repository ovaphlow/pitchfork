pub mod auth;
pub mod authorization;
pub mod config;
pub mod departments;
pub mod error;
pub mod files;
pub mod interactions;
pub mod messages;
pub mod roles;
pub mod settings;
pub mod subject_departments;
pub mod subject_roles;

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
    // 043：写门禁的中间件实例（与只读路由分开挂：axum 的 route_layer 粒度是路径而非方法）。
    //
    // 只读目录（角色/部门/用户↔角色/用户↔部门）继续只要求有效会话：它们是产品元数据，
    // 用户页、角色页、申领科室下拉都依赖它渲染；收紧读会制造新的 403 面（043 D7）。
    let access_manage = middleware::from_fn_with_state(
        state.clone(),
        authorization::require_access_manage,
    );
    let base_data_manage = middleware::from_fn_with_state(
        state.clone(),
        authorization::require_base_data_manage,
    );

    let protected = Router::new()
        .nest("/settings", settings::router())
        .nest("/messages", messages::router())
        .nest("/files", files::router())
        .nest("/interactions", interactions::router())
        .nest(
            "/roles",
            roles::router().merge(roles::write_router().route_layer(access_manage.clone())),
        )
        .nest(
            "/subject-roles",
            subject_roles::router()
                .merge(subject_roles::write_router().route_layer(access_manage.clone())),
        )
        .nest(
            "/departments",
            departments::router().merge(departments::write_router().route_layer(base_data_manage)),
        )
        .nest(
            "/subject-departments",
            subject_departments::router()
                .merge(subject_departments::write_router().route_layer(access_manage)),
        )
        .nest("/subject-permissions", subject_roles::permissions_router())
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
