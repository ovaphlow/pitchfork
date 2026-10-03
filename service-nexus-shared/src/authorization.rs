//! 043：Nexus 侧授权门禁（提权链与基础数据写）。
//!
//! ## 为什么门禁必须落在 Nexus
//!
//! Nexus 监听 `0.0.0.0:8421`，只认 IDP 会话 cookie——任何登录用户都能**直连**它。
//! 若门禁只加在 Aceso 代理层，绕过成本为零。因此授权权威在本服务。
//!
//! ## 放行条件（双轨，043 §11 裁定）
//!
//! ```text
//! allow ⇔ platform_admin == true  ∨  required ∈ 主体权限码集合
//! ```
//!
//! 平台管理员是**引导者**：没有它，权限码的第一次授予无处发生（鸡生蛋）。也正因为有它，
//! 接线当刻不会出现"全员 403"，因此**不需要 040 §9.1 那种放量协议**。
//!
//! ## 门禁缺失过的后果（本模块存在的理由）
//!
//! 修前实测：`nurse01`（只有 `nursing.staff`）能 `POST /shared/v1/roles` 建带任意权限码的角色、
//! 能 `PUT /shared/v1/subject-roles/subjects/{自己}` 把该角色分给自己——**三步即可拿到任意权限码**，
//! 包括当时唯一已接线的护理执行门禁。基础数据目录（部门/角色）同样任何登录用户可改。
//!
//! ## 读路由为什么不开门禁
//!
//! 角色目录与部门目录是**产品元数据**，业务页面（用户页、角色页、申领科室下拉）依赖它渲染；
//! 本轮范围只堵"写"（043 D7）。新增**写**路由时必须挂进各模块的 `write_router()`，
//! 该不变式由 `tests/authorization.rs` 兜底。

use axum::extract::{Request, State};
use axum::middleware::Next;
use axum::response::Response;
use sqlx::FromRow;

use crate::AppState;
use crate::auth::Identity;
use crate::error::{ApiError, ApiResult};

/// 账号与授权管理：角色目录写、用户↔角色、用户↔部门。
pub const ACCESS_MANAGE_PERMISSION: &str = "access:manage";

/// 基础数据管理：部门目录写（后续物资/床位/费用项/仓库并入时沿用此码）。
pub const BASE_DATA_MANAGE_PERMISSION: &str = "base_data:manage";

#[derive(FromRow)]
struct PermissionCodesRow {
    permission_codes: String,
}

/// 该主体当前拥有的权限码集合（角色目录里的 `permission_codes` 并集）。
///
/// 本地 SQLite 查询，不引入缓存：缓存只会带来"撤权滞后"的语义负担
/// （Aceso 侧的 60s 缓存是跨进程 HTTP 才需要）。查询走 `subject_roles.subject_id` 索引。
pub async fn permission_codes(state: &AppState, subject_id: &str) -> ApiResult<Vec<String>> {
    let rows = sqlx::query_as::<_, PermissionCodesRow>(
        "SELECT role.permission_codes \
         FROM subject_roles AS assignment \
         JOIN roles AS role ON role.id = assignment.role_id \
         WHERE assignment.subject_id = ? \
         ORDER BY role.role_code",
    )
    .bind(subject_id)
    .fetch_all(&state.database)
    .await?;

    let mut codes: Vec<String> = Vec::new();
    for row in rows {
        let parsed: Vec<String> = serde_json::from_str(&row.permission_codes)?;
        for code in parsed {
            if !codes.contains(&code) {
                codes.push(code);
            }
        }
    }
    Ok(codes)
}

/// 放行判定：平台管理员 ∨ 拥有所需权限码。
pub async fn is_allowed(state: &AppState, identity: &Identity, required: &str) -> ApiResult<bool> {
    if identity.platform_admin {
        return Ok(true);
    }
    Ok(permission_codes(state, &identity.subject_id)
        .await?
        .iter()
        .any(|code| code == required))
}

async fn guard(
    state: AppState,
    request: Request,
    next: Next,
    required: &'static str,
) -> ApiResult<Response> {
    // 身份由 `auth::require_identity` 写入 extensions；缺它说明挂载顺序被改动（fail-closed 401）。
    let identity = request
        .extensions()
        .get::<Identity>()
        .cloned()
        .ok_or(ApiError::Unauthenticated)?;

    if !is_allowed(&state, &identity, required).await? {
        return Err(ApiError::ForbiddenPermission(required.to_owned()));
    }
    Ok(next.run(request).await)
}

/// 角色 / 用户↔角色 / 用户↔部门 的写门禁。
pub async fn require_access_manage(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> ApiResult<Response> {
    guard(state, request, next, ACCESS_MANAGE_PERMISSION).await
}

/// 部门目录（基础数据）的写门禁。
pub async fn require_base_data_manage(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> ApiResult<Response> {
    guard(state, request, next, BASE_DATA_MANAGE_PERMISSION).await
}

#[cfg(test)]
mod tests {
    use super::{ACCESS_MANAGE_PERMISSION, BASE_DATA_MANAGE_PERMISSION};

    /// 权限码必须符合 Nexus 侧的格式约定（040 D2：`^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$`）。
    /// 写死格式校验而不是引用产品侧常量——Nexus 不依赖产品代码，只校验格式。
    #[test]
    fn permission_codes_follow_the_documented_format() {
        for code in [ACCESS_MANAGE_PERMISSION, BASE_DATA_MANAGE_PERMISSION] {
            let (domain, action) = code.split_once(':').expect("permission code must contain ':'");
            for part in [domain, action] {
                assert!(!part.is_empty(), "{code}");
                assert!(
                    part.chars().next().expect("non-empty").is_ascii_lowercase(),
                    "{code} must start with a lowercase letter",
                );
                assert!(
                    part.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_'),
                    "{code} contains an unsupported character",
                );
            }
        }
    }
}
