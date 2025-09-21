# service-core-go-stdlib

Small Go service using only the stdlib plus zap for logging and lib/pq for Postgres.

Requirements
- Go 1.21+
- Postgres database

Environment
- DATABASE_URL - Postgres DSN (optional, default provided for local testing)
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