package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.OffsetDateTime

/**
 * 审计条目（054 F3）：**只含元数据，绝不含请求体**。
 *
 * @param actor      认证主体（`ctx["userId"]`）；异常路径可空
 * @param method     HTTP 方法（POST/PUT/PATCH/DELETE）
 * @param path       规范化路径（`/crate-api` + `normalizedPath()`）
 * @param statusCode 响应状态码（门禁 403 尝试同样入账）
 * @param resourceId `:id` 路径参数（弱关联，便于按资源回溯）
 * @param metadata   非 PHI 上下文（如 `platform_admin`）；**禁止写请求体**
 */
data class AuditEntry(
    val actor: String?,
    val method: String,
    val path: String,
    val statusCode: Int,
    val resourceId: String?,
    val metadata: JsonObject? = null,
    val occurredAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * 审计落库端口（054 F3）：中间件依赖此接口，便于单测用替身，不依赖具体存储。
 *
 * 实现必须**永不抛出**（返回失败 Future 由调用方 `log.warn`），不得阻断主请求。
 */
fun interface AuditRecorder {
    fun record(entry: AuditEntry): Future<Unit>
}

/**
 * 操作审计日志服务（054 F3，迁移 `V540`）。
 *
 * ## 职责
 * - [record]：落一条审计元数据（写方法由 `AuditMiddleware` 在响应结束时调用）。
 * - [list]：审计查询（**全系统第一条受门禁的读路由**，054 §11.2 Q8，受 `access:manage`）。
 *
 * ## 口径
 * - 只记元数据：`actor/method/path/status_code/resource_id/occurred_at/metadata`，**不落请求体**。
 * - 查询过滤：`actor` 精确、`method` 精确、`path` **前缀匹配**（传全路径即精确）、
 *   `from`/`to` 为 `occurred_at` 闭区间（ISO-8601 offset date-time）。
 * - 列表按 `occurred_at DESC, id DESC`；空结果返回 `records: []` 与 `total: 0`。
 * - 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），**本文件无任何 jOOQ 引用**。
 */
class AuditLogService(private val pool: Pool) : AuditRecorder {

    companion object {
        private const val COLUMNS =
            "id, actor, method, path, status_code, resource_id, occurred_at, metadata"

        private const val INSERT_SQL =
            "INSERT INTO healthcare.audit_logs " +
                "(id, actor, method, path, status_code, resource_id, occurred_at, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"

        private const val COUNT_SQL = "SELECT count(*) AS total FROM healthcare.audit_logs"

        /** 行 → 审计 JSON（054 §5.7 形状）。 */
        internal fun auditJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("actor", row.getString("actor"))
            .put("method", row.getString("method"))
            .put("path", row.getString("path"))
            .put("status_code", row.getInteger("status_code"))
            .put("resource_id", row.getString("resource_id"))
            .put("occurred_at", row.getOffsetDateTime("occurred_at")?.toString())
            .put("metadata", row.getJsonObject("metadata"))
    }

    /** 落一条审计元数据；由中间件在响应结束时调用（失败只告警，不阻断主请求）。 */
    override fun record(entry: AuditEntry): Future<Unit> {
        val tuple = Tuple.tuple()
        tuple.addString(Ulid.generate())
        tuple.addValue(entry.actor)
        tuple.addString(entry.method)
        tuple.addString(entry.path)
        tuple.addInteger(entry.statusCode)
        tuple.addValue(entry.resourceId)
        tuple.addOffsetDateTime(entry.occurredAt)
        tuple.addValue(entry.metadata)
        return pool.preparedQuery(INSERT_SQL).execute(tuple).map { Unit }
    }

    /**
     * 审计查询：`actor` 精确、`method` 精确、`path` 前缀、`from`/`to` 闭区间。
     *
     * 参数非法（时间格式）→ `IllegalArgumentException`（路由 400）。
     */
    fun list(
        actor: String? = null,
        method: String? = null,
        path: String? = null,
        from: String? = null,
        to: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        actor?.trim()?.takeIf(String::isNotBlank)?.let {
            conditions.add("actor = \$${values.size + 1}")
            values.add(it)
        }
        method?.trim()?.takeIf(String::isNotBlank)?.let {
            conditions.add("method = \$${values.size + 1}")
            values.add(it.uppercase())
        }
        path?.trim()?.takeIf(String::isNotBlank)?.let {
            conditions.add("path LIKE \$${values.size + 1} || '%'")
            values.add(it)
        }
        val fromValue = try {
            parseInstant(from, "from")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val toValue = try {
            parseInstant(to, "to")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        fromValue?.let {
            conditions.add("occurred_at >= \$${values.size + 1}")
            values.add(it)
        }
        toValue?.let {
            conditions.add("occurred_at <= \$${values.size + 1}")
            values.add(it)
        }
        if (fromValue != null && toValue != null && fromValue.isAfter(toValue)) {
            return Future.failedFuture(IllegalArgumentException("from must not be after to"))
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT $COLUMNS FROM healthcare.audit_logs" + where +
            " ORDER BY occurred_at DESC, id DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::auditJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    private fun parseInstant(raw: String?, label: String): OffsetDateTime? {
        val text = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        return try {
            OffsetDateTime.parse(text)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$label must be an ISO-8601 offset date-time")
        }
    }

    private fun tupleOf(values: List<Any>): Tuple {
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        return tuple
    }
}
