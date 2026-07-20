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
export PITCHFORK_JWT_SECRET='long-random-secret'
./gradlew :apps:trainova:run
```

Trainova listens on `8421`.

## Run Aceso

```bash
cp apps/aceso/config.example.json apps/aceso/config.json
export PITCHFORK_CONFIG="$PWD/apps/aceso/config.json"
export PITCHFORK_DB_PASSWORD='local-development-password'
export PITCHFORK_JWT_SECRET='long-random-secret'
./gradlew :apps:aceso:run
```

Aceso listens on `8422`.

## jOOQ

Set `PITCHFORK_DB_PASSWORD` before code generation. The checked-in `jooq-config.xml` files contain only a placeholder; Gradle writes the rendered configuration under `build/tmp`.

```bash
PITCHFORK_DB_PASSWORD='local-development-password' ./gradlew :libs:users:generateJooq
```
