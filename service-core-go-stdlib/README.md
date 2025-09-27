# service-core-go-stdlib

Small Go service using only the stdlib plus zap for logging and lib/pq for Postgres.

Requirements
- Go 1.21+
- Postgres database

Environment
- DATABASE_URL - Postgres DSN (optional, default provided for local testing)

Environment
-----------

This service will load a `.env` file from the project root at startup (best-effort).
Put any environment variables there (for example `DATABASE_URL`, `LOG_LEVEL`, `LOG_DEV`).
Values in the real environment (exported in the shell or set by Docker) will still take precedence.

Note: passwords containing special URL characters like `#` are handled by the startup code
which percent-encodes `#` in the DSN userinfo section to avoid URL parsing errors.
- LOG_LEVEL - debug|info|warn|error
- LOG_DEV - set to 1 for development logger

Run locally

1. Set env vars (see `.env.example`)
2. go mod tidy
3. go build -o bin/service ./...
4. ./bin/service
#

##

users, auth, settings, files