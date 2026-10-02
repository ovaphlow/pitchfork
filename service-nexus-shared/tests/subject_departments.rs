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
use ulid::Ulid;

#[tokio::test]
async fn set_read_and_clear_subject_department() {
    let (router, subject_id) = setup().await;
    let department_id = create_department(&router, "nursing", "护理部").await;

    let assigned = put_department(&router, &subject_id, json!(department_id)).await;
    assert_eq!(assigned.status(), StatusCode::OK);
    let assigned: Value = response_json(assigned).await;
    let records = assigned.as_array().expect("assignment array");
    assert_eq!(records.len(), 1);
    assert_eq!(records[0]["subject_id"], json!(subject_id));
    assert_eq!(records[0]["department_id"], json!(department_id));
    assert_eq!(records[0]["department_code"], json!("nursing"));
    assert_eq!(records[0]["department_name"], json!("护理部"));
    assert_eq!(records[0]["granted_by_subject_id"], json!("subject-1"));

    let listed = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-departments?subject_ids={subject_id}"),
        json!(null),
    )
    .await;
    assert_eq!(listed.status(), StatusCode::OK);
    let listed: Value = response_json(listed).await;
    assert_eq!(listed["meta"]["total"], json!(1));
    assert_eq!(listed["records"][0]["department_code"], json!("nursing"));

    // 部门页的成员数来自同一张关系表。
    let departments = request_json(&router, "GET", &format!("{API_PREFIX}/departments"), json!(null)).await;
    let departments: Value = response_json(departments).await;
    assert_eq!(departments["records"][0]["member_count"], json!(1));

    let other_subject = Ulid::new().to_string();
    let unrelated = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-departments?subject_ids={other_subject}"),
        json!(null),
    )
    .await;
    let unrelated: Value = response_json(unrelated).await;
    assert_eq!(unrelated["meta"]["total"], json!(0));
    assert!(unrelated["records"].as_array().unwrap().is_empty());

    let cleared = put_department(&router, &subject_id, json!(null)).await;
    assert_eq!(cleared.status(), StatusCode::OK);
    assert!(response_json(cleared).await.as_array().unwrap().is_empty());
}

#[tokio::test]
async fn unknown_department_and_invalid_subject_are_rejected() {
    let (router, subject_id) = setup().await;

    let unknown = put_department(&router, &subject_id, json!(Ulid::new().to_string())).await;
    assert_eq!(unknown.status(), StatusCode::BAD_REQUEST);

    let bad_subject = put_department(&router, "subject-1", json!(null)).await;
    assert_eq!(bad_subject.status(), StatusCode::BAD_REQUEST);

    let bad_filter = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-departments?subject_ids=not-a-ulid"),
        json!(null),
    )
    .await;
    assert_eq!(bad_filter.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn department_with_members_cannot_be_deleted() {
    let (router, subject_id) = setup().await;
    let department_id = create_department(&router, "nursing", "护理部").await;
    put_department(&router, &subject_id, json!(department_id)).await;

    let blocked = request_json(
        &router,
        "DELETE",
        &format!("{API_PREFIX}/departments/{department_id}"),
        json!(null),
    )
    .await;
    assert_eq!(blocked.status(), StatusCode::CONFLICT);

    put_department(&router, &subject_id, json!(null)).await;
    let deleted = request_json(
        &router,
        "DELETE",
        &format!("{API_PREFIX}/departments/{department_id}"),
        json!(null),
    )
    .await;
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
}

async fn setup() -> (Router, String) {
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
    (router, Ulid::new().to_string())
}

async fn create_department(router: &Router, code: &str, name: &str) -> String {
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

async fn put_department(router: &Router, subject_id: &str, department_id: Value) -> axum::response::Response {
    request_json(
        router,
        "PUT",
        &format!("{API_PREFIX}/subject-departments/subjects/{subject_id}"),
        json!({ "department_id": department_id }),
    )
    .await
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

async fn response_json(response: axum::response::Response) -> Value {
    let body = to_bytes(response.into_body(), 1024 * 1024)
        .await
        .expect("read response body");
    serde_json::from_slice(&body).expect("decode JSON response")
}
