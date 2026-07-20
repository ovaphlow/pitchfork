# Pitchfork Architecture

## Product Topology

This repository contains two independently deployable Kotlin products and four Astro applications. They share platform libraries, not product-domain libraries.

| Product | Backend app | Database | API port | Frontend apps |
|---|---|---|---:|---|
| Trainova | `apps:trainova` | `ovaphlow` | `8421` | `auth` (`4321`), `admin` (`4322`), `worker` (`4323`) |
| Aceso | `apps:aceso` | `aceso` | `8422` | `aceso` (`4324`) |

All backend APIs retain the `/crate-api/<module>/v1/<resource>` prefix. A port identifies the product; modules do not cross-mount between products.

## Backend Boundaries

`service-vertx-kotlin/apps/*` are composition roots. They own runtime configuration, route mounting, and the list of domain libraries shipped in a product.

| Layer | Libraries | Rule |
|---|---|---|
| Platform | `auth`, `settings`, `users`, `permissions`, `files`, `messages`, `logging`, `database`, `common` | May be shared when the application needs the capability. |
| Trainova domain | `knowledge`, `skills`, `trainings`, `exams`, `onsite`, `analytics` | Mounted only by `apps:trainova`. |
| Aceso domain | `inventories`, `pharmacy`, `nursing` | Mounted only by `apps:aceso`. |
| Incubating | `healthcare` | No application may depend on it until it has a service, routes, and a product API contract. |

An app must never add another product's domain library just to reuse a table or endpoint. Promote a capability to the platform layer only after its ownership, API and data contract are product-neutral.

## Runtime Configuration

Each backend process requires these variables:

```bash
export PITCHFORK_CONFIG="$PWD/apps/trainova/config.json"
export PITCHFORK_DB_PASSWORD='local-development-password'
export PITCHFORK_JWT_SECRET='long-random-secret'
./gradlew :apps:trainova:run
```

`PITCHFORK_CONFIG` points to a local JSON file copied from the matching `config.example.json`. It contains non-secret connection settings only. `PITCHFORK_DB_PASSWORD` and `PITCHFORK_JWT_SECRET` are mandatory environment variables; neither has a source-code fallback.

For Aceso, use `apps/aceso/config.example.json`, set `PITCHFORK_CONFIG` to its local copy, and run `:apps:aceso:run`. The Aceso Compose file also requires `PITCHFORK_DB_PASSWORD`.

Local `config.json` and `.env` files are ignored by Git. Never add passwords, JWT secrets, private keys, or rendered jOOQ configurations to version control.

## Database and Migrations

Every new domain table belongs to the library that owns its behavior. Its migration goes in:

```text
libs/<module>/src/main/resources/db/migration/
```

Flyway discovers `classpath:db/migration` from the libraries on an application's runtime classpath. Consequently, an app receives only the migrations for its declared dependencies.

`apps/trainova/src/main/resources/db/migration/V5-V7` are a legacy baseline that pre-dates library-level schema ownership. They remain in place because changing, moving, or duplicating an executed Flyway version changes validation history. Do not add new Trainova schema to that directory. Any re-baselining of those existing tables requires a separate, data-aware migration proposal.

Reserved migration bands for new work:

| Band | Owner |
|---:|---|
| `V1-V99` | users/platform core |
| `V100-V199` | settings |
| `V200-V299` | inventories |
| `V300-V399` | pharmacy |
| `V400-V499` | nursing |
| `V500-V599` | healthcare |
| `V600-V699` | knowledge (reserved) |
| `V700-V799` | skills (reserved) |
| `V800-V899` | trainings (reserved) |
| `V900-V999` | exams (reserved) |
| `V1000-V1099` | onsite (reserved) |

jOOQ configuration files contain the `__PITCHFORK_DB_PASSWORD__` placeholder. `generateJooq` requires `PITCHFORK_DB_PASSWORD` and writes a rendered XML file only under `build/tmp`.

## Frontend Boundaries

Each frontend requires an explicit `PUBLIC_API_URL`; there is no fallback backend. Copy the app's `.env.example` to `.env` before development.

| Product | Import path | Permitted API surface |
|---|---|---|
| Trainova apps | `@pitchfork/shared/trainova` | Platform and Trainova APIs |
| Aceso | `@pitchfork/shared/aceso` | Authentication, users and departments currently implemented by Aceso |

Do not import another product's API client from an app. Add a product-scoped export only after the corresponding backend route is mounted in that product.
