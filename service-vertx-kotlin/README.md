# service-vertx-kotlin

Vert.x Kotlin monorepo for the Trainova and Aceso products. The authoritative architecture reference is [`../docs/architecture.md`](../docs/architecture.md).

## Requirements

- JDK 25 toolchain
- PostgreSQL
- Gradle wrapper

## Run Trainova

```bash
cp apps/trainova/config.example.json apps/trainova/config.json
export PITCHFORK_CONFIG="$PWD/apps/trainova/config.json"
export PITCHFORK_DB_PASSWORD='local-development-password'
./gradlew :apps:trainova:run
```

Trainova listens on `8421`.

## Run Aceso

```bash
cp apps/aceso/config.example.json apps/aceso/config.json
export PITCHFORK_CONFIG="$PWD/apps/aceso/config.json"
export PITCHFORK_DB_PASSWORD='local-development-password'
./gradlew :apps:aceso:run
```

Aceso listens on `8422`.

## jOOQ

Platform `auth`, `users` and shared capabilities have been migrated to external services: `platformd` serves `/crate-api/identity/v1/*` and (since plan 055) `/crate-api/v1/*`, which replaced `service-nexus-shared`. The corresponding Kotlin libs (`libs/auth`, `libs/users`) have been removed.

Aceso's calls to the shared API (`subject-permissions`, `messages`, `departments`) send `X-App-Code: aceso`; platformd scopes settings, files, messages, interactions and roles by that code.
