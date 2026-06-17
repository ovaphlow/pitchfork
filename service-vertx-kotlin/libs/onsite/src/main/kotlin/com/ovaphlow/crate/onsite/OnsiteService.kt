package com.ovaphlow.crate.onsite

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.DeviceQrCodes
import com.ovaphlow.crate.database.gen.public_.tables.OfflineCachePolicies
import com.ovaphlow.crate.database.gen.public_.tables.KnowledgeEntries
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import org.jooq.impl.DSL.count

class OnsiteService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(OnsiteService::class.java)
    private val d = DeviceQrCodes.DEVICE_QR_CODES
    private val ocp = OfflineCachePolicies.OFFLINE_CACHE_POLICIES
    private val ke = KnowledgeEntries.KNOWLEDGE_ENTRIES

    // ==============================
    // Devices (QR codes)
    // ==============================

    fun listDevices(
        search: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()

        if (!search.isNullOrBlank()) {
            conditions.add(d.DEVICE_ID.likeIgnoreCase("%$search%").or(d.CODE.likeIgnoreCase("%$search%")))
        }

        val countQuery = ctx.select(count().`as`("total")).from(d).where(conditions)
        val dataQuery = ctx.select(d.ID, d.DEVICE_ID, d.CODE, d.LINKED_KNOWLEDGE_IDS, d.OFFLINE_CACHE_CONFIG, d.CREATED_AT)
            .from(d)
            .where(conditions)
            .orderBy(d.CREATED_AT.desc())
            .limit(limit).offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) {
                            records.add(deviceToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createDevice(
        deviceId: String,
        code: String,
        linkedKnowledgeIds: List<String> = emptyList(),
        offlineCacheConfig: JsonObject = JsonObject()
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val query = ctx.insertInto(d)
            .columns(d.ID, d.DEVICE_ID, d.CODE, d.LINKED_KNOWLEDGE_IDS, d.OFFLINE_CACHE_CONFIG, d.CREATED_AT)
            .values(id, deviceId, code, linkedKnowledgeIds.toTypedArray(), JSONB.valueOf(offlineCacheConfig.encode()), now)
            .returning(d.ID, d.DEVICE_ID, d.CODE, d.LINKED_KNOWLEDGE_IDS, d.OFFLINE_CACHE_CONFIG, d.CREATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> deviceToJson(rows.iterator().next()) }
    }

    fun getDevice(id: String): Future<JsonObject> {
        val query = ctx.select(d.ID, d.DEVICE_ID, d.CODE, d.LINKED_KNOWLEDGE_IDS, d.OFFLINE_CACHE_CONFIG, d.CREATED_AT)
            .from(d)
            .where(d.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("device not found"))
                else Future.succeededFuture(deviceToJson(rows.iterator().next()))
            }
    }

    fun updateDevice(
        id: String,
        deviceId: String? = null,
        code: String? = null,
        linkedKnowledgeIds: List<String>? = null,
        offlineCacheConfig: JsonObject? = null
    ): Future<JsonObject> {
        return getDeviceRaw(id).flatMap { existing: JsonObject ->
            val setClauses = mutableListOf<String>()
            val params = mutableListOf<Any?>()
            var idx = 1

            if (deviceId != null) { setClauses.add("device_id = \${'$'}${idx}"); params.add(deviceId); idx++ }
            if (code != null) { setClauses.add("code = \${'$'}${idx}"); params.add(code); idx++ }
            if (linkedKnowledgeIds != null) { setClauses.add("linked_knowledge_ids = \${'$'}${idx}"); params.add(linkedKnowledgeIds.toTypedArray()); idx++ }
            if (offlineCacheConfig != null) { setClauses.add("offline_cache_config = \${'$'}${idx}::jsonb"); params.add(offlineCacheConfig.encode()); idx++ }

            if (setClauses.isEmpty()) return@flatMap Future.succeededFuture(existing)

            // DeviceQrCodes has no UPDATED_AT column, so we don't set it

            val sql = """UPDATE device_qr_codes
                         SET ${setClauses.joinToString(", ")}
                         WHERE id = \${'$'}${idx}
                         RETURNING id, device_id, code, linked_knowledge_ids, offline_cache_config, created_at""".trimIndent()
            params.add(id)

            val tuple = Tuple.tuple()
            for (p in params) {
                when (p) {
                    is String -> tuple.addString(p)
                    is Array<*> -> tuple.addValue(p)
                    else -> tuple.addValue(p)
                }
            }

            pool.preparedQuery(sql)
                .execute(tuple)
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("device not found"))
                    else Future.succeededFuture(deviceToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteDevice(id: String): Future<Void> {
        val query = ctx.deleteFrom(d).where(d.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null as Void? }
    }

    fun scanDevice(code: String): Future<JsonObject> {
        val qDevice = ctx.select(d.ID, d.DEVICE_ID, d.CODE, d.LINKED_KNOWLEDGE_IDS, d.OFFLINE_CACHE_CONFIG, d.CREATED_AT)
            .from(d)
            .where(d.CODE.eq(code))
        return pool.preparedQuery(DatabaseConfig.sql(qDevice))
            .execute(DatabaseConfig.tuple(qDevice))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("device not found for code: $code"))
                else {
                    val device = deviceToJson(rows.iterator().next())
                    val linkedIds = device.getJsonArray("linked_knowledge_ids")?.map { it.toString() } ?: emptyList()
                    if (linkedIds.isEmpty()) {
                        Future.succeededFuture(JsonObject().put("device", device).put("knowledge", JsonArray()))
                    } else {
                        // Use direct SQL for IN clause since KnowledgeEntries jOOQ field for "extra" column is named METADATA in generated code
                        val placeholders = linkedIds.mapIndexed { i, _ -> "\${${i + 1}}" }.joinToString(", ")
                        val knowledgeSql = """
                            SELECT id, title, type, status, tags, extra
                            FROM knowledge_entries
                            WHERE id IN ($placeholders)
                        """.trimIndent()
                        val knowledgeTuple = Tuple.tuple()
                        for (lid in linkedIds) {
                            knowledgeTuple.addString(lid)
                        }
                        pool.preparedQuery(knowledgeSql)
                            .execute(knowledgeTuple)
                            .map { kRows ->
                                val knowledge = JsonArray()
                                for (row in kRows) {
                                    knowledge.add(JsonObject()
                                        .put("id", row.getValue("id")?.toString())
                                        .put("title", row.getValue("title")?.toString())
                                        .put("type", row.getValue("type")?.toString())
                                        .put("status", row.getValue("status")?.toString())
                                        .put("tags", arrayToJsonArray(row.getValue("tags")))
                                        .put("extra", row.getValue("extra")?.let { JsonObject(it.toString()) }))
                                }
                                JsonObject().put("device", device).put("knowledge", knowledge)
                            }
                    }
                }
            }
    }

    // ==============================
    // Cache Policies
    // ==============================

    fun listCachePolicies(): Future<JsonObject> {
        val query = ctx.select(ocp.ID, ocp.POSITION_ID, ocp.CACHE_SIZE_LIMIT_MB, ocp.INCLUDE_KNOWLEDGE_TYPES, ocp.INCLUDE_RECENT_DAYS, ocp.EXTRA, ocp.CREATED_AT, ocp.UPDATED_AT)
            .from(ocp)
            .orderBy(ocp.CREATED_AT.desc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val records = JsonArray()
                for (row in rows) {
                    records.add(cachePolicyToJson(row))
                }
                JsonObject().put("records", records)
            }
    }

    fun createCachePolicy(
        positionId: String,
        cacheSizeLimitMb: Int = 100,
        includeKnowledgeTypes: List<String> = emptyList(),
        includeRecentDays: Int = 30,
        extra: JsonObject = JsonObject()
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val query = ctx.insertInto(ocp)
            .columns(ocp.ID, ocp.POSITION_ID, ocp.CACHE_SIZE_LIMIT_MB, ocp.INCLUDE_KNOWLEDGE_TYPES, ocp.INCLUDE_RECENT_DAYS, ocp.EXTRA, ocp.CREATED_AT, ocp.UPDATED_AT)
            .values(id, positionId, cacheSizeLimitMb.toShort(), includeKnowledgeTypes.toTypedArray(), includeRecentDays.toShort(), JSONB.valueOf(extra.encode()), now, now)
            .returning(ocp.ID, ocp.POSITION_ID, ocp.CACHE_SIZE_LIMIT_MB, ocp.INCLUDE_KNOWLEDGE_TYPES, ocp.INCLUDE_RECENT_DAYS, ocp.EXTRA, ocp.CREATED_AT, ocp.UPDATED_AT)
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> cachePolicyToJson(rows.iterator().next()) }
    }

    fun getCachePolicy(id: String): Future<JsonObject> {
        val query = ctx.select(ocp.ID, ocp.POSITION_ID, ocp.CACHE_SIZE_LIMIT_MB, ocp.INCLUDE_KNOWLEDGE_TYPES, ocp.INCLUDE_RECENT_DAYS, ocp.EXTRA, ocp.CREATED_AT, ocp.UPDATED_AT)
            .from(ocp)
            .where(ocp.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("cache policy not found"))
                else Future.succeededFuture(cachePolicyToJson(rows.iterator().next()))
            }
    }

    fun updateCachePolicy(
        id: String,
        positionId: String? = null,
        cacheSizeLimitMb: Int? = null,
        includeKnowledgeTypes: List<String>? = null,
        includeRecentDays: Int? = null,
        extra: JsonObject? = null
    ): Future<JsonObject> {
        return getCachePolicyRaw(id).flatMap { existing: JsonObject ->
            val setClauses = mutableListOf<String>()
            val params = mutableListOf<Any?>()
            var idx = 1

            if (positionId != null) { setClauses.add("position_id = \${'$'}${idx}"); params.add(positionId); idx++ }
            if (cacheSizeLimitMb != null) { setClauses.add("cache_size_limit_mb = \${'$'}${idx}"); params.add(cacheSizeLimitMb); idx++ }
            if (includeKnowledgeTypes != null) { setClauses.add("include_knowledge_types = \${'$'}${idx}"); params.add(includeKnowledgeTypes.toTypedArray()); idx++ }
            if (includeRecentDays != null) { setClauses.add("include_recent_days = \${'$'}${idx}"); params.add(includeRecentDays); idx++ }
            if (extra != null) { setClauses.add("extra = \${'$'}${idx}::jsonb"); params.add(extra.encode()); idx++ }

            if (setClauses.isEmpty()) return@flatMap Future.succeededFuture(existing)

            setClauses.add("updated_at = \${'$'}${idx}"); params.add(OffsetDateTime.now()); idx++

            val sql = """UPDATE offline_cache_policies
                         SET ${setClauses.joinToString(", ")}
                         WHERE id = \${'$'}${idx}
                         RETURNING id, position_id, cache_size_limit_mb, include_knowledge_types, include_recent_days, extra, created_at, updated_at""".trimIndent()
            params.add(id)

            val tuple = Tuple.tuple()
            for (p in params) {
                when (p) {
                    is String -> tuple.addString(p)
                    is Int -> tuple.addInteger(p)
                    is OffsetDateTime -> tuple.addOffsetDateTime(p)
                    is Array<*> -> tuple.addValue(p)
                    else -> tuple.addValue(p)
                }
            }

            pool.preparedQuery(sql)
                .execute(tuple)
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("cache policy not found"))
                    else Future.succeededFuture(cachePolicyToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteCachePolicy(id: String): Future<Void> {
        val query = ctx.deleteFrom(ocp).where(ocp.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null as Void? }
    }

    // ==============================
    // Helpers
    // ==============================

    private fun getDeviceRaw(id: String): Future<JsonObject> {
        val query = ctx.select(d.ID, d.DEVICE_ID, d.CODE, d.LINKED_KNOWLEDGE_IDS, d.OFFLINE_CACHE_CONFIG, d.CREATED_AT)
            .from(d)
            .where(d.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("device not found"))
                else Future.succeededFuture(deviceToJson(rows.iterator().next()))
            }
    }

    private fun getCachePolicyRaw(id: String): Future<JsonObject> {
        val query = ctx.select(ocp.ID, ocp.POSITION_ID, ocp.CACHE_SIZE_LIMIT_MB, ocp.INCLUDE_KNOWLEDGE_TYPES, ocp.INCLUDE_RECENT_DAYS, ocp.EXTRA, ocp.CREATED_AT, ocp.UPDATED_AT)
            .from(ocp)
            .where(ocp.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("cache policy not found"))
                else Future.succeededFuture(cachePolicyToJson(rows.iterator().next()))
            }
    }

    companion object {
        fun deviceToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("device_id", row.getValue("device_id")?.toString())
                .put("code", row.getValue("code")?.toString())
                .put("linked_knowledge_ids", arrayToJsonArray(row.getValue("linked_knowledge_ids")))
                .put("offline_cache_config", row.getValue("offline_cache_config")?.let { JsonObject(it.toString()) })
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }

        fun cachePolicyToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("position_id", row.getValue("position_id")?.toString())
                .put("cache_size_limit_mb", row.getValue("cache_size_limit_mb")?.let { (it as Number).toInt() })
                .put("include_knowledge_types", arrayToJsonArray(row.getValue("include_knowledge_types")))
                .put("include_recent_days", row.getValue("include_recent_days")?.let { (it as Number).toInt() })
                .put("extra", row.getValue("extra")?.let { JsonObject(it.toString()) })
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }

        private fun arrayToJsonArray(value: Any?): JsonArray {
            return when (value) {
                is Array<*> -> {
                    val arr = JsonArray()
                    for (item in value) {
                        arr.add(item?.toString() ?: "")
                    }
                    arr
                }
                is List<*> -> {
                    val arr = JsonArray()
                    for (item in value) {
                        arr.add(item?.toString() ?: "")
                    }
                    arr
                }
                is String -> {
                    val cleaned = value.removeSurrounding("{", "}")
                    val arr = JsonArray()
                    if (cleaned.isNotBlank()) {
                        for (s in cleaned.split(",")) {
                            arr.add(s.trim())
                        }
                    }
                    arr
                }
                else -> JsonArray()
            }
        }
    }
}

class NotFoundException(message: String) : Exception(message)
