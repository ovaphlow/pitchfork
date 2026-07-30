# Crate 作为 OIDC 提供方（SSO / OIDC IdP）实现计划

> 模块：`service-vertx-kotlin/libs/oidc`
> 角色：OIDC **提供方（IdP）**，自实现，不对接外部 IdP
> 范围：仅后端 API（登录 UI 由前端负责）
## 1. 目标与范围

 在 `service-vertx-kotlin` 中以**独立 `libs/oidc` 模块**实现 OIDC **提供方（IdP）**：对外暴露标准 OIDC 端点，供 **Crate 任意自有应用**（含前端 SPA、各 `apps/*` 后端服务，以及未来新增 app）通过依赖本模块并挂载路由接入。不引入任何第三方身份服务，认证完全自实现。**注意**：SSO（共享会话 / `crate_sso` cookie）仅适用于有浏览器的前端 SPA；纯后端服务无浏览器、无 cookie，应走 `password` / `refresh_token` grant 各自获取 token，不属于 SSO 语义。
- **自实现**，不对接任何外部 IdP；用户身份仍来自现有 `users` 表。
- **仅后端 API**；登录 UI 由前端负责（本次不涉及）。
- 与 Go 工程是平行关系、互不直接调用，本计划在 vertx-kotlin 内**完全独立实现**，仅遵循 OIDC 标准协议规范。

## 2. 设计概览

```
   SPA（浏览器，走 SSO/cookie）        内部服务（无浏览器，走 password/refresh）
   │  /authorize (PKCE + SSO cookie)            │  /token (password|refresh_token)
   ▼                               ▼
libs/oidc ── 验证 users 表 bcrypt 密码 ──► 签发 RS256 access_token + id_token + 不透明 refresh_token
   │  /jwks (RS256 公钥)   /userinfo   /introspect   /revoke
   ▼
现有 users / permissions 模块（access_token 兼容既有 HS256 守卫，见 §7）
```

> 说明：端点遵循 OIDC 标准协议（discovery/jwks/token/userinfo/introspect/revoke），便于 Crate 各端统一接入；但身份源、密钥、客户端注册全部自建，不依赖任何外部 IdP 或第三方服务。

端点由 `libs/oidc` 以 `OidcRoutes.create(vertx, pool, authConfig)` 暴露，调用方 app 在自己的 `Main.kt` 中挂载到 `apiRouter.route("/oidc/v1/*")`（即完整路径 `/crate-api/oidc/v1/{func}`）。`Routes.kt` 内部使用相对路径（如 `/token`）；OIDC discovery 同时额外挂在标准 `/crate-api/.well-known/openid-configuration` 以便工具兼容。不限定具体 app——任何需要 OIDC 的 app 依赖本模块并按此方式挂载即可（后续新增 app 同理）。

## 3. 模块结构（遵循 Module Pattern）

```
libs/oidc/
├── build.gradle.kts
└── src/main/kotlin/com/ovaphlow/crate/oidc/
    ├── OidcRoutes.kt          # Router：薄路由，协议端点
    ├── OidcService.kt         # 令牌签发/验证、grant 处理
    ├── OidcKeyService.kt      # RSA 签名密钥生成/持久化/JWKS
    ├── OidcClientService.kt   # 自有客户端注册与校验
    ├── OidcSessionService.kt  # 服务端登录会话管理（UNLOGGED 表）
    └── OidcExceptions.kt      # OAuth2 标准错误（invalid_grant 等）
```

> **注意**：Flyway 迁移文件存放位置见 §4（统一在 `libs/database` 的迁移目录）。jOOQ 生成类**沿用现有 per-lib 约定**：`libs/oidc` 自带 `jooq-config.xml` 与 `generateJooq` task（参照 `libs/users/build.gradle.kts`，`workingDir = projectDir`，仅包含本模块所需表），生成的类归 `libs/oidc` 私有使用，不依赖 `libs/database` 暴露表类。

依赖：`libs:database`、`libs:common`、`libs:auth`（复用 bcrypt 校验/用户查询）、`vertx-web`、`vertx-auth-jwt`（用其 `JWTAuth` 以 RS256 签发/验签，已存在于 `libs.versions.toml`）。**无需新增第三方 OIDC 库**——RS256 由 `vertx-auth-jwt` 的 `PubSecKeyOptions(algorithm="RS256")` 支持，JWK 的 `n/e` 用标准 `java.security` 计算。

## 4. 数据表迁移（Flyway）

> **迁移文件位置**：采用 per-lib 分散方案，迁移文件放在 `libs/oidc/src/main/resources/db/migration/` 目录下，使用 V600+ 版本号。与现有 users、settings、pharmacy、nursing、healthcare、inventories 等 lib 做法一致。Flyway 在运行时通过 `locations("classpath:db/migration")` 自动聚合当前 app 所依赖的各个 lib jar 中的迁移文件，每个 app 仅执行其依赖范围内的迁移（例如不依赖 `libs/oidc` 的 app 不会收到 oidc 迁移）。jOOQ 生成类沿用 per-lib 约定（见 §3）。

- **`oidc_clients`**（`V600`）：`id` VARCHAR(32) PK (ULID)、`client_id` VARCHAR(64) UNIQUE、`client_secret_hash` VARCHAR(255) (bcrypt，可空用于公开客户端)、`name` VARCHAR(128)、`redirect_uris` JSONB、`login_page_uris` JSONB（可选，未登录时 302 跳转的目标前端登录页 URL 列表，见 §6）、`grant_types` JSONB、`scopes` JSONB、`status` VARCHAR(16) (`启用`/`禁用`)、`created_at`/`updated_at` TIMESTAMPTZ。
- **`oidc_signing_keys`**（`V601`）：`kid` VARCHAR(64) PK、`alg` VARCHAR(8) (`RS256`)、`public_jwk` JSONB、`private_pem` TEXT、`status` VARCHAR(16) (`激活`/`已轮换`/`已吊销`)、`created_at` TIMESTAMPTZ、`expires_at` TIMESTAMPTZ。用于 JWKS 跨重启稳定与密钥轮换（状态语义见 §5）。
- **`oidc_refresh_tokens`**（`V602`）：`token` VARCHAR(64) PK、`user_id` VARCHAR(32) NOT NULL、`client_id` VARCHAR(64) NOT NULL、`expires_at` TIMESTAMPTZ NOT NULL、`revoked` BOOLEAN NOT NULL DEFAULT false、`created_at` TIMESTAMPTZ NOT NULL DEFAULT now()。索引：`idx_oidc_refresh_tokens_user_id`。不透明 refresh token 落地；`/revoke` 通过置 `revoked=true` 立即吊销（轮转时旧 token 一并置 `revoked=true`）。
- **`oidc_auth_codes`**（`V603`，授权码流程用）：`code` VARCHAR(64) PK、`user_id` VARCHAR(32) NOT NULL、`client_id` VARCHAR(64) NOT NULL、`redirect_uri` TEXT NOT NULL、`code_challenge` VARCHAR(128) NOT NULL、`code_challenge_method` VARCHAR(8) NOT NULL (`S256`)、`scope` TEXT、`nonce` VARCHAR(64)、`expires_at` TIMESTAMPTZ NOT NULL（短 TTL ~5min）、`used` BOOLEAN NOT NULL DEFAULT false（授权码一次性使用标记，`/token` 交换成功后置 `true`，后续请求直接拒绝 `invalid_grant`）。索引：`idx_oidc_auth_codes_user_id`。
- **`oidc_sessions`**（`V604`，**UNLOGGED 表**，服务端登录会话，**本期新增能力**）：`id` VARCHAR(32) PK (ULID)、`user_id` VARCHAR(32) NOT NULL、`client_id` VARCHAR(64)、`sso_token` VARCHAR(64) NOT NULL（即 `crate_sso` cookie 的不透明值，该 cookie 为本期首次引入，此前登录为无状态 HS256 JWT、不写 cookie）、`scope` TEXT（创建会话时记录的权限范围，用于 `/introspect` 返回）、`expires_at` TIMESTAMPTZ NOT NULL、`revoked` BOOLEAN NOT NULL DEFAULT false、`last_active_at` TIMESTAMPTZ、`created_at` TIMESTAMPTZ NOT NULL DEFAULT now()。索引：`idx_oidc_sessions_sso_token`、`idx_oidc_sessions_user_id`。用途：将登录态落地为**服务端可查、可吊销**的会话，支撑多实例部署、全域登出、并发会话控制（此前的认证无服务端会话层）。

> **外键约束说明**：为保持灵活性和性能，表间不设置数据库层面的外键约束。引用完整性（如 `user_id` 必须存在于 `users` 表）由应用层（`OidcService`、`OidcClientService` 等）在业务逻辑中保证。

> **UNLOGGED 取舍**：该表不写 WAL、不进流复制，崩溃/故障切换后内容会丢失（会话需重新登录），换来更低写放大与更高吞吐——符合会话这类易失态的场景。其他 oidc 表仍为普通 LOGGED 表（令牌/密钥需持久）。Flyway 对 UNLOGGED 建表无特殊处理，正常 `CREATE UNLOGGED TABLE` 即可。

> Flyway 所有 lib 共享一套 `flyway_schema_history`（现有最大版本为 V500 healthcare），故 oidc 表使用 V600+ 号段避免冲突。

 迁移文件就位后，运行 `./gradlew :libs:oidc:generateJooq` 生成 `libs/oidc` 专属的 jOOQ 类（沿用 §3 的 per-lib 约定，不依赖 `libs/database` 暴露表类）。

## 5. 签名密钥管理（`OidcKeyService`）

- 启动时确保 `oidc_signing_keys` 中至少有一条 `激活` 记录；无则 `KeyPairGenerator("RSA",2048)` 生成，`kid = base64url(sha256(n)[:8])`（取 SHA-256 摘要前 8 字节做 base64url，约 11 字符，仅作密钥标识，碰撞概率可忽略），私钥以 PEM 存入，公钥转为 JWK `{kty,n,e,use:"sig",alg:"RS256",kid}`。
- `JWKS()` 返回所有可验签的密钥，即 `status` 为 `激活` 或 `已轮换` 的键。`getActivePrivateKey()` 供 `JWTAuth` RS256 签发，取 `status=激活` 的键。
- 轮换：Admin 端点/定时任务生成新 `激活` 键，旧键记录 `private_pem` 清空、`public_jwk` 保留（JWKS 仍返回以验签历史 token）、`status` 置 `已轮换`；不再需要验签时彻底删除该行。若需强制作废某键则直接删除（立即从 JWKS 移除）。

## 6. 端点设计

所有端点位于 `libs/oidc`，完整路径为 `/crate-api/oidc/v1/{func}`（`Routes.kt` 内用相对路径）：

| 方法 | 路径（func） | 说明 |
|------|------|------|
| GET | `/crate-api/oidc/v1/discovery` | 内部 discovery 端点，返回 OIDC 元数据。各字段使用**完整 URL**，例如 `issuer="https://crate.example.com/crate-api"`、`authorization_endpoint="https://crate.example.com/crate-api/oidc/v1/authorize"`、`token_endpoint="https://crate.example.com/crate-api/oidc/v1/token"`、`jwks_uri="https://crate.example.com/crate-api/oidc/v1/jwks"`、`userinfo_endpoint="https://crate.example.com/crate-api/oidc/v1/userinfo"`、`introspection_endpoint="https://crate.example.com/crate-api/oidc/v1/introspect"`、`revocation_endpoint="https://crate.example.com/crate-api/oidc/v1/revoke"`，以及 `response_types_supported/scopes_supported/grant_types_supported`。**注意：此端点主要用于内部路由，标准 OIDC 工具请使用 `/.well-known/openid-configuration`。** |
| GET | `/crate-api/oidc/v1/jwks` | RS256 JWKS（JSON Web Key Set） |
| POST | `/crate-api/oidc/v1/token` | 令牌端点，支持三种 grant（见下方详细说明） |
| GET | `/crate-api/oidc/v1/userinfo` | Bearer access_token → 返回 `sub/email/email_verified/user_type` |
| GET | `/crate-api/oidc/v1/authorize` | 授权码流程入口（**仅 GET**，遵循 RFC 6749；PKCE/redirect 天然走 GET，不提供 POST 以避免 CSRF/参数注入面）：校验 client/PKCE，查 `oidc_sessions`（有效且未吊销）则直接 302 发 code（带 `code/state`），否则 302 到该客户端配置的 `login_page_uris` 中的第一个 URL（带 `client_id/state/redirect_uri` 参数）。各 app 在注册客户端时指定自己的 `login_page_uris`。 |
| POST | `/crate-api/oidc/v1/sessions` | 创建 SSO 会话（详见下方 SSO cookie 说明） |
| POST | `/crate-api/oidc/v1/logout` | 全域登出：吊销当前 `sso_token` 对应会话（可选 `revoke_all=true` 吊销该用户全部会话），并清 `crate_sso` cookie |
| POST | `/crate-api/oidc/v1/introspect` | RFC 7662（见下方说明）。**需 HTTP Basic 认证**（client_id:client_secret）。 |
| POST | `/crate-api/oidc/v1/revoke` | RFC 7009（吊销 refresh token 或会话）。**需 HTTP Basic 认证**（client_id:client_secret）。 |

> **关于 `/.well-known/openid-configuration`**：在 `Main.kt` 根路由挂载 `/crate-api/.well-known/openid-configuration` → 与 `/discovery` 返回相同内容。`issuer` 配置为 `https://crate.example.com/crate-api`（见 §8），标准 OIDC 工具通过 `{issuer}/.well-known/openid-configuration` 即 `https://crate.example.com/crate-api/.well-known/openid-configuration` 发现端点。`/discovery` 端点保留用于内部路由和测试。

### `/token` — 三种 grant 的校验逻辑

`/token` 端点根据 `grant_type` 参数分发：

- **`password`**：必填 `username` + `password` + `client_id`（注：标准 OAuth 2.0 password grant 参数名为 `username`，此处映射为 email 作为用户标识）。校验 `client_id` 存在且 `grant_types` 包含 `password`；调用 `authenticateUser(username, password)` 验证 bcrypt 密码；签发 access_token + id_token + refresh_token（refresh token 存入 `oidc_refresh_tokens`）。
- **`refresh_token`**：必填 `refresh_token` + `client_id`。查 `oidc_refresh_tokens` 表：token 存在且未 revoked 且未过期 → 将旧 token 置 `revoked=true` → 签发新 access_token + id_token + refresh_token（轮转）。否则拒绝 `invalid_grant`。
- **`authorization_code`**：必填 `code` + `code_verifier` + `client_id` + `redirect_uri`。查 `oidc_auth_codes` 表：code 存在且未过期且 `used=false` 且 `redirect_uri` 匹配 → PKCE 校验（`S256`：`BASE64URL(SHA256(code_verifier)) == code_challenge`）→ 将该 code 置 `used=true`（一次性使用）→ 签发 access_token + id_token + refresh_token。否则拒绝 `invalid_grant`。

### `/sessions` — SSO cookie 说明

`/sessions`（POST）：校验 `email` + `password`（bcrypt）→ 创建 `oidc_sessions` 行 → 生成随机 `sso_token` → 设置 HTTP-only `Secure` `SameSite=Lax` cookie `crate_sso`，值为 `sso_token`。

**Cookie 属性**：
- `HttpOnly=true`：JavaScript 不可读
- `Secure=true`：仅 HTTPS 传输
- `SameSite=Lax`：防止 CSRF，但允许顶级导航跳转
- `Path=/`：所有路径共享
- `Domain`：从配置项 `auth.oidc.cookie-domain` 读取（见 §8）。若未配置，则不设 Domain 属性（浏览器默认为当前完整主机名，同域共享；跨子域场景需显式配置父域如 `.crate.com`）

**跨应用 SSO 流程**：Crate 各前端 SPA 部署在同一父域下（如 `.crate.com`），配置相同的 `cookie-domain`，用户在任一应用登录后 `crate_sso` cookie 对所有同父域应用生效。`/authorize` 请求携带该 cookie → 有效会话直接发 code → 实现 SSO。

### 授权码流程（前后端配合）

完整的 OIDC 授权码流程涉及前后端协作：

1. 前端 SPA 将用户浏览器导航到 `/authorize?client_id=...&response_type=code&redirect_uri=...&state=...&code_challenge=...&code_challenge_method=S256`。
2. 后端 `/authorize` 检查 `crate_sso` cookie 对应的 `oidc_sessions`：
   - 会话有效 → 直接生成授权码，302 重定向到 `redirect_uri?code=xxx&state=yyy`。
   - 会话无效/不存在 → 302 重定向到该客户端配置的 `login_page_uris` 中的 URL（带 `client_id/state/redirect_uri` 等原始参数）。
3. 前端登录页收到参数后，用户填写凭据 → POST `/sessions` 创建 SSO 会话（设 `crate_sso` cookie）→ 前端将浏览器重新导航回原始 `/authorize?......` URL（利用前一步保留的参数）。
4. 浏览器携带 `crate_sso` cookie 再次请求 `/authorize` → 会话有效 → 302 到 `redirect_uri?code=xxx&state=yyy`。
5. 前端 SPA 从 URL 中提取 `code` → POST `/token?grant_type=authorization_code&code=...&code_verifier=...&client_id=...&redirect_uri=...` → 获得 access_token + id_token + refresh_token。

> **注意**：步骤 2-3 的 `login_page_uris` 由各 app 在 `oidc_clients` 表中自行配置，OIDC 模块不预设默认值。步骤 3 中前端负责保留参数并回跳（不在本次后端实现范围内，需各前端自行实现）。

### `/introspect` — 三种输入类型（需客户端认证）

`/introspect`（POST）遵循 RFC 7662，要求调用方提供 HTTP Basic 认证（`client_id:client_secret`）。`token_type_hint` 参数可选，省略时按 token 前缀自动识别：

| 输入 | 识别方式（按优先级） | 校验逻辑 | 返回 |
|------|------|------|------|
| access_token（JWT） | `token_type_hint=access_token`；或 token 以 `eyJ` 开头 | RS256 验签 + 检查 `exp` | `active=true`/`false`，含 `sub`、`aud`、`scope`、`exp`、`iss`、`token_type` |
| refresh_token（opaque） | `token_type_hint=refresh_token`；或 token 以 `rt_` 开头 | 查 `oidc_refresh_tokens`：存在且 `revoked=false` 且未过期 | `active=true`/`false`，含 `sub`、`client_id`、`scope`、`exp`、`token_type` |
| sso_token（opaque） | `token_type_hint=sso_token`；或 token 以 `st_` 开头 | 查 `oidc_sessions`：存在且 `revoked=false` 且未过期 | `active=true`/`false`，含 `sub`、`client_id`、`scope`、`exp`、`token_type` |

> **注意**：当 `token_type_hint` 提供时优先按提示查找，未命中则回退按前缀识别（遵循 RFC 7662 §2.1）。

**Token 模型**（遵循 OIDC 标准）：
- `access_token`：RS256 JWT，标准 claims 包括：`iss`（签发者，见 §8 `issuer`）、`sub`（用户 ID，`users.id`）、`aud`（受众，**字符串数组**，首元素为发起 grant 的 `client_id`；若资源服务作为独立受信方，可追加其 `client_id` 到数组）、`exp`（过期时间，~15分钟）、`iat`（签发时间）、`scope`（权限范围，格式：`openid profile email`）、`user_type`（用户类型）、`email`。**`aud` 用数组而非单值**，以便资源服务的 `PermissionGuard` 配置受信 `aud` 列表（见 §7）。 |
- `id_token`：RS256 JWT（OIDC），标准 claims 包括：`iss`、`sub`（用户 ID）、`aud`（受众，**字符串或字符串数组**，与 `access_token` 一致）、`exp`、`iat`、`nonce`（请求随机数）、`email_verified`（邮箱验证状态）。**必须包含 `iss`、`sub`、`aud`、`exp`、`iat` 以符合 OIDC 规范。**
- `refresh_token`：`rt_` + 32 字节随机 `base64url`（总长约 46 字符），存 `oidc_refresh_tokens`，TTL 30d，**使用时轮转**（吊销旧、发新）。
- `sso_token`（`crate_sso` cookie）：`st_` + 32 字节随机 `base64url`（总长约 46 字符），存 `oidc_sessions`，TTL 与 session 一致。
- `scope` 字段格式：标准 OIDC scope，如 `openid profile email`，可根据业务需求扩展。

**错误响应**：所有错误响应遵循 OAuth 2.0 标准格式：
```json
{
  "error": "invalid_grant|invalid_client|invalid_request|unauthorized_client|unsupported_grant_type|invalid_scope",
  "error_description": "具体的错误描述信息"
}
```
对应 HTTP 状态码：
- `400 Bad Request`：`invalid_request`、`invalid_client`、`invalid_grant`、`unauthorized_client`、`unsupported_grant_type`、`invalid_scope`
- `401 Unauthorized`：未提供 token 或 token 无效
- `403 Forbidden`：token 有效但权限不足
- `500 Internal Server Error`：服务器内部错误

**password grant**：复用 `AuthService` 的 bcrypt 校验逻辑（提取为 `authenticateUser(username,password)` 共享函数，`username` 即为 email）→ 取用户最小视图 → 签发三件套。

## 7. 与现有认证/权限的兼容

- 现有 `/auth/v1/login` 仍签发 **HS256** crate token，既有 SPA 与 `PermissionGuard`（`apps/*/Main.kt` 中 `JWTAuth` HS256）**不变**。
- 新 OIDC 端点签发 **RS256** token。`PermissionGuard` 使用一个同时配置 HS256 和 RS256 公钥的 `JWTAuth` 实例，`authenticate()` 根据 JWT header 的 `alg` 自动选择对应密钥验签，不存在线性回退。大幅简化改动。

**`PermissionGuard` 改动方案**：
1. 在 `PermissionGuard` 构造函数中增加 OIDC 公钥源（`OidcKeyService`），在 `JWTAuth` 配置中追加 `PubSecKeyOptions(algorithm="RS256")`，公钥从 `OidcKeyService.getActivePublicJWK()` 获取。
2. 单 `JWTAuth` 实例同时持有 HS256 对称密钥和 RS256 公钥 ，`jwtAuth.authenticate()` 自动按 `kid` 和 `alg` 分派验签，无需手动 fallback。
3. **`aud` 校验**：RS256 验签后需校验 `aud`。因 `access_token.aud` 为数组（§6），`PermissionGuard` 应配置本服务的受信 `aud` 集合（即本服务自身的 `client_id` 列表）；只要 token 的 `aud` 数组与本服务受信集合有交集即视为通过。请勿用单一固定 `aud` 字符串硬匹配，否则跨 client 的合法 token 会被错误拒绝。HS256 token 无 `aud` claim，忽略校验。

- 用户主体 `sub` = `users.id`（ULID），无需身份映射表（自实现，无外部 IdP）。

## 8. 配置（config.json）

```json
{
  "auth": {
    "jwt-secret": "...",            // 既有 HS256
    "oidc": {
      "issuer": "https://crate.example.com/crate-api",
      "access-token-ttl-seconds": 900,      // 各 app 可按需覆盖（auth.oidc 下或自有 config）
      "refresh-token-ttl-days": 30,
      "cookie-domain": ".crate.com",
      "login-page-url": "https://auth.crate.com/login"  // 默认登录页 URL（各 client 的 login_page_uris 优先）
    }
```

> **`issuer` 取值约定**：`issuer` 必须包含 schema、host 和**路径前缀**（如 `https://crate.example.com/crate-api`）。token 的 `iss` claim 与 discovery 文档的 `issuer` 字段必须**完全一致**（否则 RS256 验签失败）。标准 OIDC 工具通过 `{issuer}/.well-known/openid-configuration` 发现端点，故 `issuer` 必须与 well-known 的挂载路径匹配。各端点的完整 URL 由 discovery 文段的字段给出（见 §6），不要将 `/oidc/v1` 这类功能路径写进 `issuer`。

> **`cookie-domain` 取值约定**：用于 `crate_sso` cookie 的 `Domain` 属性。跨子域 SSO 场景需配置为父域（如 `.crate.com`，注意前导点）；同域单应用场景可省略此项（浏览器默认使用当前主机名，不含端口）。值为空或未配置时不设 Domain 属性。见 §6 `/sessions` 说明。

## 9. 实施步骤（Checklist）
1. `settings.gradle.kts` 添加 `include("libs:oidc")`；新建 `libs/oidc/build.gradle.kts`（含本模块专属的 `jooq-config.xml` 与 `generateJooq` task，参照 `libs/users/build.gradle.kts` 的 per-lib 模式，仅 `<includes>` 本模块所需表 `oidc_clients|oidc_signing_keys|oidc_refresh_tokens|oidc_auth_codes|oidc_sessions`）。**每个需要 OIDC 的 app**（不限具体目录，含未来新增）在其 `build.gradle.kts` 加入 `implementation(project(":libs:oidc"))`。
2. 在 `libs/oidc/src/main/resources/db/migration/` 目录下写 5 个 Flyway 迁移文件（V600–V604，其中 `oidc_sessions` 为 UNLOGGED）。位置与 users、pharmacy 等现有 lib 的 per-lib 模式一致。随后跑 `./gradlew :libs:oidc:generateJooq` 生成 `libs/oidc` 专属 jOOQ 类。
3. 实现 `OidcKeyService`（密钥持久化 + JWKS + `JWTAuth` RS256 工厂）。
4. 实现 `OidcClientService`（Crate 自有客户端注册/校验）。**内置种子数据**在应用启动时检查并初始化（`OidcClientService.initSeedClients()`），包含至少一个默认客户端：`client_id="crate-web"`、`client_secret_hash=bcrypt("crate-web-secret")`、`grant_types=["authorization_code","refresh_token","password"]`、`scopes=["openid","profile","email"]`、`redirect_uris` 和 `login_page_uris` 从配置项 `auth.oidc` 读取（开发环境默认 `["http://localhost:4322/callback"]`／`["http://localhost:4322/login"]`），`status="启用"`。后续新增客户端可通过直接操作 `oidc_clients` 表（INSERT SQL）实现，本期不提供 admin API。
5. 实现 `OidcSessionService`（UNLOGGED `oidc_sessions` 的建/查/吊销：创建会话、按 `sso_token` 取有效会话、按用户吊销全部、更新 `last_active_at`）。
6. 实现 `OidcService`（password/refresh_token/authorization_code 三种 grant、token 签发、introspect/revoke；`/sessions` 与 `/authorize` 经由 `OidcSessionService` 落地会话）。
7. 实现 `OidcRoutes`（所有端点，含 discovery/jwks/userinfo/authorize/sessions/logout）。
8. **每个需要 OIDC 的 app** 在其 `Main.kt` 挂载：`apiRouter.route("/oidc/v1/*").subRouter(OidcRoutes.create(vertx, pool, authConfig))`，并在 `mainRouter` 层挂载 `/.well-known/openid-configuration`（注意：在 `/crate-api/` 之前匹配，或直接在 `apiRouter` 下挂载 `/crate-api/.well-known/openid-configuration`），注入 `issuer` 配置。`issuer` 取值见 §8。（不限定具体 app，后续新增 app 同理接线即可。）
9. 修改 `PermissionGuard`：增加 RS256 验签支持，具体方案见 §7。
10. 在适用的 `AGENTS.md` 补充 OIDC 端点与约定。
11. 加最小冒烟测试（以下为 `curl` 示例，假设后端运行在 `localhost:8421`）：

```bash
# 1. Discovery
curl http://localhost:8421/crate-api/oidc/v1/discovery | jq

# 2. 创建 SSO 会话（返回 Set-Cookie: crate_sso=...）
curl -X POST http://localhost:8421/crate-api/oidc/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"secret","client_id":"crate-web"}' \
  -c cookie.jar

# 3. 授权码流程（携带 cookie，302 重定向带 code & state）
curl -v 'http://localhost:8421/crate-api/oidc/v1/authorize?\
client_id=crate-web&response_type=code&redirect_uri=http://localhost:4322/callback&\
state=abc123&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&\
code_challenge_method=S256' \
  -b cookie.jar

# 4. 用 code 换 token（从上一步 Location header 取 code 值）
curl -X POST http://localhost:8421/crate-api/oidc/v1/token \
  -d 'grant_type=authorization_code&code=<CODE>&client_id=crate-web&\
redirect_uri=http://localhost:4322/callback&code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'

# 5. 用 access_token 调 /userinfo
curl http://localhost:8421/crate-api/oidc/v1/userinfo \
  -H 'Authorization: Bearer <ACCESS_TOKEN>'

# 6. Introspect（JWT access_token，需 Basic 认证）
curl -X POST http://localhost:8421/crate-api/oidc/v1/introspect \
  -u 'crate-web:crate-web-secret' \
  -d 'token=<ACCESS_TOKEN>&token_type_hint=access_token'

# 7. Revoke refresh token（需 Basic 认证）
curl -X POST http://localhost:8421/crate-api/oidc/v1/revoke \
  -u 'crate-web:crate-web-secret' \
  -d 'token=<REFRESH_TOKEN>&token_type_hint=refresh_token'

# 8. 全域登出
curl -X POST http://localhost:8421/crate-api/oidc/v1/logout \
  -H 'Authorization: Bearer <ACCESS_TOKEN>' \
  -b cookie.jar -c cookie.jar
```

## 10. 风险与注意

- **SSO 跨域 cookie**：`crate_sso` 为 HTTP-only/Secure，Crate 各应用跨子域 SSO 需统一父域（通过 §8 `cookie-domain` 配置）；纯后端不处理 UI，登录页跳转由自有前端约定（§6 `/authorize` 的 302）。同域不同端口不共享 cookie，需通过反向代理统一端口或使用同一域名。
- **密钥安全**：`oidc_signing_keys.private_pem` 为敏感数据，写入后需确保文件系统权限。轮换时旧私钥立即清除（见 §5），降低泄露窗口。迁移/备份需注意此表安全；可考虑 KMS，本期先落库。
- **授权码流程依赖登录态**：因后端无登录 UI，浏览器 SSO 依赖 `/sessions` 设 cookie；Crate 内部纯后端服务可直接走 `password` grant 更合适（本期含 password+refresh+auth_code）。
- **令牌撤销策略**：
  - `access_token`（JWT，~15分钟 TTL）：由于 JWT 是无状态的，无法直接撤销。主要依赖短 TTL 限制暴露窗口。如需立即撤销，可维护一个内存/Redis 黑名单，但会增加复杂度，本期不实现。
  - `refresh_token`（30天 TTL）：支持立即撤销，通过 `/revoke` 端点标记为已吊销。
  - `session`（`oidc_sessions` 表）：支持立即吊销，通过 `/logout` 端点标记 `revoked=true`。
- 本计划在 vertx-kotlin 内自包含实现，与 Go 工程平行、互不直接调用。
