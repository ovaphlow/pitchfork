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
async fn assign_replace_and_list_subject_roles() {
    let (router, subject_id) = setup().await;
    create_role(&router, "nursing.staff", "护理人员").await;
    create_role(&router, "pharmacy.manager", "药房管理员").await;

    let assigned = put_roles(
        &router,
        &subject_id,
        json!(["nursing.staff", "nursing.staff", "pharmacy.manager"]),
    )
    .await;
    assert_eq!(assigned.status(), StatusCode::OK);
    let assigned: Value = response_json(assigned).await;
    let assignments = assigned.as_array().expect("assignments array");
    assert_eq!(
        assignments.len(),
        2,
        "duplicate role codes are deduplicated"
    );
    assert_eq!(
        assignments
            .iter()
            .map(|assignment| assignment["role_code"].as_str().unwrap())
            .collect::<Vec<_>>(),
        vec!["nursing.staff", "pharmacy.manager"],
        "assignments are sorted by role_code"
    );
    assert_eq!(assignments[0]["subject_id"], json!(subject_id));
    assert_eq!(assignments[0]["granted_by_subject_id"], json!("subject-1"));
    assert_eq!(assignments[0]["role_display_name"], json!("护理人员"));

    let listed = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-roles?subject_ids={subject_id}"),
        json!(null),
    )
    .await;
    assert_eq!(listed.status(), StatusCode::OK);
    assert_eq!(response_json(listed).await.as_array().unwrap().len(), 2);

    let other_subject = Ulid::new().to_string();
    let unrelated = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-roles?subject_ids={other_subject}"),
        json!(null),
    )
    .await;
    assert_eq!(unrelated.status(), StatusCode::OK);
    assert!(
        response_json(unrelated)
            .await
            .as_array()
            .unwrap()
            .is_empty()
    );

    let narrowed = put_roles(&router, &subject_id, json!(["nursing.staff"])).await;
    assert_eq!(narrowed.status(), StatusCode::OK);
    let narrowed: Value = response_json(narrowed).await;
    assert_eq!(narrowed.as_array().unwrap().len(), 1);

    let cleared = put_roles(&router, &subject_id, json!([])).await;
    assert_eq!(cleared.status(), StatusCode::OK);
    assert!(response_json(cleared).await.as_array().unwrap().is_empty());

    let after_clear = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-roles?subject_ids={subject_id}"),
        json!(null),
    )
    .await;
    assert!(
        response_json(after_clear)
            .await
            .as_array()
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn invalid_inputs_are_rejected() {
    let (router, subject_id) = setup().await;
    create_role(&router, "nursing.staff", "护理人员").await;

    let unknown_role =
        put_roles(&router, &subject_id, json!(["nursing.staff", "ghost.role"])).await;
    assert_eq!(unknown_role.status(), StatusCode::BAD_REQUEST);

    let bad_subject = put_roles(&router, "subject-1", json!(["nursing.staff"])).await;
    assert_eq!(bad_subject.status(), StatusCode::BAD_REQUEST);

    let bad_filter = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-roles?subject_ids=not-a-ulid"),
        json!(null),
    )
    .await;
    assert_eq!(bad_filter.status(), StatusCode::BAD_REQUEST);

    let missing_codes = request_json(
        &router,
        "PUT",
        &format!("{API_PREFIX}/subject-roles/subjects/{subject_id}"),
        json!({}),
    )
    .await;
    assert!(
        missing_codes.status().is_client_error(),
        "a body without role_codes must not silently clear assignments"
    );

    // 校验失败的写入不得改动既有分配。
    let surviving = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-roles?subject_ids={subject_id}"),
        json!(null),
    )
    .await;
    assert!(
        response_json(surviving)
            .await
            .as_array()
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn assigned_role_cannot_be_deleted() {
    let (router, subject_id) = setup().await;
    let role_id = create_role(&router, "nursing.staff", "护理人员").await;
    put_roles(&router, &subject_id, json!(["nursing.staff"])).await;

    let blocked = request_json(
        &router,
        "DELETE",
        &format!("{API_PREFIX}/roles/{role_id}"),
        json!(null),
    )
    .await;
    assert_eq!(blocked.status(), StatusCode::CONFLICT);

    let still_there = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/roles/{role_id}"),
        json!(null),
    )
    .await;
    assert_eq!(still_there.status(), StatusCode::OK);

    put_roles(&router, &subject_id, json!([])).await;
    let deleted = request_json(
        &router,
        "DELETE",
        &format!("{API_PREFIX}/roles/{role_id}"),
        json!(null),
    )
    .await;
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn subject_permissions_union_role_permission_codes() {
    let (router, subject_id) = setup().await;
    create_role_with_permissions(
        &router,
        "nursing.staff",
        "护理人员",
        json!(["nursing:execute", "nursing:read"]),
    )
    .await;
    create_role_with_permissions(
        &router,
        "pharmacy.manager",
        "药房管理员",
        json!(["pharmacy:manage", "nursing:read"]),
    )
    .await;
    let assigned = put_roles(
        &router,
        &subject_id,
        json!(["nursing.staff", "pharmacy.manager"]),
    )
    .await;
    assert_eq!(assigned.status(), StatusCode::OK);

    let response = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-permissions?subject_id={subject_id}"),
        json!(null),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let payload: Value = response_json(response).await;
    assert_eq!(payload["subject_id"], json!(subject_id));
    assert_eq!(
        payload["role_codes"],
        json!(["nursing.staff", "pharmacy.manager"])
    );
    assert_eq!(
        payload["permission_codes"],
        json!(["nursing:execute", "nursing:read", "pharmacy:manage"]),
        "permission codes are a deduplicated union in role order"
    );
    assert_eq!(payload["source"], json!("nexus"));

    let subject_without_roles = Ulid::new().to_string();
    let empty = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-permissions?subject_id={subject_without_roles}"),
        json!(null),
    )
    .await;
    assert_eq!(empty.status(), StatusCode::OK);
    let empty: Value = response_json(empty).await;
    assert_eq!(empty["role_codes"], json!([]));
    assert_eq!(empty["permission_codes"], json!([]));

    let invalid = request_json(
        &router,
        "GET",
        &format!("{API_PREFIX}/subject-permissions?subject_id=not-a-ulid"),
        json!(null),
    )
    .await;
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
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

async fn create_role(router: &Router, role_code: &str, display_name: &str) -> String {
    create_role_with_permissions(router, role_code, display_name, json!([])).await
}

async fn create_role_with_permissions(
    router: &Router,
    role_code: &str,
    display_name: &str,
    permission_codes: Value,
) -> String {
    let response = request_json(
        router,
        "POST",
        &format!("{API_PREFIX}/roles"),
        json!({
            "role_code": role_code,
            "display_name": display_name,
            "permission_codes": permission_codes,
        }),
    )
    .await;
    assert_eq!(response.status(), StatusCode::CREATED);
    response_json(response).await["id"]
        .as_str()
        .expect("role id")
        .to_owned()
}

async fn put_roles(
    router: &Router,
    subject_id: &str,
    role_codes: Value,
) -> axum::response::Response {
    request_json(
        router,
        "PUT",
        &format!("{API_PREFIX}/subject-roles/subjects/{subject_id}"),
        json!({"role_codes": role_codes}),
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
