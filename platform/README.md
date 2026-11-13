# platformd

`platformd` is the lightweight, local identity-control service planned in
`../docs/plans/service-idp-go.md`.

## Current Slice

The initial implementation provides configuration parsing, SQLite startup with
WAL and defensive connection settings, embedded ordered migrations, mandatory
first-run bootstrap administration, Argon2id local login, opaque browser
sessions, CSRF-protected logout, a minimal protected dashboard, and a JSON
administrator API for listing, creating, inspecting, and disabling subjects.
Disabling a subject increments its security version, revokes its active
sessions, and cannot disable the final enabled `identity.admin` holder.
Administrators can set an audited temporary password; its `需更新` credential
status restricts the next browser session to password change and sign-out.
Changing a password uses credential-revision optimistic locking, increments the
subject security version, revokes active sessions, and requires a new login.
It provides a Tailwind/HTMX server-rendered identity-management page, but does
not yet provide OIDC token issuance. Login failures are persistently throttled
by a keyed identifier and validated client address.

## Requirements

- Go 1.24 or newer
- Node.js 20 or newer and pnpm 11 or newer to build local Tailwind and HTMX assets

## Run

```bash
cp .env.example .env
set -a
. ./.env
set +a
make test
make assets
make run
```

The currently available endpoints are:

- `GET /crate-api/identity/v1/healthz`
- `GET /crate-api/identity/v1/session`
- `GET /crate-api/identity/v1/login`
- `POST /crate-api/identity/v1/sessions`
- `POST /crate-api/identity/v1/registrations`
- `POST /crate-api/identity/v1/email-login-links`
- `POST /crate-api/identity/v1/email-login-links/{token}/exchange`
- `DELETE /crate-api/identity/v1/sessions/current`
- `GET /crate-api/identity/v1/password`
- `PATCH /crate-api/identity/v1/password`
- `GET /crate-api/identity/v1/dashboard`
- `GET /crate-api/identity/v1/subjects?limit=20&offset=0`
- `POST /crate-api/identity/v1/subjects`
- `GET /crate-api/identity/v1/subjects/{subjectID}`
- `PATCH /crate-api/identity/v1/subjects/{subjectID}`

登录请求携带 `Accept: application/json` 时返回 JSON，并同时设置浏览器会话 Cookie；省略该请求头时保留浏览器表单的重定向行为。

`POST /registrations` 是公开自注册入口，只接受 JSON
`{"display_name":"...","identifier":"...","password":"..."}`。它创建普通启用主体
（`platform_admin=false`）、Argon2id 密码凭据和审计事件，并直接建立完整会话
（响应带 `access`，同样设置会话 Cookie）。账号标识沿用 IDP 的账号规则：3–64
字符，可含 `@`，统一转小写；密码至少 12 字节。重复标识返回 `409`，无效输入返回
`400`，未知字段一律拒绝。当前不包含邮箱验证和找回密码；面向互联网部署时必须由
可信网关限制注册频率，并且只暴露网关，不直接暴露 platformd 端口。

`POST /email-login-links` 是邮箱链接登录的申请入口，只接受 JSON
`{"email":"...","display_name":"...?","redirect_uri":"..."}`。`display_name` 只用于
自助建号：命中已存在且可登录的邮箱时忽略它；未知邮箱带 `display_name` 时创建
**无密码**主体（`identifier_type=邮箱`、主登录、无密码凭据），否则不发信。响应永远
是 `202 {"status":"accepted"}`——未知邮箱、限流、投递失败与成功不可区分，避免枚举
账号。`redirect_uri` 必须与 `PLATFORMD_EMAIL_LINK_REDIRECT_URIS` 精确匹配，否则
`500 email-login-misconfigured`（配置漂移，运维可见）。

`POST /email-login-links/{token}/exchange` 兑换邮件里的 `email_token`：token 10 分钟
有效、一次性、只以 SHA-256 哈希落库；兑换成功直接签发 `完整` 会话（同时把邮箱标识
标记为已验证），并写一条 `AuditActionLogin`、`metadata` 为
`{"method":"email_link"}` 的审计事件。无效、过期、已用或主体被禁用的 token 统一
`401 invalid-email-login-link`。邮件由 `PLATFORMD_SMTP_*` 配置的 SMTP 中继同步发送；
未配置 `PLATFORMD_SMTP_ADDR` 时申请端点返回 `503`，密码登录不受影响。

The subject endpoints require a `完整` browser session whose subject has the
`identity.admin` role. `POST` and `PATCH` also require the `platformd_csrf`
cookie value in the `X-CSRF-Token` request header. Create requests contain
`display_name`, `identifier`, and `password`. `PATCH /password` accepts
`{"current_password":"...","new_password":"..."}`. `PATCH /subjects/{subjectID}`
accepts either `{"status":"禁用"}` or
`{"temporary_password":"a sufficiently long password"}`. The latter marks
the credential `需更新`; the next successful login receives a `仅改密` session
that can access only the password route and sign-out. Lists use
`{ "records": [...], "meta": { "total": N } }`; single subjects are returned
directly. Every HTTP error body, including unmatched routes and unsupported
methods, uses RFC 9457 Problem Details with
`Content-Type: application/problem+json`:

```json
{
  "type": "/crate-api/identity/v1/problems/invalid-request",
  "title": "Bad Request",
  "status": 400,
  "detail": "invalid JSON request",
  "instance": "/crate-api/identity/v1/subjects"
}
```

`type` is a stable, service-local URI reference, `title` is the HTTP status
phrase, and `instance` is the requested URI (including its query string).
Problem Details never include the legacy `error` member. Browser form failures
that intentionally redirect do not send an error body.

`GET /session` requires a `完整` browser session and returns the authenticated
identity as `{"subject_id":"...","access":"完整"}`. It is the only endpoint
used by downstream services, such as Nexus: they forward the original Cookie
header and never inspect the opaque session token themselves.

`GET /subjects` returns its JSON list by default. A browser request with
`Accept: text/html` receives the server-rendered management page; HTMX requests
receive a newly created or updated table-row fragment. HTML and JSON requests
share the same authorization, CSRF validation, and identity-domain
transactions.

SQL queries are written directly in Go alongside the code that uses them
(`internal/identity`), so the project has no code-generation step and no
generated query package to keep in sync. Built web assets remain ignored.
`web/assets/app.css`, `package.json`, `pnpm-lock.yaml`, and
`web/scripts/build-assets.mjs` are source. `make assets` produces ignored
`web/static/app.css` and `web/static/htmx.min.js`, which `make build` embeds in
the Go binary. It first runs `pnpm install --frozen-lockfile`, so it can be
used from a clean checkout. Node is therefore a build-time tool only;
production runs the Go binary without Node or an external CDN. CI should run
`make assets` to reject stale web assets.
`PLATFORMD_LOGIN_THROTTLE_SECRET` is required, must contain at least 32 bytes,
must be unique per deployment, and must remain stable across restarts.
The terminal receives text logs at `INFO` and above. `WARN` and `ERROR` records
are also written as JSON Lines to `logs/platformd-YYYY-MM-DD.jsonl`.

## Shared capabilities (055)

`platformd` also serves the cross-product capabilities previously hosted by
`service-nexus-shared` (Rust/Axum), so one process owns both identity and shared
data on `http://127.0.0.1:8420`:

- `/crate-api/v1/settings`, `/messages`, `/interactions`, `/files`
- `/crate-api/v1/roles`, `/subject-roles`, `/subject-permissions`
- `/crate-api/v1/departments`, `/subject-departments`

All endpoints require a `完整` platformd session cookie and are authenticated
in-process; the old HTTP call to `GET /crate-api/identity/v1/session` is gone.
Write routes keep the 043 authorization gate: `roles`, `subject-roles` and
`subject-departments` require `access:manage`; `departments` writes require
`base_data:manage`; `platform_admin` subjects may always write. Read routes
only require a session.

Lists keep the Nexus contract (`page` default `1`, `page_size` default `20`,
maximum `100`). Errors keep RFC 9457 Problem Details with
`type = /crate-api/v1/problems/<slug>`; permission failures add
`required_permission`. Uploads use `POST /crate-api/v1/files/upload`
(`multipart/form-data`, field `file`).

Two environment variables configure the shared slice:

- `PLATFORMD_FILES_DIR` (default `.data/files`): file entity storage directory.
- `PLATFORMD_MAX_UPLOAD_BYTES` (default `20971520`): maximum upload size.

Migrations `000013`–`000020` create the shared tables in `platformd.sqlite`.

### Migrating data from the retired service-nexus-shared database

The Rust service was merged into platformd (data migrated first, with a
`platformd.sqlite` backup kept from that run) and then removed from the tree.
If you still have an archived copy of its SQLite database and file directory,
stop `platformd` (or at least its write traffic) and import it:

```bash
cd platform
set -a
. ./.env
set +a
go run ./cmd/nexus-import -dry-run \
  -source-db ~/nexus-archive/nexus.sqlite \
  -source-files ~/nexus-archive/files
go run ./cmd/nexus-import \
  -source-db ~/nexus-archive/nexus.sqlite \
  -source-files ~/nexus-archive/files
```

The importer applies pending migrations, copies the eight shared tables with
`INSERT OR IGNORE` (idempotent), backs up `platformd.sqlite` with `VACUUM INTO`
before writing, and copies file entities (`stored_name`) into
`PLATFORMD_FILES_DIR`. Its defaults still point at
`../service-nexus-shared/.data/...` for the migration window; pass explicit
flags once the old tree is gone.

See `../docs/plans/055.nexus-shared-merge-into-platform.md`.
