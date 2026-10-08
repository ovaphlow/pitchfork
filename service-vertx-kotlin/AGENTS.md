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
- Do not add new jOOQ usage. Existing jOOQ in Trainova-side code
  (`libs/{knowledge,skills,trainings,exams,onsite,analytics}`, `apps/trainova`)
  is legacy and out of the native-SQL default — convert it only when a task
  explicitly scopes that migration. When editing legacy jOOQ code, keep its
  conventions: omit `.set()` for nullable inserts/updates rather than binding
  `null`, and use `ctx.select(listOf(...))` when a large select defeats type
  inference.

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
