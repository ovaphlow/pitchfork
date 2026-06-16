# AGENTS.md — AI Agent Instructions for service-vertx-kotlin

## Overview

This is a Vert.x Kotlin monorepo backend for the **Crate** (克拉特) manufacturing employee training platform. It runs on JDK 25 with Kotlin 2.3.x, Vert.x 4.5.x, PostgreSQL, jOOQ, and Flyway.

## Code Architecture

```
service-vertx-kotlin/
├── apps/
│   └── service/                    # Entrypoint (Main.kt with sub-router mounting)
│       └── src/main/resources/
│           ├── config.json          # Runtime config (DB, JWT, port)
│           └── logback.xml          # Logging config
├── libs/                           # Domain modules (each = Kotlin library JAR)
│   ├── auth/                       # Login, sign-up, JWT
│   ├── settings/                   # Departments & key-value config
│   ├── files/                      # File serving
│   ├── permission/                 # RBAC + ReBAC + ABAC
│   ├── messages/                   # Notifications
│   ├── users/                      # Employee user management
│   ├── knowledge/                  # Knowledge base
│   ├── skills/                     # Skills, positions, certificates
│   ├── training/                   # Courses, chapters, assignments
│   ├── exam/                       # Questions, papers, records
│   ├── onsite/                     # QR devices, offline cache
│   ├── ai-assistant/               # AI Q&A, FAQ, push rules
│   ├── analytics/                  # Dashboard aggregation
│   ├── database/                   # DB connection, Flyway migrations, jOOQ codegen
│   └── common/                     # Ulid, RsaCrypto
├── config.json                     # Root config (read by service)
└── logs/                           # JSONL log output
```

## Conventions (Follow These Strictly)

### Module Pattern

Every lib module has exactly two files:
- `<ModuleName>Routes.kt` — `object` with `fun create(vertx: Vertx, pool: Pool): Router`
- `<ModuleName>Service.kt` — `class` with all DB operations

### Route Pattern

```kotlin
object MyRoutes {
    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = MyService(pool)
        router.route().handler(BodyHandler.create())

        // ALWAYS include health check
        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "my-module"))
        }

        router.get("/items").handler { ctx ->
            service.list(/* params from ctx.request().getParam() */)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }
        return router
    }

    private fun body(ctx: RoutingContext) = ctx.body().asJsonObject() ?: JsonObject()
    private fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }
    private fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("module route error", err)
        ctx.response().setStatusCode(500).end(JsonObject().put("error", "internal error").encode())
    }
}
```

- **Use RELATIVE paths** inside sub-routers (`/items`, NOT `/my-module/v1/items`). Version prefix is applied in `Main.kt`.
- **CRITICAL**: `respond()` must set `Content-Type: application/json`. Some modules omit this — always include it in new code.

### Service Pattern

```kotlin
class MyService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {
    private val t = MyTable.MY_TABLE  // jOOQ generated table

    fun list(limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.select(t.ID, t.NAME).from(t).where(conditions)
            .orderBy(t.CREATED_AT.desc()).limit(limit).offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows -> /* build { records, meta } */ }
            }
    }
}
```

### SQL Parameter Escaping (Critical)

Kotlin string interpolation conflicts with PostgreSQL `$N` parameters. **ALWAYS** use `${'$'}`:

```kotlin
// ✅ CORRECT — use ${'$'}N everywhere
val sql = """UPDATE items SET status = ${'$'}1 WHERE id = ${'$'}2"""

// Dynamic indexing:
conditions.add("name = ${'$'}${idx}")
params.add(name); idx++
conditions.add("type = ${'$'}${idx}")
params.add(type); idx++
```

### API Response Convention

- **Paginated lists**: Always return `{ records: [...], meta: { total: N } }`
- **Single items**: Return the JSON object directly.
- **Errors**: `{ "error": "<message>" }`
- **Empty results**: `{ records: [], meta: { total: 0 } }`

### jOOQ Usage

- Prefer jOOQ type-safe queries (`ctx.insertInto().set().set().returning()`)
- Use `DatabaseConfig.sql(query)` + `DatabaseConfig.tuple(query)` to convert jOOQ queries to Vert.x prepared statements
- For INSERT with nullable columns, use `.set()` style (avoids jOOQ null-inlining bug)
- For dynamic UPDATE with conditional SET clauses, use raw SQL with `${'$'}` placeholders
- When a column doesn't exist in generated code yet, use `DSL.field("column_name", SQLDataType.JSONB)`

### ULID ID Generation

All IDs use ULID format (26 chars, Crockford Base32):
```kotlin
val id = Ulid.generate()  // e.g., "01J8Z4Q5W6V7B8N9M0K1L2P3Q4"
```

### Module Registration

Every new module must be:
1. Added to `settings.gradle.kts` as `"libs:<name>"`
2. Added as `implementation(project(":libs:<name>"))` in `apps/service/build.gradle.kts`
3. Route mounted in `Main.kt`: `apiRouter.route("/<module>/v1/*").subRouter(<Module>Routes.create(vertx, pool))`

## Build Commands

```bash
# Full clean build (use when lib sources changed)
./gradlew clean :apps:service:installDist --no-build-cache --rerun-tasks

# Quick compile check for a module
./gradlew :libs:<module>:compileKotlin

# jOOQ codegen (after schema changes)
./gradlew :libs:database:generateJooq

# Run
cd /path/to/service-vertx-kotlin && ./apps/service/build/install/service/bin/service

# Kill stale processes
ps aux | grep '[s]ervice/bin/service' | awk '{print $2}' | xargs kill -9

# View logs
cat logs/app.jsonl | grep 'route error' | tail -5
```

Gradle cache lives in `.gradle-cache/` (set via `GRADLE_USER_HOME`). AliYun Maven mirror is configured.

## Common Pitfalls

1. **JAR staleness**: `installDist` copies lib JARs to the distribution — these can be stale even when code compiles. Always use `clean` or `--rerun-tasks` when changing lib code.
2. **Port in use**: Two instances running. Kill all before restarting.
3. **Null inlining in jOOQ**: When a value is `null` in `.values()`, jOOQ inlines NULL in SQL and SKIPS it in `getBindValues()`, shifting all subsequent parameters. Omit nullable columns entirely when null.
4. **`as? Int` on VARCHAR**: Silently returns null. Use `.toString().toIntOrNull()` for VARCHAR columns containing numeric data.
5. **`OffsetDateTime` type**: Must use `tuple.addOffsetDateTime(p)`, NOT `tuple.addString(p.toString())`.
6. **10+ column select**: `ctx.select(c1..c10)` fails type inference. Use `ctx.select(listOf(c1..c10))` or raw SQL.
7. **Route path confusion**: Routes inside sub-routers use RELATIVE paths. `/module/v1/*` prefix is set in Main.kt.
8. **Service "stuck" at startup**: Flyway scanner produces DEBUG logs that look like hanging. Test with `curl`.
9. **ArrayList cannot be cast to JsonObject**: Use `b.getValue(key)` + safe casts instead of `b.getJsonObject(key)`.
10. **`column "updated_at" does not exist`**: Not all tables have `updated_at`. Check the jOOQ generated class first.
11. **`items` variable rename**: After renaming `items` to `records`, also change bare `items` used as final expression in `.map {}` blocks and string keys in `.put("records", items)`.

## Adding a New Module (Checklist)

1. Create `libs/<name>/build.gradle.kts` (standard: `java-library` plugin + deps)
2. Create `libs/<name>/src/main/kotlin/.../<Name>Routes.kt` + `<Name>Service.kt`
3. Add `include("libs:<name>")` to `settings.gradle.kts`
4. Add `implementation(project(":libs:<name>"))` to `apps/service/build.gradle.kts`
5. Import and mount in `Main.kt`
6. If new DB tables: add Flyway migration in `libs/database/src/main/resources/db/migration/` + run jOOQ codegen
7. Update this AGENTS.md and SKILLS.md

## Flyway Migration Naming

`V<next-version>__<description>.sql` (check `flyway_schema_history` for current version). Use idempotent DDL:
- `CREATE TABLE IF NOT EXISTS`
- `CREATE INDEX IF NOT EXISTS`
- `ALTER TABLE` wrapped in `DO $$ ... END $$` with existence checks

## Testing

No automated tests currently exist. Manual testing via `curl` against `localhost:8421/crate-api/<module>/v1/<endpoint>`. When adding test infrastructure, use Vert.x JUnit 5 integration.
