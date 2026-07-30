# Kotlin Backend Work Rules

Root [`../AGENTS.md`](../AGENTS.md) defines the API contract, migration bands and
database-test safety rules. Product composition and configuration are in
[`../docs/architecture.md`](../docs/architecture.md).

## Product Boundaries

- `apps:trainova` serves Trainova on `8421`; `apps:aceso` serves Aceso on `8422`.
- A product mounts only its own domain libraries. Do not add a cross-product lib
  dependency to reuse a table or endpoint.
- `settings`, `messages` and `files` are Nexus capabilities. Consume their HTTP
  API at `/crate-api/shared/v1/*`; do not recreate the removed Kotlin libraries.
- `healthcare` is incubating and may not be depended on by an app until it has a
  complete product API contract.

## Module Changes

- Find the closest existing module before adding code. Routes are thin and use
  relative paths; the app `Main.kt` mounts `/crate-api/<module>/v1/*`.
- Put persistence and business rules in services. Use generated jOOQ tables and
  execute queries through `pool.preparedQuery(DatabaseConfig.sql(query))` with
  `DatabaseConfig.tuple(query)`.
- Raw SQL is limited to genuinely dynamic or PostgreSQL-specific queries and
  must use Kotlin-safe PostgreSQL parameter placeholders.
- For nullable jOOQ inserts/updates, omit `.set()` rather than binding `null`;
  use `ctx.select(listOf(...))` when a large select defeats type inference.

## Schema And Verification

- Place schema changes and the module `jooq-config.xml` with the owning library.
  After a schema change, run
  `PITCHFORK_DB_PASSWORD=... ./gradlew :libs:<module>:generateJooq`; never commit
  rendered configuration from `build/tmp`.
- Default checks are the narrow module compile and relevant non-database tests,
  for example `./gradlew :libs:<module>:compileKotlin`.
- A product distribution embeds library JARs. When a distribution is explicitly
  needed after library changes, rebuild it with `clean` or `--rerun-tasks`.
