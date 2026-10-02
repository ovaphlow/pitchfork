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
async fn create_list_update_and_delete_department() {
    let router = setup().await;

    let created = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/departments"),
        json!({ "code": "nursing", "name": "护理部", "description": "临床护理" }),
    )
    .await;
    assert_eq!(created.status(), StatusCode::CREATED);
    let created: Value = response_json(created).await;
    assert_eq!(created["code"], json!("nursing"));
    assert_eq!(created["name"], json!("护理部"));
    assert_eq!(created["parent_code"], json!(""));
    assert_eq!(created["sort_order"], json!(0));
    assert_eq!(created["member_count"], json!(0));
    let department_id = created["id"].as_str().expect("department id").to_owned();

    let listed = request_json(&router, "GET", &format!("{API_PREFIX}/departments"), json!(null)).await;
    assert_eq!(listed.status(), StatusCode::OK);
    let listed: Value = response_json(listed).await;
    assert_eq!(listed["meta"]["total"], json!(1));
    assert_eq!(listed["records"].as_array().unwrap().len(), 1);
    assert_eq!(listed["records"][0]["code"], json!("nursing"));

    let updated = request_json(
        &router,
        "PUT",
        &format!("{API_PREFIX}/departments/{department_id}"),
        json!({ "code": "nursing", "name": "护理一部", "sort_order": 5 }),
    )
    .await;
    assert_eq!(updated.status(), StatusCode::OK);
    let updated: Value = response_json(updated).await;
    assert_eq!(updated["name"], json!("护理一部"));
    assert_eq!(updated["sort_order"], json!(5));

    let renamed_code = request_json(
        &router,
        "PUT",
        &format!("{API_PREFIX}/departments/{department_id}"),
        json!({ "code": "nursing-2", "name": "护理一部" }),
    )
    .await;
    assert_eq!(
        renamed_code.status(),
        StatusCode::BAD_REQUEST,
        "code is immutable after creation"
    );

    let deleted = request_json(
        &router,
        "DELETE",
        &format!("{API_PREFIX}/departments/{department_id}"),
        json!(null),
    )
    .await;
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);

    let missing = request_json(
        &router,
        "DELETE",
        &format!("{API_PREFIX}/departments/{department_id}"),
        json!(null),
    )
    .await;
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn duplicate_code_and_unknown_parent_are_rejected() {
    let router = setup().await;
    create_department(&router, "nursing", "护理部").await;

    let duplicate = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/departments"),
        json!({ "code": "nursing", "name": "护理二部" }),
    )
    .await;
    assert_eq!(duplicate.status(), StatusCode::CONFLICT);

    let unknown_parent = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/departments"),
        json!({ "code": "ward-a", "name": "病区 A", "parent_code": "ghost" }),
    )
    .await;
    assert_eq!(unknown_parent.status(), StatusCode::BAD_REQUEST);

    let blank_name = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/departments"),
        json!({ "code": "ward-b", "name": "   " }),
    )
    .await;
    assert_eq!(blank_name.status(), StatusCode::BAD_REQUEST);

    // 校验失败的写入不得留下任何部门。
    let listed = request_json(&router, "GET", &format!("{API_PREFIX}/departments"), json!(null)).await;
    let listed: Value = response_json(listed).await;
    assert_eq!(listed["meta"]["total"], json!(1));
}

pub async fn setup() -> Router {
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
    app(
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
    )
}

pub async fn create_department(router: &Router, code: &str, name: &str) -> String {
    let response = request_json(
        router,
        "POST",
        &format!("{API_PREFIX}/departments"),
        json!({ "code": code, "name": name }),
    )
    .await;
    assert_eq!(response.status(), StatusCode::CREATED);
    response_json(response).await["id"]
        .as_str()
        .expect("department id")
        .to_owned()
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
    format!("http://{address}")
}

pub async fn request_json(
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

pub async fn response_json(response: axum::response::Response) -> Value {
    let body = to_bytes(response.into_body(), 1024 * 1024)
        .await
        .expect("read response body");
    serde_json::from_slice(&body).expect("decode JSON response")
}
