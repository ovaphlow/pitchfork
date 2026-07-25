use std::path::PathBuf;
use std::sync::Arc;

use axum::body::Body;
use http::{Request, StatusCode};
use nexus_shared::auth::IdentityClient;
use nexus_shared::{API_PREFIX, AppState, app};
use sqlx::sqlite::SqlitePoolOptions;
use tower::ServiceExt;

#[tokio::test]
async fn every_shared_resource_requires_an_identity_cookie() {
    let database = SqlitePoolOptions::new()
        .connect_lazy("sqlite::memory:")
        .expect("create lazy SQLite pool");
    let state = AppState {
        database,
        files_dir: Arc::new(PathBuf::from("/tmp/nexus-test-files")),
        identity_client: IdentityClient::new("http://127.0.0.1:9", reqwest::Client::new()),
    };
    let router = app(state, 1024);

    for resource in ["settings", "messages", "files", "interactions"] {
        let response = router
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("{API_PREFIX}/{resource}"))
                    .body(Body::empty())
                    .expect("build request"),
            )
            .await
            .expect("route response");
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "{resource}");
    }
}
