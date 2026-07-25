use std::path::PathBuf;
use std::sync::Arc;

use axum::body::{Body, to_bytes};
use axum::http::header::{CONTENT_TYPE, COOKIE};
use axum::http::{Request, StatusCode};
use axum::routing::get;
use axum::{Json, Router};
use nexus_shared::auth::IdentityClient;
use nexus_shared::{API_PREFIX, AppState, app};
use serde_json::{Value, json};
use sqlx::sqlite::SqlitePoolOptions;
use tower::ServiceExt;

#[tokio::test]
async fn idp_identity_is_used_for_messages_and_interactions() {
    let identity_url = start_identity_service().await;
    let database = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .expect("open test SQLite database");
    sqlx::migrate!("./migrations")
        .run(&database)
        .await
        .expect("run migrations");
    let router = app(
        AppState {
            database,
            files_dir: Arc::new(PathBuf::from("/tmp/nexus-test-files")),
            identity_client: IdentityClient::new(
                &identity_url,
                reqwest::Client::builder()
                    .no_proxy()
                    .build()
                    .expect("create direct IDP client"),
            ),
        },
        1024 * 1024,
    );

    let setting = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/settings"),
        json!({"category": "department", "code": "care", "payload": {"name": "Care"}}),
    )
    .await;
    assert_eq!(setting.status(), StatusCode::CREATED);

    let message = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/messages"),
        json!({
            "message_type": "system_notification",
            "sender_type": "user",
            "receiver_id": "resident-1",
            "receiver_type": "resident",
            "payload": {"title": "Reminder"}
        }),
    )
    .await;
    assert_eq!(message.status(), StatusCode::CREATED);
    let message: Value = response_json(message).await;
    assert_eq!(message["sender_id"], "subject-1");

    let interaction_body = json!({
        "target_type": "care_plan",
        "target_id": "plan-1",
        "interaction_type": "favorite",
        "payload": {}
    });
    let created = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/interactions"),
        interaction_body.clone(),
    )
    .await;
    assert_eq!(created.status(), StatusCode::CREATED);
    let created: Value = response_json(created).await;
    assert_eq!(created["actor_id"], "subject-1");

    let duplicate = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/interactions"),
        interaction_body,
    )
    .await;
    assert_eq!(duplicate.status(), StatusCode::CONFLICT);
}

#[tokio::test]
async fn deleting_a_file_removes_local_content_and_metadata() {
    let identity_url = start_identity_service().await;
    let database = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .expect("open test SQLite database");
    sqlx::migrate!("./migrations")
        .run(&database)
        .await
        .expect("run migrations");
    let files_dir = std::env::temp_dir().join(format!("nexus-files-test-{}", ulid::Ulid::new()));
    tokio::fs::create_dir_all(&files_dir)
        .await
        .expect("create test file directory");
    let router = app(
        AppState {
            database: database.clone(),
            files_dir: Arc::new(files_dir.clone()),
            identity_client: IdentityClient::new(
                &identity_url,
                reqwest::Client::builder()
                    .no_proxy()
                    .build()
                    .expect("create direct IDP client"),
            ),
        },
        1024 * 1024,
    );

    let uploaded = request_file(&router, &format!("{API_PREFIX}/files/upload")).await;
    assert_eq!(uploaded.status(), StatusCode::CREATED);
    let uploaded: Value = response_json(uploaded).await;
    let id = uploaded["id"].as_str().expect("file id");
    let stored_name = uploaded["stored_name"].as_str().expect("stored file name");
    let stored_path = files_dir.join(stored_name);
    assert_eq!(
        tokio::fs::read(&stored_path)
            .await
            .expect("read uploaded file"),
        b"care plan"
    );

    let deleted = request_json(
        &router,
        "DELETE",
        &format!("{API_PREFIX}/files/{id}"),
        json!(null),
    )
    .await;
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    assert!(
        !stored_path.exists(),
        "stored file should be physically deleted"
    );
    let remaining: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM files WHERE id = ?")
        .bind(id)
        .fetch_one(&database)
        .await
        .expect("count file metadata");
    assert_eq!(remaining, 0, "file metadata should be physically deleted");
    tokio::fs::remove_dir_all(&files_dir)
        .await
        .expect("remove test file directory");
}

async fn start_identity_service() -> String {
    let identity_router = Router::new().route(
        "/crate-api/identity/v1/session",
        get(|| async { Json(json!({"subject_id": "subject-1", "access": "完整"})) }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind test identity service");
    let address = listener.local_addr().expect("identity service address");
    tokio::spawn(async move {
        axum::serve(listener, identity_router)
            .await
            .expect("serve test identity service");
    });
    let base_url = format!("http://{address}");
    let response = reqwest::Client::builder()
        .no_proxy()
        .build()
        .expect("create direct IDP probe client")
        .get(format!("{base_url}/crate-api/identity/v1/session"))
        .send()
        .await
        .expect("probe test identity service");
    assert_eq!(response.status(), StatusCode::OK);
    base_url
}

async fn request_json(
    router: &Router,
    method: &str,
    uri: &str,
    body: Value,
) -> axum::response::Response {
    router
        .clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(uri)
                .header(CONTENT_TYPE, "application/json")
                .header(COOKIE, "identityd_session=test-session")
                .body(Body::from(body.to_string()))
                .expect("build JSON request"),
        )
        .await
        .expect("route response")
}

async fn request_file(router: &Router, uri: &str) -> axum::response::Response {
    const BOUNDARY: &str = "nexus-test-boundary";
    let mut body = format!(
        "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"care-plan.txt\"\r\nContent-Type: text/plain\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(b"care plan");
    body.extend_from_slice(format!("\r\n--{BOUNDARY}--\r\n").as_bytes());
    router
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(uri)
                .header(
                    CONTENT_TYPE,
                    format!("multipart/form-data; boundary={BOUNDARY}"),
                )
                .header(COOKIE, "identityd_session=test-session")
                .body(Body::from(body))
                .expect("build multipart request"),
        )
        .await
        .expect("route response")
}

async fn response_json(response: axum::response::Response) -> Value {
    let body = to_bytes(response.into_body(), 1024 * 1024)
        .await
        .expect("read response body");
    serde_json::from_slice(&body).expect("decode JSON response")
}
