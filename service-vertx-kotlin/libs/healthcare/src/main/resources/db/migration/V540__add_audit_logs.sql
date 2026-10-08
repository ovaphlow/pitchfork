-- =====================================================
-- Healthcare — 操作审计日志（054 F3，V540）
--
-- 背景（054 §3 Dh / §4 F3 / §8.6 / §11.2 Q8）：
--   集中式**旁路**审计中间件在响应结束时落一条元数据记录，用于系统日志与操作审计。
--   只记元数据（`actor/method/path/status_code/resource_id/occurred_at/metadata`），
--   **绝不落请求体**（避免 PHI 落库）；写路由由系统内部产生，读路由 `GET /healthcare/v1/audit-logs`
--   受 `access:manage`（**读路由门禁的唯一例外**，054 §11.2 Q8）。
--   本迁移只新增审计日志一张表，**不修改**任何既有表。
--
-- 口径：
--   1. 只审计写方法（POST/PUT/PATCH/DELETE）；GET 不入账。
--   2. `actor` 取认证主体（`ctx["userId"]`）；中间件挂在认证总闸之后，故正常路径必有值，
--      但列保持可空以兼容异常路径。
--   3. `path` = `/crate-api` + `normalizedPath()`（含 ULID 的规范化路径）；`resource_id` 单独存
--      `:id` 路径参数，便于按资源回溯。
--   4. 落库失败只 `log.warn`，**不阻断主请求、不改状态码**。
--   5. `metadata` 只放非 PHI 的上下文（如 `platform_admin`），禁止写入请求体/身份信息。
--   6. 054 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**不新增 jOOQ 引用**。
-- =====================================================

CREATE SCHEMA IF NOT EXISTS healthcare;
SET search_path TO healthcare, public;

CREATE TABLE healthcare.audit_logs (
    id           VARCHAR(32) PRIMARY KEY,              -- 服务端生成 26 位 ULID
    actor        VARCHAR,                              -- 认证主体（ctx["userId"]），异常路径可空
    method       VARCHAR NOT NULL,                     -- HTTP 方法（POST/PUT/PATCH/DELETE）
    path         VARCHAR NOT NULL,                     -- /crate-api + normalizedPath()
    status_code  INTEGER NOT NULL,                     -- 响应状态码（含门禁 403）
    resource_id  VARCHAR,                              -- `:id` 路径参数（弱关联，便于回溯）
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),   -- 响应结束时刻
    metadata     JSONB                                 -- 非 PHI 上下文（禁止请求体）
);

COMMENT ON TABLE healthcare.audit_logs IS '操作审计日志（054 F3）：只记元数据、不落请求体；写方法入账，GET 不入账';
COMMENT ON COLUMN healthcare.audit_logs.actor IS '认证主体（ctx["userId"]）；中间件在认证总闸之后，正常路径必有值';
COMMENT ON COLUMN healthcare.audit_logs.path IS '规范化路径：/crate-api + normalizedPath()';
COMMENT ON COLUMN healthcare.audit_logs.status_code IS '响应状态码：门禁 403 尝试同样入账';
COMMENT ON COLUMN healthcare.audit_logs.resource_id IS '路径参数 :id（弱关联，便于按资源回溯）';
COMMENT ON COLUMN healthcare.audit_logs.metadata IS '非 PHI 上下文 JSONB（如 platform_admin）；**禁止写请求体**';

CREATE INDEX IF NOT EXISTS idx_audit_logs_occurred
    ON healthcare.audit_logs(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_occurred
    ON healthcare.audit_logs(actor, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_method_path_occurred
    ON healthcare.audit_logs(method, path, occurred_at DESC);
