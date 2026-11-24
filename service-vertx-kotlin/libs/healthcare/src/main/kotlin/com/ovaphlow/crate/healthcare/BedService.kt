package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.OffsetDateTime

/**
 * 床位主数据服务（030 §4.5 W8）。
 *
 * 业务规则（服务端强制）：
 *  1. `department`/`ward` 必填、trim 后非空、≤50 字；`label`/`remark` 可选（`remark` ≤500 字）。
 *  2. 状态白名单 启用/停用（中文值，默认 启用）；启用/停用只能走 `updateBedStatus`，
 *     `updateBed` 不得改动状态。
 *  3. 启用态业务键唯一：同一 `(department, ward)` 至多一条启用项，重复 →
 *     `ConflictException`(409) `bed already exists: <department>/<ward>`；
 *     DB 部分唯一索引 `uq_beds_enabled_identity` 的 23505 兜底同样转 409。
 *  4. 删除：该 `(department, ward)` 存在 `ACTIVE` 的 `ELDERLY_CARE` 入住 →
 *     409 `bed is occupied by an active admission`；不存在 → 404。
 *  5. 列表按 `created_at DESC, id DESC` 倒序分页，返回 `{records, meta:{total}}`；
 *     空列表 `records: []` 且 `total: 0`。
 *
 * 030 D4：床位只是入住表单的候选来源（前端 datalist），
 * `encounters.department/ward` 仍是占用事实来源，本服务不写入/不改写入住记录。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class BedService(
    private val pool: Pool,
) {
    companion object {
        const val STATUS_ENABLED = "启用"
        const val STATUS_DISABLED = "停用"

        /** 状态白名单：启用/停用（中文值） */
        val statuses = setOf(STATUS_ENABLED, STATUS_DISABLED)

        /** department/ward 上限：50 字 */
        const val MAX_IDENTITY_LENGTH = 50

        /** remark 上限：500 字 */
        const val MAX_REMARK_LENGTH = 500

        /** 写白名单：id/status/created_at/updated_at 由服务端管控 */
        private val createKeys = setOf("department", "ward", "label", "remark")
        private val updateKeys = setOf("department", "ward", "label", "remark")
        private val statusKeys = setOf("status")

        private const val COLUMNS =
            "id, department, ward, label, status, remark, created_at, updated_at"

        private const val SELECT_BY_ID_SQL =
            "SELECT $COLUMNS FROM healthcare.beds WHERE id = \$1"

        private const val INSERT_SQL =
            "INSERT INTO healthcare.beds " +
                "(id, department, ward, status, created_at, updated_at, label, remark) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"

        /** 全量更新（label/remark 缺失即置空，与 setNull 语义一致）；状态不在此改。 */
        private const val UPDATE_SQL =
            "UPDATE healthcare.beds SET department = \$2, ward = \$3, label = \$4, remark = \$5, " +
                "updated_at = \$6 WHERE id = \$1"

        private const val UPDATE_STATUS_SQL =
            "UPDATE healthcare.beds SET status = \$2, updated_at = \$3 WHERE id = \$1"

        private const val DELETE_SQL =
            "DELETE FROM healthcare.beds WHERE id = \$1"

        private const val OCCUPIED_SQL =
            "SELECT count(*) AS total FROM healthcare.encounters " +
                "WHERE encounter_type = \$1 AND status = \$2 AND department = \$3 AND ward = \$4"

        private fun recordJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("department", row.getString("department"))
                .put("ward", row.getString("ward"))
                .put("label", row.getString("label"))
                .put("status", row.getString("status"))
                .put("remark", row.getString("remark"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  创建
    // ========================================================================

    /** 创建床位：department/ward 必填且校验；状态默认 启用；重复启用键 409。 */
    fun createBed(body: JsonObject): Future<JsonObject> {
        val fields = try {
            validateFields(body, createKeys)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            ensureIdentityAvailable(connection, fields.department, fields.ward, null).compose {
                // 可选列缺省时不出现在列清单里（与既有 omit .set() 语义一致）
                val columns = mutableListOf(
                    "id", "department", "ward", "status", "created_at", "updated_at",
                )
                val args = mutableListOf<Any?>(
                    id, fields.department, fields.ward, STATUS_ENABLED, now, now,
                )
                fields.label?.let { columns += "label"; args += it }
                fields.remark?.let { columns += "remark"; args += it }
                val placeholders = args.indices.joinToString(", ") { "\$${it + 1}" }
                execute(
                    connection,
                    "INSERT INTO healthcare.beds (${columns.joinToString(", ")}) VALUES ($placeholders)",
                    Tuple.from(args),
                ).compose { updatedJson(connection, id) }
            }.recover { error -> recoverUniqueViolation(error, fields.department, fields.ward) }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    /** 床位列表：支持 department/ward/status 过滤，倒序分页，返回 {records, meta:{total}}。 */
    fun listBeds(
        department: String?,
        ward: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val filterArgs = mutableListOf<Any?>()
        fun bindFilter(value: Any?): String = "\$${filterArgs.size + 1}".also { filterArgs.add(value) }

        department?.trim()?.takeIf(String::isNotBlank)?.let { conditions += "department = ${bindFilter(it)}" }
        ward?.trim()?.takeIf(String::isNotBlank)?.let { conditions += "ward = ${bindFilter(it)}" }
        status?.trim()?.takeIf(String::isNotBlank)?.let { conditions += "status = ${bindFilter(it)}" }

        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val from = " FROM healthcare.beds$where"
        val countSql = "SELECT count(*) AS total$from"
        val dataSql = "SELECT $COLUMNS$from ORDER BY created_at DESC, id DESC " +
            "OFFSET \$${filterArgs.size + 1} ROWS FETCH NEXT \$${filterArgs.size + 2} ROWS ONLY"
        val dataArgs = Tuple.from(filterArgs + listOf<Any?>(limit, offset))

        return execute(pool, countSql, Tuple.from(filterArgs)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, dataArgs).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map(::recordJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 单条查询：不存在 404。 */
    fun getBed(id: String): Future<JsonObject> =
        execute(pool, SELECT_BY_ID_SQL, Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(recordJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("bed not found: $id"))
        }

    // ========================================================================
    //  更新
    // ========================================================================

    /** 全量更新床位字段（department/ward/label/remark）；状态只能走 PATCH /:id/status。 */
    fun updateBed(id: String, body: JsonObject): Future<JsonObject> {
        val fields = try {
            validateFields(body, updateKeys)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireBed(connection, id).compose { existing ->
                // 启用项改键（或原位）都要保证启用态业务键唯一；停用项不占用业务键
                val guard = if (existing.getString("status") == STATUS_ENABLED) {
                    ensureIdentityAvailable(connection, fields.department, fields.ward, id)
                } else {
                    Future.succeededFuture()
                }
                guard.compose {
                    execute(
                        connection,
                        UPDATE_SQL,
                        Tuple.of(id, fields.department, fields.ward, fields.label, fields.remark, now),
                    ).compose { updatedJson(connection, id) }
                }.recover { error -> recoverUniqueViolation(error, fields.department, fields.ward) }
            }
        }
    }

    // ========================================================================
    //  状态流转（启用/停用）
    // ========================================================================

    /** 启用/停用流转：非法状态值 400；不存在 404；启用时重复业务键 409。 */
    fun updateBedStatus(id: String, body: JsonObject): Future<JsonObject> {
        val status = try {
            validateStatus(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireBed(connection, id).compose { existing ->
                val department = existing.getString("department")
                val ward = existing.getString("ward")
                val guard = if (status == STATUS_ENABLED) {
                    ensureIdentityAvailable(connection, department, ward, id)
                } else {
                    Future.succeededFuture()
                }
                guard.compose {
                    execute(connection, UPDATE_STATUS_SQL, Tuple.of(id, status, now))
                        .compose { updatedJson(connection, id) }
                }.recover { error -> recoverUniqueViolation(error, department, ward) }
            }
        }
    }

    // ========================================================================
    //  删除
    // ========================================================================

    /** 删除床位：不存在 404；该 (department, ward) 仍有 ACTIVE 的 ELDERLY_CARE 入住 → 409。 */
    fun deleteBed(id: String): Future<JsonObject> =
        pool.withTransaction<JsonObject> { connection ->
            requireBed(connection, id).compose { existing ->
                ensureNotOccupied(
                    connection,
                    existing.getString("department"),
                    existing.getString("ward"),
                ).compose {
                    execute(connection, DELETE_SQL, Tuple.of(id)).compose { rows ->
                        if (rows.rowCount() == 1) {
                            Future.succeededFuture(JsonObject().put("id", id))
                        } else {
                            Future.failedFuture(HealthcareNotFoundException("bed not found: $id"))
                        }
                    }
                }
            }
        }

    // ========================================================================
    //  内部实现
    // ========================================================================

    private data class Fields(
        val department: String,
        val ward: String,
        val label: String?,
        val remark: String?,
    )

    private fun validateFields(body: JsonObject, allowed: Set<String>): Fields {
        rejectForbiddenKeys(body, allowed, "bed")
        return Fields(
            requiredText(body, "department"),
            requiredText(body, "ward"),
            optionalText(body, "label", null),
            optionalText(body, "remark", MAX_REMARK_LENGTH),
        )
    }

    private fun validateStatus(body: JsonObject): String {
        rejectForbiddenKeys(body, statusKeys, "status")
        val raw = body.getValue("status") ?: throw IllegalArgumentException("status is required")
        val status = raw as? String ?: throw IllegalArgumentException("status must be a string")
        if (status !in statuses) {
            throw IllegalArgumentException("status must be one of: ${statuses.joinToString(", ")}")
        }
        return status
    }

    /** 必填文本：trim 后非空且 ≤50 字。 */
    private fun requiredText(body: JsonObject, key: String): String {
        val raw = body.getValue(key) ?: throw IllegalArgumentException("$key is required")
        val value = raw as? String ?: throw IllegalArgumentException("$key must be a string")
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("$key must not be blank")
        if (trimmed.length > MAX_IDENTITY_LENGTH) {
            throw IllegalArgumentException("$key must not exceed $MAX_IDENTITY_LENGTH characters")
        }
        return trimmed
    }

    /** 可选文本：trim 后为空视为清空（null）；`maxLength` 非空时校验上限。 */
    private fun optionalText(body: JsonObject, key: String, maxLength: Int?): String? {
        val raw = body.getValue(key) ?: return null
        val value = raw as? String ?: throw IllegalArgumentException("$key must be a string")
        val trimmed = value.trim().takeIf(String::isNotBlank) ?: return null
        if (maxLength != null && trimmed.length > maxLength) {
            throw IllegalArgumentException("$key must not exceed $maxLength characters")
        }
        return trimmed
    }

    private fun rejectForbiddenKeys(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }

    /** 启用态业务键唯一：命中其它启用项 → 409（服务端检查；索引 23505 由 recover 兜底）。 */
    private fun ensureIdentityAvailable(
        client: SqlClient,
        department: String,
        ward: String,
        excludeId: String?,
    ): Future<Void> {
        val args = mutableListOf<Any?>(department, ward, STATUS_ENABLED)
        var sql = "SELECT count(*) AS total FROM healthcare.beds " +
            "WHERE department = \$1 AND ward = \$2 AND status = \$3"
        if (excludeId != null) {
            args.add(excludeId)
            sql += " AND id <> \$4"
        }
        return execute(client, sql, Tuple.from(args)).compose { rows ->
            val total = rows.iterator().next().getLong("total") ?: 0L
            if (total > 0) {
                Future.failedFuture(ConflictException("bed already exists: $department/$ward"))
            } else {
                Future.succeededFuture()
            }
        }
    }

    /**
     * 占用检查（030 §4.5）：该 `(department, ward)` 存在 `ACTIVE` 的 `ELDERLY_CARE` 入住 →
     * 409 `bed is occupied by an active admission`；按 029 口径 trim 后精确比较。
     */
    private fun ensureNotOccupied(client: SqlClient, department: String?, ward: String?): Future<Void> {
        val keyDepartment = department?.trim()?.takeIf(String::isNotBlank) ?: return Future.succeededFuture()
        val keyWard = ward?.trim()?.takeIf(String::isNotBlank) ?: return Future.succeededFuture()
        return execute(
            client,
            OCCUPIED_SQL,
            Tuple.of("ELDERLY_CARE", "ACTIVE", keyDepartment, keyWard),
        ).compose { rows ->
            val total = rows.iterator().next().getLong("total") ?: 0L
            if (total > 0) {
                Future.failedFuture(ConflictException("bed is occupied by an active admission"))
            } else {
                Future.succeededFuture()
            }
        }
    }

    /** 唯一索引兜底（并发竞态）：23505 / 约束名 → 409，不泄漏 500。 */
    private fun recoverUniqueViolation(error: Throwable, department: String, ward: String): Future<JsonObject> {
        val message = error.message ?: ""
        val isUniqueViolation = message.contains("uq_beds_enabled_identity") ||
            (error as? PgException)?.sqlState == "23505"
        return if (isUniqueViolation) {
            Future.failedFuture(ConflictException("bed already exists: $department/$ward"))
        } else {
            Future.failedFuture(error)
        }
    }

    /** 更新/状态流转/删除前确认存在：不存在 404。 */
    private fun requireBed(client: SqlClient, id: String): Future<Row> =
        execute(client, SELECT_BY_ID_SQL, Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("bed not found: $id"))
        }

    private fun updatedJson(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, SELECT_BY_ID_SQL, Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(recordJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("bed not found: $id"))
        }

    private fun execute(client: SqlClient, sql: String, args: Tuple): Future<RowSet<Row>> =
        client.preparedQuery(sql).execute(args)
}
