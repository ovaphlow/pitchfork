//! 043 授权门禁矩阵（Nexus 侧权威判定）。
//!
//! 这里锁的是**授权边界**本身，不是 CRUD：
//!
//! 1. **提权链被堵**：业务账号既不能建带任意权限码的角色，也不能把角色分给自己；
//! 2. **基础数据写被堵**：部门目录的写操作需要 `base_data:manage`；
//! 3. **引导者路径成立**：平台管理员在**没有任何 `access:manage` 持有者**时仍可管理
//!    角色与部门——因此不需要 040 §9.1 那种放量协议；
//! 4. **委派成立且互不越界**：拿到 `access:manage` 的主体能管角色/归属，但仍改不了部门目录；
//! 5. **读路由保持开放**（043 D7）：角色/部门目录是产品元数据，业务页面依赖它渲染；
//! 6. **越权读收紧**：`/subject-permissions` 只能查自己（原先任何登录用户可读任意主体）。
//!
//! 桩身份服务按**会话 cookie** 返回不同主体，一个服务覆盖全部角色：
//!   admin-session   → 平台管理员
//!   manager-session → 普通主体，但被授予了 `access:manage`
//!   staff-session   → 普通主体（只有业务角色）

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

const ADMIN_COOKIE: &str = "identityd_session=admin-session";
const MANAGER_COOKIE: &str = "identityd_session=manager-session";
const STAFF_COOKIE: &str = "identityd_session=staff-session";

// subject_id 必须是 26 位 ULID（`validate_subject_id` 会拒绝其它形状）
const ADMIN_ID: &str = "01KYE3F6CPNJS16RHT01M64D8B";
const MANAGER_ID: &str = "01M3ZR3CENV2DGYY2RS1BPJG2D";
const STAFF_ID: &str = "01M3ZR3CDAQ6ZKHCYD065RSYMT";

const ACCESS_MANAGE: &str = "access:manage";
const BASE_DATA_MANAGE: &str = "base_data:manage";

struct TestApp {
    router: Router,
}

async fn test_app() -> TestApp {
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
    TestApp {
        router: app(
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
        ),
    }
}

// ============================================================================
// 1 + 2 + 5. 业务账号：提权链与基础数据写被堵，读路由保持开放
// ============================================================================

#[tokio::test]
async fn business_accounts_cannot_escalate_or_write_base_data() {
    let TestApp { router } = test_app().await;

    // 先以平台管理员建两个角色，作为"提权目标"与"业务角色"
    let nursing = call(&router, "POST", "/roles", ADMIN_COOKIE, json!({
        "role_code": "nursing.staff",
        "display_name": "护理人员",
        "permission_codes": ["nursing:execute"],
    })).await;
    assert_eq!(nursing.status(), StatusCode::CREATED, "引导者必须能建角色");

    // —— 提权链第一半：建带任意权限码的角色 ——
    let escalate_role = call(&router, "POST", "/roles", STAFF_COOKIE, json!({
        "role_code": "self.escalate",
        "display_name": "自授角色",
        "permission_codes": ["nursing:execute"],
    })).await;
    assert_forbidden(escalate_role, ACCESS_MANAGE).await;

    // —— 提权链第二半：把角色分给自己 ——
    let escalate_assign = call(
        &router,
        "PUT",
        &format!("/subject-roles/subjects/{STAFF_ID}"),
        STAFF_COOKIE,
        json!({"role_codes": ["nursing.staff"]}),
    )
    .await;
    assert_forbidden(escalate_assign, ACCESS_MANAGE).await;

    // 归属也必须未被改动（回读断言，而不是只看状态码）
    let own_permissions = call(
        &router,
        "GET",
        &format!("/subject-permissions?subject_id={STAFF_ID}"),
        STAFF_COOKIE,
        json!(null),
    )
    .await;
    assert_eq!(own_permissions.status(), StatusCode::OK);
    let own_permissions: Value = response_json(own_permissions).await;
    assert_eq!(own_permissions["role_codes"], json!([]), "403 不得留下任何副作用");

    // —— 基础数据写：部门目录 ——
    let create_department = call(&router, "POST", "/departments", STAFF_COOKIE, json!({
        "code": "qa-authz",
        "name": "越权部门",
    })).await;
    assert_forbidden(create_department, BASE_DATA_MANAGE).await;

    let department = call(&router, "POST", "/departments", ADMIN_COOKIE, json!({
        "code": "nursing",
        "name": "护理部",
    })).await;
    assert_eq!(department.status(), StatusCode::CREATED);
    let department: Value = response_json(department).await;
    let department_id = department["id"].as_str().expect("department id").to_owned();

    let update_department = call(
        &router,
        "PUT",
        &format!("/departments/{department_id}"),
        STAFF_COOKIE,
        json!({"code": "nursing", "name": "改名越权"}),
    )
    .await;
    assert_forbidden(update_department, BASE_DATA_MANAGE).await;

    let delete_department = call(
        &router,
        "DELETE",
        &format!("/departments/{department_id}"),
        STAFF_COOKIE,
        json!(null),
    )
    .await;
    assert_forbidden(delete_department, BASE_DATA_MANAGE).await;

    // —— 越权读收紧（043 D5）——
    let read_others = call(
        &router,
        "GET",
        &format!("/subject-permissions?subject_id={ADMIN_ID}"),
        STAFF_COOKIE,
        json!(null),
    )
    .await;
    assert_forbidden(read_others, ACCESS_MANAGE).await;

    // —— 读路由保持开放（043 D7）——
    for resource in ["roles", "departments", "subject-roles", "subject-departments"] {
        let listed = call(&router, "GET", &format!("/{resource}"), STAFF_COOKIE, json!(null)).await;
        assert_eq!(
            listed.status(),
            StatusCode::OK,
            "GET /{resource} 必须对业务账号保持可读（业务页面依赖它渲染）",
        );
    }
    // 部门改名/删除被拒后，目录内容必须原样（回读）
    let departments = call(&router, "GET", "/departments", STAFF_COOKIE, json!(null)).await;
    let departments: Value = response_json(departments).await;
    assert_eq!(departments["records"][0]["name"], json!("护理部"));
}

// ============================================================================
// 3 + 4. 引导者路径与委派
// ============================================================================

#[tokio::test]
async fn platform_administrator_bootstraps_and_delegation_stays_within_its_code() {
    let TestApp { router } = test_app().await;

    // 引导者：此刻库里**没有任何** access:manage 持有者，平台管理员仍可操作
    let manager_role = call(&router, "POST", "/roles", ADMIN_COOKIE, json!({
        "role_code": "access.manager",
        "display_name": "账号管理员",
        "permission_codes": [ACCESS_MANAGE],
    })).await;
    assert_eq!(manager_role.status(), StatusCode::CREATED);

    let delegated = call(
        &router,
        "PUT",
        &format!("/subject-roles/subjects/{MANAGER_ID}"),
        ADMIN_COOKIE,
        json!({"role_codes": ["access.manager"]}),
    )
    .await;
    assert_eq!(delegated.status(), StatusCode::OK, "引导者必须能分配角色");

    // 委派生效：manager 能管角色与归属……
    let created_by_manager = call(&router, "POST", "/roles", MANAGER_COOKIE, json!({
        "role_code": "pharmacy.staff",
        "display_name": "药房药师",
        "permission_codes": ["pharmacy:dispense"],
    })).await;
    assert_eq!(created_by_manager.status(), StatusCode::CREATED);

    let assign_by_manager = call(
        &router,
        "PUT",
        &format!("/subject-roles/subjects/{STAFF_ID}"),
        MANAGER_COOKIE,
        json!({"role_codes": ["pharmacy.staff"]}),
    )
    .await;
    assert_eq!(assign_by_manager.status(), StatusCode::OK);

    let set_department_by_manager = call(
        &router,
        "PUT",
        &format!("/subject-departments/subjects/{STAFF_ID}"),
        MANAGER_COOKIE,
        json!({"department_id": null}),
    )
    .await;
    assert_eq!(
        set_department_by_manager.status(),
        StatusCode::OK,
        "用户↔部门归属归 access:manage（043 §11 裁定 2）",
    );

    // ……但仍改不了部门目录（两个码相互独立）
    let department_by_manager = call(&router, "POST", "/departments", MANAGER_COOKIE, json!({
        "code": "manager-dept",
        "name": "越权目录",
    })).await;
    assert_forbidden(department_by_manager, BASE_DATA_MANAGE).await;

    // 反向：拥有 base_data:manage 也不得动角色
    let base_data_role = call(&router, "POST", "/roles", ADMIN_COOKIE, json!({
        "role_code": "org.admin",
        "display_name": "组织管理员",
        "permission_codes": [BASE_DATA_MANAGE],
    })).await;
    assert_eq!(base_data_role.status(), StatusCode::CREATED);
    let assigned = call(
        &router,
        "PUT",
        &format!("/subject-roles/subjects/{MANAGER_ID}"),
        ADMIN_COOKIE,
        json!({"role_codes": ["org.admin"]}),
    )
    .await;
    assert_eq!(assigned.status(), StatusCode::OK);

    let department_by_org_admin = call(&router, "POST", "/departments", MANAGER_COOKIE, json!({
        "code": "nursing",
        "name": "护理部",
    })).await;
    assert_eq!(
        department_by_org_admin.status(),
        StatusCode::CREATED,
        "拥有 base_data:manage 后部门目录可写",
    );

    let role_by_org_admin = call(&router, "POST", "/roles", MANAGER_COOKIE, json!({
        "role_code": "org.other",
        "display_name": "越权角色",
    })).await;
    assert_forbidden(role_by_org_admin, ACCESS_MANAGE).await;
}

// ============================================================================
// 辅助
// ============================================================================

/// 断言 403 且响应体带出**缺哪个权限码**（前端才能说清"该找谁要哪权限"）。
async fn assert_forbidden(response: axum::response::Response, expected_permission: &str) {
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    let body: Value = response_json(response).await;
    assert_eq!(
        body["required_permission"],
        json!(expected_permission),
        "403 必须带 required_permission，实际 {body}",
    );
    assert_eq!(
        body["type"],
        json!(format!("{API_PREFIX}/problems/permission-required")),
    );
    assert_eq!(body["status"], json!(403));
}

async fn call(
    router: &Router,
    method: &str,
    path: &str,
    cookie: &str,
    body: Value,
) -> axum::response::Response {
    let request = Request::builder()
        .method(method)
        .uri(format!("{API_PREFIX}{path}"))
        .header(COOKIE, cookie)
        .header(CONTENT_TYPE, "application/json")
        .body(Body::from(body.to_string()))
        .expect("build JSON request");
    router.clone().oneshot(request).await.expect("route response")
}

async fn response_json(response: axum::response::Response) -> Value {
    let body = to_bytes(response.into_body(), 1024 * 1024)
        .await
        .expect("read response body");
    serde_json::from_slice(&body).expect("decode JSON response")
}

/// 桩身份服务：按会话 cookie 返回不同主体（平台管理员 / 已委派 / 普通业务账号）。
async fn start_identity_service() -> String {
    let identity_router = Router::new().route(
        "/crate-api/identity/v1/session",
        get(|headers: axum::http::HeaderMap| async move {
            let cookie = headers
                .get(COOKIE)
                .and_then(|value| value.to_str().ok())
                .unwrap_or_default()
                .to_owned();
            let (subject_id, platform_admin) = if cookie.contains("admin-session") {
                (ADMIN_ID, true)
            } else if cookie.contains("manager-session") {
                (MANAGER_ID, false)
            } else if cookie.contains("staff-session") {
                (STAFF_ID, false)
            } else {
                ("subject-anonymous", false)
            };
            Json(json!({
                "subject_id": subject_id,
                "access": "完整",
                "platform_admin": platform_admin,
            }))
        }),
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
