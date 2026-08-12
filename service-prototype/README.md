# service-prototype

`service-prototype` is the minimal Go service skeleton of the pitchfork
monorepo. It follows the engineering conventions of `service-idp-go`
(`cmd/prototyped` composition root, `internal/config`, `internal/database`,
`internal/httpapi` layering) and provides a health endpoint that does not
depend on a database.

## Requirements

- Go 1.24 or newer

## Build

```bash
make build
```

The binary is written to `bin/prototyped`.

## Test

```bash
make test
```

All tests are pure in-memory: configuration loading and the HTTP routing
table are exercised with `httptest` and an injected lookup, without a
database or network.

## Static checks

```bash
make vet
```

## Run

```bash
make run
```

or, with an explicit port:

```bash
PORT=9432 make run
```

The service listens on `PORT` (default `8423`, valid range 0–65535) and
exposes:

```
GET /crate-api/prototype/v1/healthz
```

which returns `200` with `Content-Type: application/json` and a JSON body
such as:

```json
{"status":"ok"}
```

Unknown paths return `404`; methods other than `GET` on the health endpoint
return `405` with an `Allow` header.

## Configuration

Configuration is read from environment variables (a `.env.example` template
is provided; there is no implicit dotenv loading):

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8423` | HTTP listen port (0–65535); invalid values abort startup with a clear error |
