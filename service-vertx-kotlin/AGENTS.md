# Kotlin Backend Work Rules

Root [`../AGENTS.md`](../AGENTS.md) defines the API contract, migration bands and
database-test safety rules. Product composition and configuration are in
[`../docs/architecture.md`](../docs/architecture.md).

## Product Boundaries

- `apps:aceso` serves Aceso on `8422` (Trainova was removed on 2026-11-21).
- A product mounts only its own domain libraries. Do not add a cross-product lib
  dependency to reuse a table or endpoint.
- Shared capabilities (`settings`, `messages`, `files`, `interactions`, role and
  department directories) are served by `platformd` at `/crate-api/v1/*`;
  consume that HTTP API and do not recreate the removed Kotlin libraries.
- `healthcare` is incubating and may not be depended on by an app until it has a
  complete product API contract.

## Module Changes

- Find the closest existing module before adding code. Routes are thin and use
  relative paths; the app `Main.kt` mounts `/crate-api/<module>/v1/*`.
- Put persistence and business rules in services. **New and changed persistence
  code defaults to native parameterized SQL**: execute
  `pool.preparedQuery(sql).execute(Tuple.of(...))` with PostgreSQL `$1..$n`
  placeholders bound in positional order. Use `DatabaseConfig.sql/tuple` and the
  jOOQ builders only for legacy modules kept on the jOOQ path.
- Never interpolate user input or dynamic values into SQL text. Append dynamic
  predicate fragments together with their bound values; bind JSONB columns as
  `io.vertx.core.json.JsonObject` / `JsonArray` and read them back with
  `Row.getJsonObject` / `Row.getJsonArray`.
- Qualify cross-schema names explicitly (`healthcare.patients`,
  `nursing.nursing_task_executions`, `public.materials`, ...). Table and column
  names come only from the owning library's Flyway migration history; do not
  invent them.
- Do not add new jOOQ usage. Legacy jOOQ remains only where already written
  (e.g. parts of `libs/healthcare`); convert it only when a task explicitly
  scopes that migration. When editing such code, keep its conventions: omit
  `.set()` for nullable inserts/updates rather than binding `null`, and use
  `ctx.select(listOf(...))` when a large select defeats type inference.

## Schema And Verification

- Place schema changes (Flyway migrations under
  `libs/<module>/src/main/resources/db/migration/`) with the owning library.
  Under native SQL there is no codegen step: reference new tables and columns by
  name in the SQL. Only a module deliberately kept on the legacy jOOQ path needs
  `jooq-config.xml` and
  `PITCHFORK_DB_PASSWORD=... ./gradlew :libs:<module>:generateJooq`; never commit
  rendered configuration from `build/tmp`.
- Default checks are the narrow module compile and relevant non-database tests,
  for example `./gradlew :libs:<module>:compileKotlin`.
- A product distribution embeds library JARs. When a distribution is explicitly
  needed after library changes, rebuild it with `clean` or `--rerun-tasks`.
