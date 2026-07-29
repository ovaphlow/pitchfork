# identityd

`identityd` is the lightweight, local identity-control service planned in
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
- `sqlc v1.31.1` in `$(go env GOPATH)/bin` when running `make generate` or `make check-generated`
- Node.js 20 or newer and pnpm 11 or newer to build local Tailwind and HTMX assets

## Run

```bash
cp .env.example .env
set -a
. ./.env
set +a
make test
make assets
make check-generated
make run
```

The currently available endpoints are:

- `GET /crate-api/identity/v1/healthz`
- `GET /crate-api/identity/v1/session`
- `GET /crate-api/identity/v1/login`
- `POST /crate-api/identity/v1/sessions`
- `DELETE /crate-api/identity/v1/sessions/current`
- `GET /crate-api/identity/v1/password`
- `PATCH /crate-api/identity/v1/password`
- `GET /crate-api/identity/v1/dashboard`
- `GET /crate-api/identity/v1/subjects?limit=20&offset=0`
- `POST /crate-api/identity/v1/subjects`
- `GET /crate-api/identity/v1/subjects/{subjectID}`
- `PATCH /crate-api/identity/v1/subjects/{subjectID}`

登录请求携带 `Accept: application/json` 时返回 JSON，并同时设置浏览器会话 Cookie；省略该请求头时保留浏览器表单的重定向行为。

The subject endpoints require a `完整` browser session whose subject has the
`identity.admin` role. `POST` and `PATCH` also require the `identityd_csrf`
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

Generated `internal/database/sqlc` source is committed and must be regenerated
after changing migrations or query files. Built web assets remain ignored.
`web/assets/app.css`, `package.json`, `pnpm-lock.yaml`, and
`web/scripts/build-assets.mjs` are source. `make assets` produces ignored
`web/static/app.css` and `web/static/htmx.min.js`, which `make build` embeds in
the Go binary. It first runs `pnpm install --frozen-lockfile`, so it can be
used from a clean checkout. Node is therefore a build-time tool only;
production runs the Go binary without Node or an external CDN. CI should run
`make assets` and `make check-generated` to reject stale assets or generated
query code.
`IDENTITYD_LOGIN_THROTTLE_SECRET` is required, must contain at least 32 bytes,
must be unique per deployment, and must remain stable across restarts.
The terminal receives text logs at `INFO` and above. `WARN` and `ERROR` records
are also written as JSON Lines to `logs/identityd-YYYY-MM-DD.jsonl`.
