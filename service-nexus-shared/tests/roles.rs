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
async fn roles_directory_crud_flow() {
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

    let unauthorized = router
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("{API_PREFIX}/roles"))
                .body(Body::empty())
                .expect("build request"),
        )
        .await
        .expect("route response");
    assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);

    let created = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/roles"),
        json!({
            "role_code": "pharmacy.manager",
            "display_name": "药房管理员",
            "description": "负责药房日常管理",
            "permission_codes": ["pharmacy:manage", "pharmacy:execute", "pharmacy:manage"]
        }),
    )
    .await;
    assert_eq!(created.status(), StatusCode::CREATED);
    let created: Value = response_json(created).await;
    let pharmacy_id = created["id"].as_str().expect("role id");
    assert_eq!(created["role_code"], "pharmacy.manager");
    assert_eq!(
        created["permission_codes"],
        json!(["pharmacy:manage", "pharmacy:execute"]),
        "duplicate permission codes are deduplicated server-side"
    );

    let nursing = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/roles"),
        json!({
            "role_code": "nursing.staff",
            "display_name": "护理人员",
            "permission_codes": ["nursing:execute"]
        }),
    )
    .await;
    assert_eq!(nursing.status(), StatusCode::CREATED);

    let listed = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/roles?page=1&page_size=100"),
        json!(null),
    )
    .await;
    assert_eq!(listed.status(), StatusCode::OK);
    let listed: Value = response_json(listed).await;
    let codes = listed
        .as_array()
        .expect("list is an array")
        .iter()
        .map(|role| role["role_code"].as_str().expect("role code"))
        .collect::<Vec<_>>();
    assert_eq!(codes, vec!["nursing.staff", "pharmacy.manager"], "sorted by role_code");

    let fetched = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/roles/{pharmacy_id}"),
        json!(null),
    )
    .await;
    assert_eq!(fetched.status(), StatusCode::OK);
    let fetched: Value = response_json(fetched).await;
    assert_eq!(fetched["display_name"], "药房管理员");

    let duplicate = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/roles"),
        json!({
            "role_code": "pharmacy.manager",
            "display_name": "重复角色"
        }),
    )
    .await;
    assert_eq!(duplicate.status(), StatusCode::CONFLICT);

    let invalid_code = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/roles"),
        json!({
            "role_code": "Pharmacy.Manager",
            "display_name": "非法编码"
        }),
    )
    .await;
    assert_eq!(invalid_code.status(), StatusCode::BAD_REQUEST);

    let blank_name = request_json(
        &router,
        "POST",
        &format!("{API_PREFIX}/roles"),
        json!({
            "role_code": "nursing.manager",
            "display_name": "   "
        }),
    )
    .await;
    assert_eq!(blank_name.status(), StatusCode::BAD_REQUEST);

    let updated = request_json(
        &router,
        "PUT",
        &format!("{API_PREFIX}/roles/{pharmacy_id}"),
        json!({
            "role_code": "pharmacy.manager",
            "display_name": "药房主管",
            "description": "负责药房全面管理",
            "permission_codes": ["pharmacy:manage"]
        }),
    )
    .await;
    assert_eq!(updated.status(), StatusCode::OK);
    let updated: Value = response_json(updated).await;
    assert_eq!(updated["display_name"], "药房主管");

    let cannot_change_code = request_json(
        &router,
        "PUT",
        &format!("{API_PREFIX}/roles/{pharmacy_id}"),
        json!({
            "role_code": "pharmacy.chief",
            "display_name": "药房主管"
        }),
    )
    .await;
    assert_eq!(cannot_change_code.status(), StatusCode::BAD_REQUEST);

    let missing = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/roles/not-a-real-id"),
        json!(null),
    )
    .await;
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);

    let deleted = request_json(
        &router,
        "DELETE",
        &format!("{API_PREFIX}/roles/{pharmacy_id}"),
        json!(null),
    )
    .await;
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);

    let gone = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/roles/{pharmacy_id}"),
        json!(null),
    )
    .await;
    assert_eq!(gone.status(), StatusCode::NOT_FOUND);

    let delete_missing = request_json(
        &router,
        "DELETE",
        &format!("{API_PREFIX}/roles/{pharmacy_id}"),
        json!(null),
    )
    .await;
    assert_eq!(delete_missing.status(), StatusCode::NOT_FOUND);
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

async fn response_json(response: axum::response::Response) -> Value {
    let body = to_bytes(response.into_body(), 1024 * 1024)
        .await
        .expect("read response body");
    serde_json::from_slice(&body).expect("decode JSON response")
}