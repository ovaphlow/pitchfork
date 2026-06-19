# Inventories — 物资档案与批次 API 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `materials`（物资档案 CRUD）和 `lots`（批次创建/查询）的后端 API 接口。

**Architecture:** 单一 Gradle module `libs:inventories`，内部拆分为 `MaterialRoutes/Service` + `LotRoutes/Service` 两组文件。所有表放 `public` schema 以匹配现有 jOOQ 模式。新增 V10 Flyway 迁移，添加表到 jOOQ codegen include 列表。

**Tech Stack:** Vert.x + jOOQ + PostgreSQL + Kotlin

---

### Task 1: Flyway 迁移 — 创建 materials + lots 表

**Files:**
- Create: `libs/database/src/main/resources/db/migration/V10__create_inventory_tables.sql`

- [ ] **Step 1: 创建迁移文件**

```sql
-- 物资主表
CREATE TABLE IF NOT EXISTS materials
(
    id                   VARCHAR(32) PRIMARY KEY,
    code                 VARCHAR NOT NULL UNIQUE,
    name                 VARCHAR NOT NULL,
    category             VARCHAR NOT NULL,
    spec                 VARCHAR,
    package_unit         VARCHAR NOT NULL,
    split_unit           VARCHAR,
    split_ratio          NUMERIC(10, 4),
    enable_batch_control BOOLEAN     DEFAULT FALSE,
    cost_method          VARCHAR     DEFAULT 'MOVING_AVG'
        CHECK (cost_method IN ('MOVING_AVG', 'FIFO')),
    metadata             JSONB,
    status               VARCHAR     DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISCONTINUED')),
    created_at           TIMESTAMPTZ DEFAULT now()
);

-- 批次表
CREATE TABLE IF NOT EXISTS lots
(
    id              VARCHAR(32) PRIMARY KEY,
    material_id     VARCHAR(32) NOT NULL REFERENCES materials (id),
    batch_no        VARCHAR     NOT NULL,
    production_date DATE,
    expiry_date     DATE,
    manufacturer    VARCHAR,
    supplier        VARCHAR,
    metadata        JSONB,
    UNIQUE (material_id, batch_no)
);
```

- [ ] **Step 2: 提交**

```bash
git add libs/database/src/main/resources/db/migration/V10__create_inventory_tables.sql
git commit -m "feat: add V10 migration for materials and lots tables"
```

---

### Task 2: 更新 jOOQ codegen 配置并重新生成

**Files:**
- Modify: `libs/database/jooq-config.xml`

- [ ] **Step 1: 将 materials 和 lots 加入 jOOQ include 列表**

编辑 `jooq-config.xml`，在 `<includes>` 的管道字符串末尾追加 `|materials|lots`：

```xml
<includes>users|settings|messages|files|behaviors|knowledge_entries|knowledge_versions|knowledge_categories|knowledge_feedbacks|departments|employees|positions|skills|employee_skills|certificates|employee_certificates|courses|course_chapters|questions|exam_papers|training_assignments|learning_progress|exam_records|ai_qa_logs|faq_pairs|preventive_push_rules|device_qr_codes|offline_cache_policies|materials|lots</includes>
```

- [ ] **Step 2: 运行 jOOQ codegen**

```bash
./gradlew :libs:database:generateJooq
```

预期：生成 `libs/database/src/main/java/com/ovaphlow/crate/database/gen/public_/tables/Materials.java` 和 `Lots.java`。

- [ ] **Step 3: 提交**

```bash
git add libs/database/jooq-config.xml libs/database/src/main/java/com/ovaphlow/crate/database/gen/public_/tables/Materials.java libs/database/src/main/java/com/ovaphlow/crate/database/gen/public_/tables/Lots.java
git commit -m "feat: regenerate jOOQ codegen for materials and lots tables"
```

---

### Task 3: 创建模块脚手架 — build.gradle.kts + InventoriesRoutes.kt

**Files:**
- Create: `libs/inventories/build.gradle.kts`
- Create: `libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/InventoriesRoutes.kt`
- Create: `libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/package-info.txt` (不需要，直接建目录)

- [ ] **Step 1: 创建 build.gradle.kts**

```kotlin
plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":libs:database"))
    api(project(":libs:common"))
    api(libs.vertx.web)
    api(libs.jooq)
    implementation(libs.slf4j.api)
}
```

- [ ] **Step 2: 创建 InventoriesRoutes.kt**

```kotlin
package com.ovaphlow.crate.inventories

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object InventoriesRoutes {

    private val log = LoggerFactory.getLogger(InventoriesRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val mPool = pool

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "inventories"))
        }

        router.route("/materials/*").subRouter(MaterialRoutes.create(vertx, mPool))
        router.route("/lots/*").subRouter(LotRoutes.create(vertx, mPool))

        return router
    }

    internal fun body(ctx: RoutingContext): JsonObject =
        ctx.body().asJsonObject() ?: JsonObject()

    internal fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }

    internal fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("inventories route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add libs/inventories/build.gradle.kts libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/InventoriesRoutes.kt
git commit -m "feat: scaffold inventories module with top-level routes"
```

---

### Task 4: 实现 MaterialService

**Files:**
- Create: `libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/MaterialService.kt`

- [ ] **Step 1: 创建 MaterialService**

```kotlin
package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventory.tables.Materials
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL.count
import java.time.OffsetDateTime

class MaterialService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = Materials.MATERIALS

    // POST — 创建
    fun create(body: JsonObject): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val query = ctx.insertInto(t)
            .set(t.ID, id)
            .set(t.CODE, body.getString("code"))
            .set(t.NAME, body.getString("name"))
            .set(t.CATEGORY, body.getString("category"))
            .set(t.SPEC, body.getString("spec"))
            .set(t.PACKAGE_UNIT, body.getString("package_unit"))
            .set(t.SPLIT_UNIT, body.getString("split_unit"))
            .set(t.SPLIT_RATIO, body.getDouble("split_ratio")?.let { java.math.BigDecimal.valueOf(it) })
            .set(t.ENABLE_BATCH_CONTROL, body.getBoolean("enable_batch_control", false))
            .set(t.COST_METHOD, body.getString("cost_method", "MOVING_AVG"))
            .set(t.METADATA, body.getJsonObject("metadata")?.let { JSONB.valueOf(it.encode()) })
            .set(t.STATUS, body.getString("status", "ACTIVE"))
            .set(t.CREATED_AT, now)

        query.returning(t.ID, t.CODE, t.NAME, t.CATEGORY, t.SPEC,
            t.PACKAGE_UNIT, t.SPLIT_UNIT, t.SPLIT_RATIO, t.ENABLE_BATCH_CONTROL,
            t.COST_METHOD, t.METADATA, t.STATUS, t.CREATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> toJson(rows.iterator().next()) }
    }

    // GET — 列表
    fun list(
        code: String? = null,
        name: String? = null,
        category: String? = null,
        status: String? = null,
        enableBatchControl: Boolean? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()

        if (!code.isNullOrBlank()) conditions.add(t.CODE.eq(code))
        if (!name.isNullOrBlank()) conditions.add(t.NAME.like("%$name%"))
        if (!category.isNullOrBlank()) conditions.add(t.CATEGORY.eq(category))
        if (!status.isNullOrBlank()) conditions.add(t.STATUS.eq(status))
        if (enableBatchControl != null) conditions.add(t.ENABLE_BATCH_CONTROL.eq(enableBatchControl))

        val where = conditions.reduceOrNull { a, b -> a.and(b) } ?: org.jooq.impl.DSL.trueCondition()

        val countQuery = ctx.select(count().`as`("total")).from(t).where(where)
        val dataQuery = ctx.select(
            t.ID, t.CODE, t.NAME, t.CATEGORY, t.SPEC,
            t.PACKAGE_UNIT, t.SPLIT_UNIT, t.SPLIT_RATIO, t.ENABLE_BATCH_CONTROL,
            t.COST_METHOD, t.METADATA, t.STATUS, t.CREATED_AT
        ).from(t).where(where).orderBy(t.CREATED_AT.desc()).limit(limit).offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) records.add(toJson(row))
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    // GET — 单条
    fun get(id: String): Future<JsonObject> {
        val query = ctx.select(
            t.ID, t.CODE, t.NAME, t.CATEGORY, t.SPEC,
            t.PACKAGE_UNIT, t.SPLIT_UNIT, t.SPLIT_RATIO, t.ENABLE_BATCH_CONTROL,
            t.COST_METHOD, t.METADATA, t.STATUS, t.CREATED_AT
        ).from(t).where(t.ID.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val row = rows.iterator()
                if (row.hasNext()) toJson(row.next())
                else throw NotFoundException("material not found: $id")
            }
    }

    // PUT — 部分更新
    fun update(id: String, body: JsonObject): Future<JsonObject> {
        // 预先检查物资是否存在
        val checkQuery = ctx.select(t.ID).from(t).where(t.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (!rows.iterator().hasNext()) {
                    throw NotFoundException("material not found: $id")
                }
                doUpdate(id, body)
            }
    }

    private fun doUpdate(id: String, body: JsonObject): Future<JsonObject> {
        // 使用动态 SQL（SET 子句运行时变化）
        val params = mutableListOf<Any>()
        val setClauses = mutableListOf<String>()
        var idx = 1

        fun addIntParam(key: String, col: String) {
            body.getValue(key)?.let { v ->
                setClauses.add("$col = \${'$'}$idx"); idx++
                when (v) {
                    is String -> params.add(v)
                    is Number -> params.add(v)
                    else -> params.add(v.toString())
                }
            }
        }

        // code 不可修改，排除
        if (body.containsKey("name")) { setClauses.add("name = \${'$'}$idx"); params.add(body.getString("name")); idx++ }
        if (body.containsKey("category")) { setClauses.add("category = \${'$'}$idx"); params.add(body.getString("category")); idx++ }
        if (body.containsKey("spec")) { setClauses.add("spec = \${'$'}$idx"); params.add(body.getString("spec")); idx++ }
        if (body.containsKey("package_unit")) { setClauses.add("package_unit = \${'$'}$idx"); params.add(body.getString("package_unit")); idx++ }
        if (body.containsKey("split_unit")) { setClauses.add("split_unit = \${'$'}$idx"); params.add(body.getString("split_unit")); idx++ }
        if (body.containsKey("split_ratio")) { setClauses.add("split_ratio = \${'$'}$idx"); params.add(body.getBigDecimal("split_ratio")); idx++ }
        if (body.containsKey("enable_batch_control")) { setClauses.add("enable_batch_control = \${'$'}$idx"); params.add(body.getBoolean("enable_batch_control")); idx++ }
        if (body.containsKey("cost_method")) { setClauses.add("cost_method = \${'$'}$idx"); params.add(body.getString("cost_method")); idx++ }
        if (body.containsKey("metadata")) { setClauses.add("metadata = \${'$'}$idx"); params.add(body.getJsonObject("metadata").encode()); idx++ }
        if (body.containsKey("status")) { setClauses.add("status = \${'$'}$idx"); params.add(body.getString("status")); idx++ }

        if (setClauses.isEmpty()) {
            // 没有要更新的字段，直接返回当前数据
            return get(id)
        }


        val sql = "UPDATE materials SET ${setClauses.joinToString(", ")} WHERE id = \${'$'}$idx RETURNING id, code, name, category, spec, package_unit, split_unit, split_ratio, enable_batch_control, cost_method, metadata, status, created_at"
        params.add(id)

        return pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.wrapping(params.toTypedArray()))
            .map { rows -> toJson(rows.iterator().next()) }
    }

    // DELETE — 物理删除
    fun delete(id: String): Future<Void?> {
        val query = ctx.deleteFrom(t).where(t.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null as Void? }
    }

    private fun toJson(row: Row): JsonObject {
        val obj = JsonObject()
        obj.put("id", row.getString("id"))
        obj.put("code", row.getString("code"))
        obj.put("name", row.getString("name"))
        obj.put("category", row.getString("category"))
        obj.put("spec", row.getString("spec"))
        obj.put("package_unit", row.getString("package_unit"))
        obj.put("split_unit", row.getString("split_unit"))
        obj.put("split_ratio", row.getBigDecimal("split_ratio")?.toDouble())
        obj.put("enable_batch_control", row.getBoolean("enable_batch_control"))
        obj.put("cost_method", row.getString("cost_method"))
        val metadata = row.getJsonObject("metadata")
        if (metadata != null) obj.put("metadata", metadata)
        obj.put("status", row.getString("status"))
        obj.put("created_at", row.getOffsetDateTime("created_at")?.toString())
        return obj
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/MaterialService.kt
git commit -m "feat: implement MaterialService with CRUD operations"
```

---

### Task 5: 实现 MaterialRoutes

**Files:**
- Create: `libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/MaterialRoutes.kt`

- [ ] **Step 1: 创建 MaterialRoutes**

```kotlin
package com.ovaphlow.crate.inventories

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.Pool

object MaterialRoutes {

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = MaterialService(pool)

        router.post("/").handler { ctx ->
            val b = InventoriesRoutes.body(ctx)
            val required = listOf("code", "name", "category", "package_unit")
            val missing = required.filter { b.getString(it).isNullOrBlank() }
            if (missing.isNotEmpty()) {
                InventoriesRoutes.respond(ctx, 400, "missing required fields: $missing"); return@handler
            }
            service.create(b)
                .onSuccess { ctx.response().setStatusCode(201).end(it.encode()) }
                .onFailure {
                    val msg = it.message ?: ""
                    if (msg.contains("unique") || msg.contains("duplicate")) {
                        InventoriesRoutes.respond(ctx, 409, "code already exists")
                    } else {
                        InventoriesRoutes.respondError(ctx, it)
                    }
                }
        }

        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                code = params.getParam("code"),
                name = params.getParam("name"),
                category = params.getParam("category"),
                status = params.getParam("status"),
                enableBatchControl = params.getParam("enable_batch_control")?.toBooleanStrictOrNull(),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { InventoriesRoutes.respondError(ctx, it) }
        }

        router.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) InventoriesRoutes.respond(ctx, 404, it.message)
                    else InventoriesRoutes.respondError(ctx, it)
                }
        }

        router.put("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            val b = InventoriesRoutes.body(ctx)
            service.update(id, b)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) InventoriesRoutes.respond(ctx, 404, it.message)
                    else InventoriesRoutes.respondError(ctx, it)
                }
        }

        router.delete("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            service.delete(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure {
                    val msg = it.message ?: ""
                    if (msg.contains("foreign key") || msg.contains("violates foreign")) {
                        InventoriesRoutes.respond(ctx, 409, "material has dependent records, cannot delete")
                    } else {
                        InventoriesRoutes.respondError(ctx, it)
                    }
                }
        }

        return router
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/MaterialRoutes.kt
git commit -m "feat: implement MaterialRoutes with CRUD endpoints"
```

---

### Task 6: 实现 LotService

**Files:**
- Create: `libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/LotService.kt`

- [ ] **Step 1: 创建 LotService**

```kotlin
package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventory.tables.Lots
import com.ovaphlow.crate.database.gen.inventory.tables.Materials
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL.count
import java.time.LocalDate

class LotService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val l = Lots.LOTS
    private val m = Materials.MATERIALS

    // POST — 幂等创建
    fun create(body: JsonObject): Future<JsonObject> {
        val materialId = body.getString("material_id")
        val batchNo = body.getString("batch_no")

        // 1. 检查物资存在且 enable_batch_control = TRUE
        val checkMaterial = ctx.select(m.ENABLE_BATCH_CONTROL).from(m).where(m.ID.eq(materialId))
        return pool.preparedQuery(DatabaseConfig.sql(checkMaterial))
            .execute(DatabaseConfig.tuple(checkMaterial))
            .flatMap { rows ->
                val row = rows.iterator()
                if (!row.hasNext()) {
                    throw NotFoundException("material not found: $materialId")
                }
                if (row.getBoolean("enable_batch_control") != true) {
                    throw IllegalArgumentException("material does not enable batch control")
                }

                // 2. 检查是否已存在 (material_id, batch_no)
                val existingQuery = ctx.select(
                    l.ID, l.MATERIAL_ID, l.BATCH_NO, l.PRODUCTION_DATE,
                    l.EXPIRY_DATE, l.MANUFACTURER, l.SUPPLIER, l.METADATA
                ).from(l).where(l.MATERIAL_ID.eq(materialId).and(l.BATCH_NO.eq(batchNo)))

                pool.preparedQuery(DatabaseConfig.sql(existingQuery))
                    .execute(DatabaseConfig.tuple(existingQuery))
                    .flatMap { existingRows ->
                        val iter = existingRows.iterator()
                        if (iter.hasNext()) {
                            // 已存在，返回已有记录
                            Future.succeededFuture(toJson(iter.next()))
                        } else {
                            // 3. 创建新批次
                            doCreate(body)
                        }
                    }
            }
    }

    private fun doCreate(body: JsonObject): Future<JsonObject> {
        val id = Ulid.generate()

        val query = ctx.insertInto(l)
            .set(l.ID, id)
            .set(l.MATERIAL_ID, body.getString("material_id"))
            .set(l.BATCH_NO, body.getString("batch_no"))
            .set(l.PRODUCTION_DATE, body.getString("production_date")?.let { LocalDate.parse(it) })
            .set(l.EXPIRY_DATE, body.getString("expiry_date")?.let { LocalDate.parse(it) })
            .set(l.MANUFACTURER, body.getString("manufacturer"))
            .set(l.SUPPLIER, body.getString("supplier"))
            .set(l.METADATA, body.getJsonObject("metadata")?.let { JSONB.valueOf(it.encode()) })

        query.returning(l.ID, l.MATERIAL_ID, l.BATCH_NO, l.PRODUCTION_DATE,
            l.EXPIRY_DATE, l.MANUFACTURER, l.SUPPLIER, l.METADATA)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> toJson(rows.iterator().next()) }
    }

    // GET — 列表
    fun list(
        materialId: String? = null,
        batchNo: String? = null,
        expiryBefore: String? = null,
        expiryAfter: String? = null,
        manufacturer: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()

        if (!materialId.isNullOrBlank()) conditions.add(l.MATERIAL_ID.eq(materialId))
        if (!batchNo.isNullOrBlank()) conditions.add(l.BATCH_NO.like("%$batchNo%"))
        if (!expiryBefore.isNullOrBlank()) conditions.add(l.EXPIRY_DATE.le(LocalDate.parse(expiryBefore)))
        if (!expiryAfter.isNullOrBlank()) conditions.add(l.EXPIRY_DATE.ge(LocalDate.parse(expiryAfter)))
        if (!manufacturer.isNullOrBlank()) conditions.add(l.MANUFACTURER.eq(manufacturer))

        val where = conditions.reduceOrNull { a, b -> a.and(b) } ?: org.jooq.impl.DSL.trueCondition()

        val countQuery = ctx.select(count().`as`("total")).from(l).where(where)
        val dataQuery = ctx.select(
            l.ID, l.MATERIAL_ID, l.BATCH_NO, l.PRODUCTION_DATE,
            l.EXPIRY_DATE, l.MANUFACTURER, l.SUPPLIER, l.METADATA
        ).from(l).where(where).orderBy(l.EXPIRY_DATE.asc().nullsLast()).limit(limit).offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) records.add(toJson(row))
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    // GET — 单条
    fun get(id: String): Future<JsonObject> {
        val query = ctx.select(
            l.ID, l.MATERIAL_ID, l.BATCH_NO, l.PRODUCTION_DATE,
            l.EXPIRY_DATE, l.MANUFACTURER, l.SUPPLIER, l.METADATA
        ).from(l).where(l.ID.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val row = rows.iterator()
                if (row.hasNext()) toJson(row.next())
                else throw NotFoundException("lot not found: $id")
            }
    }

    private fun toJson(row: Row): JsonObject {
        val obj = JsonObject()
        obj.put("id", row.getString("id"))
        obj.put("material_id", row.getString("material_id"))
        obj.put("batch_no", row.getString("batch_no"))
        obj.put("production_date", row.getLocalDate("production_date")?.toString())
        obj.put("expiry_date", row.getLocalDate("expiry_date")?.toString())
        obj.put("manufacturer", row.getString("manufacturer"))
        obj.put("supplier", row.getString("supplier"))
        val metadata = row.getJsonObject("metadata")
        if (metadata != null) obj.put("metadata", metadata)
        return obj
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/LotService.kt
git commit -m "feat: implement LotService with create (idempotent) and query"
```

---

### Task 7: 实现 LotRoutes

**Files:**
- Create: `libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/LotRoutes.kt`

- [ ] **Step 1: 创建 LotRoutes**

```kotlin
package com.ovaphlow.crate.inventories

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.Pool

object LotRoutes {

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = LotService(pool)

        router.post("/").handler { ctx ->
            val b = InventoriesRoutes.body(ctx)
            val missing = mutableListOf<String>()
            if (b.getString("material_id").isNullOrBlank()) missing.add("material_id")
            if (b.getString("batch_no").isNullOrBlank()) missing.add("batch_no")
            if (missing.isNotEmpty()) {
                InventoriesRoutes.respond(ctx, 400, "missing required fields: $missing"); return@handler
            }
            service.create(b)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> InventoriesRoutes.respond(ctx, 404, it.message)
                        is IllegalArgumentException -> InventoriesRoutes.respond(ctx, 400, it.message)
                        else -> InventoriesRoutes.respondError(ctx, it)
                    }
                }
        }

        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                materialId = params.getParam("material_id"),
                batchNo = params.getParam("batch_no"),
                expiryBefore = params.getParam("expiry_before"),
                expiryAfter = params.getParam("expiry_after"),
                manufacturer = params.getParam("manufacturer"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { InventoriesRoutes.respondError(ctx, it) }
        }

        router.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) InventoriesRoutes.respond(ctx, 404, it.message)
                    else InventoriesRoutes.respondError(ctx, it)
                }
        }

        return router
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add libs/inventories/src/main/kotlin/com/ovaphlow/crate/inventories/LotRoutes.kt
git commit -m "feat: implement LotRoutes with create and query endpoints"
```

---

### Task 8: 注册模块到项目

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `apps/service/build.gradle.kts`
- Modify: `apps/service/src/main/kotlin/com/ovaphlow/crate/service/Main.kt`
- Create: (dir entry) 模块目录已存在

- [ ] **Step 1: 在 settings.gradle.kts 中添加 `"libs:inventories"`**

在 `settings.gradle.kts` 的 `include()` 列表中，添加 `"libs:inventories"`（按字母序插入，放在 `"libs:files"` 之后、`"libs:knowledge"` 之前）：

```kotlin
include(
    "libs:auth",
    "libs:settings",
    "libs:files",
    "libs:inventories",
    "libs:permissions",
    "libs:database",
    "libs:knowledge",
    "libs:skills",
    "libs:common",
    "libs:messages",
    "libs:users",
    "libs:trainings",
    "libs:exams",
    "libs:onsite",
    "libs:logging",
    "libs:analytics",
    "apps:service"
)
```

- [ ] **Step 2: 在 apps/service/build.gradle.kts 中添加依赖**

在 `dependencies` 块中，按字母序添加（放在 `"libs:files"` 之后、`"libs:knowledge"` 之前）：

```kotlin
    implementation(project(":libs:inventories"))
```

- [ ] **Step 3: 在 Main.kt 中挂载路由**

添加 import：

```kotlin
import com.ovaphlow.crate.inventories.InventoriesRoutes
```

在路由挂载块中，按字母序添加（放在 files 路由之后、messages 路由之前）：

```kotlin
    apiRouter.route("/inventories/v1/*").subRouter(InventoriesRoutes.create(vertx, pool))
```

- [ ] **Step 4: 提交**

```bash
git add settings.gradle.kts apps/service/build.gradle.kts apps/service/src/main/kotlin/com/ovaphlow/crate/service/Main.kt
git commit -m "feat: register inventories module in project build and router"
```

---

### Task 9: 验证构建

- [ ] **Step 1: 编译验证**

```bash
./gradlew :libs:inventories:compileKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **Step 2: 完整编译**

```bash
./gradlew :apps:service:compileKotlin
```

预期：BUILD SUCCESSFUL

class NotFoundException(message: String) : Exception(message)
