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
- Put persistence and business rules in services. **All persistence code is native
  parameterized SQL** (jOOQ was removed from the whole backend on 2026-11-22):
  execute `pool.preparedQuery(sql).execute(Tuple.of(...))` with PostgreSQL
  `$1..$n` placeholders bound in positional order. Inside
  `pool.withTransaction { connection -> ... }` always run statements on that
  `connection`, never on the `pool`, or the write escapes the transaction.
- Never interpolate user input or dynamic values into SQL text. Append dynamic
  predicate fragments together with their bound values; bind JSONB columns as
  `io.vertx.core.json.JsonObject` / `JsonArray` and read them back with
  `Row.getJsonObject` / `Row.getJsonArray`.
- Qualify cross-schema names explicitly (`healthcare.patients`,
  `nursing.nursing_task_executions`, `public.materials`, ...). Table and column
  names come only from the owning library's Flyway migration history; do not
  invent them.
- Dynamic SQL: append a predicate fragment and its bound value together (for
  example a local `fun bind(value: Any?): String = "\$${args.size + 1}".also
  { args.add(value) }`); build `IN` lists from `args.indices.joinToString(", ")
  { "\$${it + 1}" }` plus `Tuple.from(list)`; reuse one placeholder when the same
  constant appears in several `FILTER (WHERE ...)` clauses.
- Every row read is by column name (`row.getValue("col")`), and every query
  lists its columns explicitly with the aliases the mapper expects. `SELECT *`
  is only acceptable where the mapper tolerates absent columns.

## Schema And Verification

- Place schema changes (Flyway migrations under
  `libs/<module>/src/main/resources/db/migration/`) with the owning library.
  There is no codegen step: reference new tables and columns by name in the SQL,
  and treat the migration history as the only source of truth for those names.
  No `jooq-config.xml`, no generated sources under `src/main/java`, no
  `generateJooq` task — adding either back reintroduces a codegen step that can
  only run against a live database.
- Default checks are the narrow module compile and relevant non-database tests,
  for example `./gradlew :libs:<module>:compileKotlin`.
- A product distribution embeds library JARs. When a distribution is explicitly
  needed after library changes, rebuild it with `clean` or `--rerun-tasks`.
