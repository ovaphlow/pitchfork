# identityd

`identityd` is the lightweight, local identity-control service planned in
`../docs/plans/service-idp-go.md`.

## Current Slice

The initial implementation provides configuration parsing, SQLite startup with
WAL and defensive connection settings, embedded ordered migrations, mandatory
first-run bootstrap administration, Argon2id local login, opaque browser
sessions, CSRF-protected logout, and a minimal protected dashboard. It does not
yet provide login throttling, identity-management pages, password change, or
OIDC token issuance.

## Requirements

- Go 1.24 or newer
- `sqlc v1.31.1` in `$(go env GOPATH)/bin` (required by the Make targets that compile Go code)

## Run

```bash
cp .env.example .env
set -a
. ./.env
set +a
make test
make run
```

The currently available endpoints are:

- `GET /crate-api/identity/v1/healthz`
- `GET /crate-api/identity/v1/login`
- `POST /crate-api/identity/v1/sessions`
- `DELETE /crate-api/identity/v1/sessions/current`
- `GET /crate-api/identity/v1/dashboard`

Generated `internal/database/sqlc` source and built web assets are ignored.
Each Make target that compiles Go code regenerates the SQLC package first.
The terminal receives text logs at `INFO` and above. `WARN` and `ERROR` records
are also written as JSON Lines to `logs/identityd-YYYY-MM-DD.jsonl`.
