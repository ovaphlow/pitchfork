# Go + SQLite Lightweight Identity Service Plan

## 1. Purpose

Build `service-idp-go` as a low-resource identity service for Pitchfork's
first-party, enterprise intranet applications. It must let applications written
in different languages and backed by different databases rely on one identity
source without sharing an application user table or reimplementing login,
password storage, and browser sessions.

The service is deliberately not a general-purpose internet IAM product and is
not intended to replace Keycloak feature-for-feature. It should be deployable
as one small Go process with one local SQLite database in a customer-managed
environment.

The public contract between the identity service and every product will be
OpenID Connect once the OIDC phase is complete. No product may read the
identity service's SQLite file or rely on its internal tables. Each deployment
belongs to exactly one customer environment and has its own identity namespace
and SQLite database.

## 2. Relationship To Existing Work

`service-core-go-stdlib` contains an older PostgreSQL-oriented authentication
and OIDC prototype. `docs/plans/oidc-provider.md` describes a separate,
Kotlin/Vert.x-based OIDC provider proposal.

This document deliberately does not depend on or integrate with either
implementation. `service-idp-go` is the only issuer considered by this plan.
After the Go service is mature, other products may migrate to it through the
standard OIDC contract. This document does not modify the Kotlin plan.

## 3. Scope And Boundaries

### In Scope

- First-party web applications in an enterprise intranet.
- A small server-side administrative UI for user and OIDC-client management.
- Locally managed accounts, password credentials, browser sessions, account
  disablement, and audit events.
- SQLite persistence, embedded SQL migrations, and `sqlc`-generated Go query
  code.
- Standard-library `net/http` routing using method-qualified patterns such as
  `GET /crate-api/identity/v1/users` and
  `PATCH /crate-api/identity/v1/users/{id}`.
- Server-rendered HTML with Go `html/template`, HTMX interactions, and a
  locally compiled Tailwind CSS bundle.
- A future OIDC Authorization Code + PKCE implementation for registered,
  first-party clients only.
- One customer environment, one service instance, one SQLite database, and
  one identity namespace per deployment.

### Explicitly Out Of Scope For The First Releases

- Public internet sign-up, email verification, password recovery, or social
  login.
- Third-party client registration, consent screens for arbitrary applications,
  and a public developer platform.
- SAML, LDAP federation, SCIM, device flow, client credentials, and MFA.
- External OIDC federation or identity brokering. The service is a local IdP,
  not an OIDC client or proxy for another identity system.
- Multi-writer SQLite, active-active high availability, or a shared database
  file on NFS.
- Product-specific roles, organisation membership, and resource-level access
  control. Those remain owned by each product.
- A JSON administration API before a concrete API consumer requires one.

Internal-only does not remove the need for TLS, password hashing, CSRF
protection, rate limiting, audit logging, or strict redirect URI validation.

## 4. Architecture Decisions

| Topic | Decision | Reason |
| --- | --- | --- |
| Service boundary | New root-level `service-idp-go/` module and local IdP only | It remains deployable independently of every product and does not broker an external IdP. |
| Runtime | Go 1.24+ and `net/http` | The standard library's method/path ServeMux patterns are sufficient and avoid a router dependency. |
| Deployment tenancy | One customer environment per service/database | SQLite stays local, no tenant model is needed, and one customer's identities cannot share a namespace with another customer's deployment. |
| Database | One local SQLite file in WAL mode | Low operational cost and adequate for a single instance with low-to-moderate login traffic. |
| SQLite driver | Pure-Go SQLite driver only; start with `modernc.org/sqlite` | Windows and Linux deployments do not need a C toolchain or CGO build path. Phase 0 validates target binary size and startup behaviour. |
| Query layer | `sqlc` with `database/sql` | SQL stays reviewable and SQLite-specific while Go callers receive typed methods and models. |
| Migrations | Embedded, ordered SQL migrations with a `schema_migrations` table | No persistent migration binary or framework process is required. |
| IDs | ULID for every persisted primary key | Matches repository-wide ID conventions and supports cross-system references. |
| Status values | Chinese `启用` and `禁用` values | Matches repository-wide business-enum conventions. |
| HTML | `html/template` and `go:embed` | Server rendering keeps the control plane light and avoids a separate JavaScript application. |
| Interaction | Locally served HTMX | Partial page updates without a frontend runtime or external CDN dependency. |
| Styling | Tailwind CSS v4 compiled during the build | The production service serves static CSS and needs no network access in the browser. |
| Generated outputs | Commit `sqlc` Go output; do not commit built static assets | Generated query code is required to compile and is reviewable; CI verifies it is synchronized with the SQL source. |
| Password storage | Argon2id hashes only | Passwords are never reversible or stored as encrypted plaintext. |
| Token signing | Asymmetric signing keys in the future OIDC phase | Products verify with public JWKS keys and cannot mint tokens themselves. |

## 5. Proposed Repository Layout

```text
service-idp-go/
├── cmd/identityd/
│   └── main.go                  # Configuration, migrations, HTTP lifecycle
├── db/
│   ├── migrations/              # Ordered SQLite DDL; source of schema truth
│   └── queries/                 # Named SQL consumed by sqlc
├── internal/
│   ├── database/                # Connection, WAL pragmas, embedded migrator
│   │   └── sqlc/                # Generated, committed Go code; never edited manually
│   ├── identity/                # User, credential, client, audit services
│   ├── session/                 # Opaque browser sessions and CSRF validation
│   ├── web/                     # HTTP handlers, templates, static assets
│   └── oidc/                    # Added only when Phase 3 begins
├── web/
│   ├── assets/                  # Tailwind source and vendored HTMX source
│   └── static/                  # Generated CSS and JS served by Go; ignored
├── sqlc.yaml
├── package.json                 # Tailwind/HTMX build-only dependencies
├── Makefile
├── go.mod
└── README.md
```

Generated `internal/database/sqlc` code is committed, while `web/static`
assets are not. `make generate` updates the generated query code after a
migration or query-source change; `make check-generated` regenerates it and
fails when the working tree differs. `make test`, `make build`, and release
packaging compile the committed generated source directly.

Login throttling uses a required `IDENTITYD_LOGIN_THROTTLE_SECRET` with at
least 32 bytes. It is a deployment-specific HMAC key, remains outside SQLite,
and must stay stable across restarts. Phase 1 defaults to five failures within
15 minutes per identifier/source pair, followed by a 15-minute lockout. The
window, failure count, and lockout duration are configurable with
`IDENTITYD_LOGIN_THROTTLE_WINDOW`, `IDENTITYD_LOGIN_THROTTLE_FAILURES`, and
`IDENTITYD_LOGIN_THROTTLE_LOCKOUT`.

## 6. `sqlc` Plan

Yes, `sqlc` is a strong fit for this project. It supports SQLite and does not
require an ORM. Migration files provide the schema to the generator; named
queries in `db/queries/*.sql` become typed Go methods.

The project will use a configuration equivalent to:

```yaml
version: "2"
sql:
  - engine: "sqlite"
    schema: "db/migrations"
    queries: "db/queries"
    gen:
      go:
        package: "sqlc"
        out: "internal/database/sqlc"
        sql_package: "database/sql"
        emit_json_tags: true
        emit_interface: true
```

`sqlc` is a build-time dependency, pinned to `v1.31.1` in development and CI
tooling rather than added to this service's runtime `go.mod`. That avoids
pulling the generator's Go 1.26 dependency graph into a Go 1.24 service. The
Makefile resolves `$(go env GOPATH)/bin/sqlc` by default and accepts a `SQLC`
override. Local and CI commands will be:

```bash
make generate
make check-generated
make assets
make test
```

Only migrations and query files are hand-written. Application services call
the generated `Queries` interface, which also makes service tests easy to
isolate.

## 7. Data Ownership And Persistence Areas

Every table uses a ULID primary key unless the row is an internal migration
record. No product table is created in this database. This section is a
structural index of the detailed data model; its column names, constraints,
and lifecycle rules are defined in `service-idp-go-data-model.md`.

| Table | Initial phase | Purpose |
| --- | --- | --- |
| `identity_subjects` | Phase 1 | Stable security subject, account state, security revision, and timestamps. |
| `identity_profiles` | Phase 1 | Minimal shared display information for the identity-control plane. |
| `identity_identifiers` | Phase 1 | Login and contact identifiers, their normalised lookup values, use, and state. |
| `identity_password_credentials` | Phase 1 | Argon2id password material, password revision, and credential-change information. |
| `identity_roles` / `identity_subject_roles` | Phase 1 | IdP control-plane roles and their audited assignments; never product roles. |
| `identity_sessions` | Phase 1 | Opaque browser-session token hash, CSRF secret, expiry, revocation, and last activity. |
| `identity_login_throttles` | Phase 1 | Persistent, pseudonymous login rate-limit state. |
| `identity_audit_events` | Phase 1 | Login success/failure, logout, bootstrap, user creation, disablement, recovery, and administrative changes. |
| `oidc_clients` | Phase 2 | First-party OIDC client registration. Client fields and client-type constraints are pending data-model design. |
| `oidc_client_redirect_uris` / `oidc_client_scopes` | Phase 2 | Normalised OIDC client redirect URI and permitted-scope registration. |
| `oidc_authorization_codes` | Phase 3 | Hashed, one-time, short-lived authorization codes and PKCE metadata. |
| `oidc_refresh_tokens` | Phase 3 | Hashed, rotating refresh token sessions with revocation and expiry. |
| `oidc_signing_keys` | Phase 3 | Signing-key metadata and public JWK material; private key material comes from protected deployment storage. |

The identity subject is always `identity_subjects.id`. A product stores it in an
`identity_subject_id` column and owns its own profile and roles. For example,
Trainova decides whether that subject is a lecturer; Aceso decides whether it
is a pharmacist.

## 8. Route Design

All HTTP endpoints use the repository convention:

```text
/crate-api/{module}/v1/{resource}
```

For this service, `{module}` is fixed as `identity`, making the stable version
one prefix:

```text
/crate-api/identity/v1
```

The current administration API uses JSON and REST-shaped resource URLs beneath
that prefix. Tailwind/HTMX pages use the same resources and browser session;
Node manages their pinned build-time dependencies only, never the production
HTTP service. OIDC route names are specified by the protocol, but they also
remain beneath the same versioned issuer path.

The standard-library router wiring remains direct and method-qualified:

```go
const identityPrefix = "/crate-api/identity/v1"

mux.HandleFunc("GET "+identityPrefix+"/subjects", handler.listSubjects)
mux.HandleFunc("POST "+identityPrefix+"/subjects", handler.createSubject)
mux.HandleFunc("PATCH "+identityPrefix+"/subjects/{subjectID}", handler.updateSubject)
```

### Phase 1: Administration API and UI

| Method pattern | URL | Purpose |
| --- | --- | --- |
| `GET /crate-api/identity/v1/healthz` | `/crate-api/identity/v1/healthz` | Liveness and readiness check. |
| `GET /crate-api/identity/v1/login` | `/crate-api/identity/v1/login` | Local administrator login page. |
| `POST /crate-api/identity/v1/sessions` | `/crate-api/identity/v1/sessions` | Create browser session after credential verification. |
| `DELETE /crate-api/identity/v1/sessions/current` | `/crate-api/identity/v1/sessions/current` | End current browser session. |
| `GET /crate-api/identity/v1/password` | `/crate-api/identity/v1/password` | Render the self-service password-change page for the current session. |
| `PATCH /crate-api/identity/v1/password` | `/crate-api/identity/v1/password` | Change the current subject password; requires CSRF validation and revokes active sessions. |
| `GET /crate-api/identity/v1/dashboard` | `/crate-api/identity/v1/dashboard` | Operational dashboard. |
| `GET /crate-api/identity/v1/subjects` | `/crate-api/identity/v1/subjects` | Paginated identity list; requires `identity.admin`. |
| `POST /crate-api/identity/v1/subjects` | `/crate-api/identity/v1/subjects` | Create a local identity; requires `identity.admin` and CSRF validation. |
| `GET /crate-api/identity/v1/subjects/{subjectID}` | `/crate-api/identity/v1/subjects/{subjectID}` | Identity detail; requires `identity.admin`. |
| `PATCH /crate-api/identity/v1/subjects/{subjectID}` | `/crate-api/identity/v1/subjects/{subjectID}` | Disable the subject or set its temporary password; requires `identity.admin` and CSRF validation. |
| `GET /crate-api/identity/v1/clients` | `/crate-api/identity/v1/clients` | Registered OIDC-client list, added in Phase 2. |
| `POST /crate-api/identity/v1/clients` | `/crate-api/identity/v1/clients` | Register a first-party client, added in Phase 2. |
| `PATCH /crate-api/identity/v1/clients/{id}` | `/crate-api/identity/v1/clients/{id}` | Update or disable a client, added in Phase 2. |
| `GET /crate-api/identity/v1/assets/{path...}` | `/crate-api/identity/v1/assets/{path...}` | Versioned, locally served Tailwind and HTMX assets. |

The management page uses the same resources and HTTP methods as the JSON API.
`Accept: text/html` selects the complete server-rendered page, and
`HX-Request: true` selects an HTML row fragment after a write. No `/api`
variants exist merely for HTMX.

The representation-selection contract remains explicit so HTML and JSON cannot
be confused by browsers or reverse proxies:

```text
HX-Request: true                 -> HTML fragment after a management write
Accept: text/html                -> complete HTML management page
Accept: application/json         -> standard JSON API representation
Vary: Accept, HX-Request         -> set on representation-dependent responses
```

### Current JSON Representation

The current administrator API uses the same versioned resources:

```text
GET    /crate-api/identity/v1/subjects?limit=50&offset=0
POST   /crate-api/identity/v1/subjects
GET    /crate-api/identity/v1/subjects/{subjectID}
PATCH  /crate-api/identity/v1/subjects/{subjectID}
GET    /crate-api/identity/v1/password
PATCH  /crate-api/identity/v1/password
```

Lists return `{ "records": [...], "meta": { "total": N } }`; errors return
`{ "error": "..." }`. The subject `PATCH` body is either
`{"status":"禁用"}` or `{"temporary_password":"..."}`. A temporary password
sets the credential status to `需更新`, so the next login is `仅改密` and may use
only the password-change and sign-out routes. The password route accepts
`{"current_password":"...","new_password":"..."}`. Its successful update
uses the stored credential revision, increments the subject security version,
revokes every active browser session, records `凭据变更`, and returns `204`.

### Phase 3: Standard OIDC Endpoints

When OIDC is enabled, the issuer is the versioned module URL, for example:

```text
https://identity.example.internal/crate-api/identity/v1
```

OIDC discovery is therefore published at the issuer-relative well-known path.
The `.well-known` name is mandated by OpenID Connect; all other endpoint
locations are advertised by discovery and remain under the same prefix.

```text
GET   /crate-api/identity/v1/.well-known/openid-configuration
GET   /crate-api/identity/v1/jwks
GET   /crate-api/identity/v1/authorize
POST  /crate-api/identity/v1/token
GET   /crate-api/identity/v1/userinfo
POST  /crate-api/identity/v1/revoke
```

The only supported browser grant in the first OIDC release is Authorization
Code + PKCE. The deprecated password grant is not included.
`GET /crate-api/identity/v1/authorize` uses the existing browser session from
Phase 1. Products validate token `iss`, `aud`, signature, `exp`, and `sub`;
they do not use the SQLite database to validate a request.

The `v1` component is part of the issuer string. It must not change for an
ordinary implementation or response-format revision: a new OIDC major version
means a new issuer, discovery document, client configuration, and token trust
boundary. This is intentional and prevents accidental cross-version token
acceptance.

## 9. User Experience Plan

The interface is a focused operational console, not a product landing page.
It should be usable repeatedly by an administrator on a small desktop or
tablet screen.

- The login page is the first screen when no valid admin session exists.
- The dashboard exposes a short operational summary: active identities,
  disabled identities, active browser sessions, and recent audit events.
- The users page has a search field and an HTMX-updated result table. It uses
  clear status chips and compact action controls.
- User creation and editing occur on dedicated pages, avoiding nested cards
  and modal-only workflows.
- Client management is introduced only when OIDC client registration is
  functional; no placeholder pages pretend OIDC is available beforehand.
- Tailwind is compiled into one local CSS file. HTMX is served locally from
  `web/static`; production browser rendering does not use a CDN.

## 10. Security Baseline

The first usable release must include all of the following:

- A mandatory bootstrap administrator supplied only by environment variables
  on the first run. The plaintext bootstrap password is never persisted or
  logged.
- Argon2id password hashes with parameters documented and versioned for
  later rehashing.
- Opaque, high-entropy browser session tokens. Only token hashes are stored
  in SQLite.
- `HttpOnly`, `SameSite=Lax`, and `Secure` session cookies in production.
  Development may explicitly disable `Secure` for localhost only.
- CSRF tokens on all state-changing browser requests, including HTMX forms.
- Login rate limiting and audit events for success and failure.
- Authorization checks on every administration route; only administrators may
  create, edit, enable, or disable identities.
- The final enabled administrator cannot be disabled or stripped of
  administrative capability through the UI or HTTP API.
- A local, audited recovery command resets an administrator credential and
  revokes that administrator's sessions. It is an operator command, not an
  HTTP endpoint; the exact command contract is defined with the data model.
- No hard-delete UI for identities. Disablement preserves auditability.
- TLS terminated by the local reverse proxy or the service itself. An intranet
  deployment still requires a trusted internal certificate.
- Content Security Policy, `X-Content-Type-Options`, clickjacking protection,
  and safe template escaping.

Phase 3 additionally requires asymmetric signing, `kid`-based rotation,
JWKS publication, exact redirect URI matching, one-time authorization codes,
PKCE S256 verification, short-lived access tokens, and hashed rotating refresh
tokens.

## 11. Configuration And Operations

Initial configuration should be environment-based and documented in
`.env.example`:

```text
IDENTITYD_ADDR=127.0.0.1:8432
IDENTITYD_DATABASE_PATH=.data/identityd.sqlite
IDENTITYD_BOOTSTRAP_IDENTIFIER=admin
IDENTITYD_BOOTSTRAP_PASSWORD=<required-on-first-start>
IDENTITYD_SESSION_TTL=12h
IDENTITYD_SESSION_IDLE_TTL=30m
IDENTITYD_SESSION_SECURE_COOKIE=false
IDENTITYD_PUBLIC_URL=https://identity.example.internal/crate-api/identity/v1
IDENTITYD_TRUSTED_PROXY_CIDRS=127.0.0.1/32
```

`IDENTITYD_PUBLIC_URL` is the only externally visible base URL. When OIDC is
enabled, it is also the exact issuer value; the service never derives its
issuer from an untrusted `Host` header. The default local port avoids current
Trainova and Aceso API ports; production should normally place the service
behind an HTTPS reverse proxy rather than expose this port directly.

Operational requirements:

- Open SQLite with WAL mode, `synchronous=FULL`, a five-second busy timeout,
  foreign keys enabled, `trusted_schema=OFF`, the default 1,000-page WAL
  autocheckpoint, a 64 MiB journal-size limit, and one conservative writer
  connection pool.
- Read `X-Forwarded-For` and `X-Forwarded-Proto` only from a source address in
  `IDENTITYD_TRUSTED_PROXY_CIDRS`; use the validated client address for login
  rate limiting.
- Require an `https` `IDENTITYD_PUBLIC_URL` and secure cookies in production.
  Session cookies use `Path=/crate-api/identity/v1` to avoid being sent to
  unrelated same-host products.
- Persist the database on a local volume; exclude it from source control.
- Back up the database regularly and document restore verification.
- Run a new binary against a copy of production data before schema upgrades.
- Write human-readable `INFO`-and-above application logs to the terminal and
  append `WARN`/`ERROR` records to daily JSONL files under `logs/`; never log
  passwords, session tokens, authorization codes, or refresh tokens.

## 12. Delivery Phases

### Phase 0: Technical Validation

1. Validate `modernc.org/sqlite` on the supported Windows and Linux target
   platforms, including binary size, startup time, WAL behaviour, and backup
   restoration.
2. Install the pinned `sqlc v1.31.1` build tool and confirm it generates
   SQLite code in CI.
3. Compile a Tailwind v4 and local HTMX asset pipeline without depending on a
   running Node server in production.
4. Confirm the one-customer, one-instance, one-SQLite-database deployment
   model and document the supported reverse-proxy configurations.

### Phase 1: Secure Local Identity Administration

1. Create the independent Go module, Makefile, asset build, and development
   documentation.
2. Add embedded SQLite migrations and `sqlc` queries for identities,
   credentials, sessions, and audit events.
3. Implement bootstrap admin creation, local login/logout, authorization,
   session handling, CSRF validation, rate limiting, final-administrator
   protection, and local recovery.
4. Implement Tailwind/HTMX pages for dashboard and identity management; build
   local CSS and vendored HTMX with pinned Node dependencies, then embed only
   the generated assets in the Go binary.
5. Add unit, database integration, HTTP handler, and browser-flow tests.

**Acceptance criteria:** a fresh, empty SQLite database can start safely;
the configured bootstrap administrator can sign in; administrators can create
and disable identities; disabled identities cannot create a session; no raw
password or browser-session token is persisted; and the final enabled
administrator cannot be disabled.

### Phase 2: First-Party Client Registry

1. Finalise the OIDC-client data model, then add migrations, `sqlc` queries,
   and administrator pages.
2. Require exact, HTTPS redirect URI registration except explicitly permitted
   localhost development URLs.
3. Define allowed scopes and client status, but do not yet expose token
   endpoints.
4. Add client change events to the audit log.

**Acceptance criteria:** an administrator can register, review, disable, and
audit a first-party OIDC client without entering a client secret into browser
pages or logs.

### Phase 3: OIDC Authorization Code + PKCE

1. Implement discovery, JWKS, authorization, token, userinfo, and revocation
   endpoints using an established OAuth/OIDC server-side protocol library.
2. Generate and rotate asymmetric signing keys with public JWKS continuity.
3. Store only hashes of authorization codes and refresh tokens.
4. Implement authorization-code, PKCE, `state`, `nonce`, redirect URI, scope,
   and audience validation.
5. Create a Go resource-server integration example and a language-neutral
   OIDC validation guide, including local JWKS caching and all required claim
   validation.

**Acceptance criteria:** a registered local SPA can complete Authorization
Code + PKCE, receive short-lived tokens, call userinfo, refresh safely, log
out, and be rejected if its redirect URI, verifier, audience, or client status
is invalid.

### Phase 4: Product Integration And Deployment Documentation

1. Add Trainova and Aceso integration only after Phase 3 passes a security
   review.
2. Document Windows and Linux deployment, reverse-proxy setup, backup, restore,
   and upgrade procedures for an isolated customer installation.
3. Document identity-subject mapping and per-product authorization ownership.
4. Run restore, account-disablement, and signing-key-rotation exercises.

## 13. Test Strategy

| Layer | Coverage |
| --- | --- |
| Migration tests | Empty database, repeat startup, malformed migration failure, and upgrade from a previous schema. |
| `sqlc` integration tests | CRUD, pagination, filtering, status transitions, session revocation, and transaction rollback against temporary SQLite files. |
| Service tests | Password verification, bootstrap rules, no raw token persistence, authorization, and audit event creation. |
| HTTP tests | Exact `net/http` method/path matching, redirect-after-write, CSRF, cookie attributes, HTMX partial responses, and 401/403 behaviour. |
| OIDC conformance tests | Added in Phase 3 for PKCE, redirect URI, code reuse, key rotation, token claims, and revocation. |
| UI checks | Desktop and narrow viewport screenshots; verify Tailwind output, locally served HTMX, and no clipped or overlapping text. |
| CI checks | Regenerate and compare committed `sqlc` source, build ignored assets, run `go test ./...`, `go vet ./...`, and execute a minimal startup smoke test. |

## 14. Data Model Baseline

The detailed Phase 1 baseline is in
[`service-idp-go-data-model.md`](./service-idp-go-data-model.md). It defines
the subject/identifier/credential boundary, profile ownership, control-plane
roles, session and throttling retention, SQLite constraints, and the initial
`sqlc` query surface without copying a generic `users` schema.

Phase 1 migrations may now be designed from that baseline. OIDC client type,
signing algorithm, and protocol-specific additions remain Phase 2/3 design
work; no `oidc_*` migration is created before that separate review.

The Phase 3 relying-party pilot can be chosen after the data model and Phase 1
control plane have passed their tests. The first supported deployment assumes
an HTTPS reverse proxy and an internal DNS name, as defined in Section 11.

## 15. Recommendation

Proceed with Phase 0, then implement Phase 1 migrations and queries from the
Section 14 baseline. This gives the team a small, useful, secure local
identity-control plane and validates the Go/SQLite/`sqlc`/HTMX stack before
taking on the much larger OIDC protocol responsibility.

Do not begin Phase 3 merely because the administration UI exists. OIDC token
issuance is a separate security milestone and must be tested as a protocol,
not treated as a JSON login endpoint.
