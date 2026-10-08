package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.isUniqueViolation
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/**
 * 儿保服务套餐与订阅服务（054 E1，迁移 `V536`）。
 *
 * ## 范围与边界（054 §3 Df / §8.5）
 * 儿保套餐定义与购买**独立于 `bills`**：本服务只读写 `healthcare.child_service_packages`
 * 与 `healthcare.child_package_subscriptions`，**不触碰** `bills` / `bill_items` / `payments` /
 * `settlements` / `encounters`，因此不放宽 `bills.encounter_id NOT NULL`，也不影响养老账期链。
 *
 * ## 付费状态（`pay_state`，读时派生，不落库；054 §5.8）
 * ```
 * amount > 0 且 used_amount >= amount   → 已用完
 * paid_amount >= amount                 → 已付
 * paid_amount > 0                       → 部分
 * 否则                                   → 未付
 * ```
 * 派生优先级 `已用完 > 已付 > 部分 > 未付`；`amount = 0` 的免费套餐视为 `已付`。
 * `paid_amount` 由收款流程（054 E2）累加、`used_amount` 由核销流程（054 E2）累加，
 * 本服务只保证初值 0 并按当前值派生状态。
 *
 * ## 其它规则
 * - 套餐定义 `status` = `启用 / 停用`（应用层白名单）；订阅 `status` = `启用 / 停用`，
 *   状态机 `启用 ⇄ 停用`，同状态重复提交视为非法流转（400）。
 * - 订阅 `amount` 为**购买时套餐价快照**（服务端从 `child_service_packages.price` 复制，
 *   客户端提交 `amount`/`paid_amount`/`used_amount`/`pay_state` 一律 400）。
 * - 订阅只服务 `person_type=儿童` 的患者；套餐必须存在且 `status=启用`。
 * - `operator` / `created_by` 一律取认证主体（042 口径），客户端不得提交。
 * - 金额全程 `BigDecimal`（禁止经 Double），入参最多 2 位小数，超出直接 400 不静默进位。
 * - 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），**本文件无任何 jOOQ 引用**。
 */
class ChildServicePackageService(private val pool: Pool) {

    companion object {
        /** 套餐状态白名单（054 §5.8）。 */
        val PACKAGE_STATUSES: Set<String> = linkedSetOf("启用", "停用")

        /** 订阅状态白名单（054 §5.8）。 */
        val SUBSCRIPTION_STATUSES: Set<String> = linkedSetOf("启用", "停用")

        /** 付费状态白名单（054 §5.8，读时派生）。 */
        const val PAY_STATE_UNPAID = "未付"
        const val PAY_STATE_PARTIAL = "部分"
        const val PAY_STATE_PAID = "已付"
        const val PAY_STATE_USED_UP = "已用完"

        /** 订阅创建时的固定初始状态。 */
        const val CREATE_STATUS = "启用"

        /**
         * 付费状态读时派生（纯函数，供单测与路由复用）。
         *
         * 入参由 DB `CHECK (>= 0)` 保证非负；`amount = 0` 视为无需收款 → `已付`。
         */
        fun derivePayState(amount: BigDecimal, paidAmount: BigDecimal, usedAmount: BigDecimal): String = when {
            amount.signum() > 0 && usedAmount >= amount -> PAY_STATE_USED_UP
            paidAmount >= amount -> PAY_STATE_PAID
            paidAmount.signum() > 0 -> PAY_STATE_PARTIAL
            else -> PAY_STATE_UNPAID
        }

        /** 订阅状态机（纯函数）：`启用 ⇄ 停用`；同状态/未知状态一律 `false`。 */
        fun canTransitionSubscription(from: String, to: String): Boolean = when (from) {
            "启用" -> to == "停用"
            "停用" -> to == "启用"
            else -> false
        }

        /** 套餐定义写白名单（`id`/`created_by`/时间戳由服务端管控）。 */
        private val PACKAGE_KEYS = setOf("code", "name", "price", "items", "status", "metadata")

        /** 订阅创建写白名单（`amount`/`paid_amount`/`used_amount`/`status` 由服务端派生）。 */
        private val SUBSCRIPTION_CREATE_KEYS = setOf("patient_id", "package_id", "purchased_at", "metadata")

        /** 订阅状态更新写白名单。 */
        private val SUBSCRIPTION_STATUS_KEYS = setOf("status", "metadata")

        private const val PACKAGE_COLUMNS =
            "id, code, name, price, items, status, metadata, created_by, created_at, updated_at"

        private const val SUBSCRIPTION_COLUMNS =
            "id, patient_id, package_id, package_code, package_name, purchased_at, amount, paid_amount, " +
                "used_amount, status, metadata, operator, created_at, updated_at"

        private const val PACKAGE_BY_ID_SQL =
            "SELECT $PACKAGE_COLUMNS FROM healthcare.child_service_packages WHERE id = \$1"

        private const val PACKAGE_CODE_TAKEN_SQL =
            "SELECT id FROM healthcare.child_service_packages WHERE code = \$1 AND id <> \$2"

        private const val INSERT_PACKAGE_SQL =
            "INSERT INTO healthcare.child_service_packages " +
                "(id, code, name, price, items, status, metadata, created_by, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10)"

        private const val UPDATE_PACKAGE_SQL =
            "UPDATE healthcare.child_service_packages SET code = \$2, name = \$3, price = \$4, items = \$5, " +
                "status = \$6, metadata = \$7, updated_at = \$8 WHERE id = \$1"

        private const val PACKAGE_COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.child_service_packages"

        private const val SUBSCRIPTION_BY_ID_SQL =
            "SELECT $SUBSCRIPTION_COLUMNS FROM healthcare.child_package_subscriptions WHERE id = \$1"

        private const val INSERT_SUBSCRIPTION_SQL =
            "INSERT INTO healthcare.child_package_subscriptions " +
                "(id, patient_id, package_id, package_code, package_name, purchased_at, amount, paid_amount, " +
                "used_amount, status, metadata, operator, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14)"

        private const val SUBSCRIPTION_LOCK_SQL =
            "SELECT status FROM healthcare.child_package_subscriptions WHERE id = \$1 FOR UPDATE"

        private const val SUBSCRIPTION_STATUS_SQL =
            "UPDATE healthcare.child_package_subscriptions SET status = \$2, metadata = \$3, updated_at = \$4 " +
                "WHERE id = \$1"

        private const val SUBSCRIPTION_COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.child_package_subscriptions"

        /** 儿童档案原生查询（不引入 jOOQ）。 */
        private const val PATIENT_SQL =
            "SELECT id, person_type FROM healthcare.patients WHERE id = \$1"

        /** 行 → 套餐 JSON（金额为 JSON number，与 054 §5.7 一致）。 */
        internal fun packageJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("code", row.getString("code"))
            .put("name", row.getString("name"))
            .put("price", row.getBigDecimal("price"))
            .put("items", row.getJsonArray("items") ?: JsonArray())
            .put("status", row.getString("status"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_by", row.getString("created_by"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        /** 行 → 订阅 JSON；`pay_state` 由 [derivePayState] 读时派生。 */
        internal fun subscriptionJson(row: Row): JsonObject {
            val amount = row.getBigDecimal("amount") ?: BigDecimal.ZERO
            val paid = row.getBigDecimal("paid_amount") ?: BigDecimal.ZERO
            val used = row.getBigDecimal("used_amount") ?: BigDecimal.ZERO
            return JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("package_id", row.getString("package_id"))
                .put("package_code", row.getString("package_code"))
                .put("package_name", row.getString("package_name"))
                .put("purchased_at", row.getOffsetDateTime("purchased_at")?.toString())
                .put("amount", amount)
                .put("paid_amount", paid)
                .put("used_amount", used)
                .put("status", row.getString("status"))
                .put("pay_state", derivePayState(amount, paid, used))
                .put("metadata", row.getJsonObject("metadata"))
                .put("operator", row.getString("operator"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
        }

        private fun pageJson(rows: Iterable<Row>, total: Long, mapper: (Row) -> JsonObject): JsonObject =
            JsonObject()
                .put("records", JsonArray(rows.map(mapper)))
                .put("meta", JsonObject().put("total", total))
    }

    // ========================================================================
    //  套餐定义
    // ========================================================================

    /** 创建套餐定义（`code` 业务唯一；`status` 缺省 `启用`）。 */
    fun createPackage(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parsePackageCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return ensureCodeAvailable(pool, parsed.code, excludeId = null).compose {
            val tuple = Tuple.tuple()
            tuple.addString(id)
            tuple.addString(parsed.code)
            tuple.addString(parsed.name)
            tuple.addValue(parsed.price)
            tuple.addValue(parsed.items)
            tuple.addString(parsed.status)
            tuple.addValue(parsed.metadata)
            tuple.addString(operator)
            tuple.addOffsetDateTime(now)
            tuple.addOffsetDateTime(now)
            pool.preparedQuery(INSERT_PACKAGE_SQL).execute(tuple)
                .compose { getPackage(id) }
                .mapUniqueViolation("uq_child_service_packages_code", "package code already exists: ${parsed.code}")
        }
    }

    /** 按 id 取套餐；不存在 → [HealthcareNotFoundException]（404）。 */
    fun getPackage(id: String): Future<JsonObject> =
        pool.preparedQuery(PACKAGE_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(packageJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("child service package not found: $id"))
        }

    /** 套餐列表（`status` 可选，白名单校验）；空结果也返回 `records: []` 与 `total: 0`。 */
    fun listPackages(status: String?, limit: Int, offset: Int): Future<JsonObject> {
        val filter = status?.trim()?.takeIf(String::isNotBlank)
        if (filter != null && filter !in PACKAGE_STATUSES) {
            return Future.failedFuture(IllegalArgumentException("invalid status: $filter"))
        }
        val where = if (filter == null) "" else " WHERE status = \$1"
        val countSql = PACKAGE_COUNT_SQL + where
        val listSql = "SELECT $PACKAGE_COLUMNS FROM healthcare.child_service_packages" + where +
            " ORDER BY created_at DESC, id DESC LIMIT \$${if (filter == null) 1 else 2} OFFSET \$${if (filter == null) 2 else 3}"
        val countTuple = if (filter == null) Tuple.tuple() else Tuple.of(filter)
        val listTuple = if (filter == null) {
            Tuple.of(limit, offset)
        } else {
            Tuple.of(filter, limit, offset)
        }
        return pool.preparedQuery(countSql).execute(countTuple).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(listTuple).map { rows ->
                pageJson(rows, total, Companion::packageJson)
            }
        }
    }

    /**
     * 修改套餐定义（PUT 语义：只覆盖请求体出现的字段；显式 `null` 清空 `metadata`）。
     *
     * 不存在 → [HealthcareNotFoundException]（404）；`code` 撞车 → [ConflictException]（409）。
     */
    fun updatePackage(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val request = try {
            rejectUnsupported(body, PACKAGE_KEYS, "child service package")
            body
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.preparedQuery(PACKAGE_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            val current = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("child service package not found: $id"),
                )
            val merged = try {
                mergePackage(current, request)
            } catch (error: IllegalArgumentException) {
                return@compose Future.failedFuture(error)
            }
            val codeChanged = merged.code != current.getString("code")
            val availability: Future<Unit> = if (codeChanged) {
                ensureCodeAvailable(pool, merged.code, excludeId = id)
            } else {
                Future.succeededFuture(Unit)
            }
            availability.compose {
                val tuple = Tuple.tuple()
                tuple.addString(id)
                tuple.addString(merged.code)
                tuple.addString(merged.name)
                tuple.addValue(merged.price)
                tuple.addValue(merged.items)
                tuple.addString(merged.status)
                tuple.addValue(merged.metadata)
                tuple.addOffsetDateTime(OffsetDateTime.now())
                pool.preparedQuery(UPDATE_PACKAGE_SQL).execute(tuple)
                    .compose { getPackage(id) }
                    .mapUniqueViolation("uq_child_service_packages_code", "package code already exists: ${merged.code}")
            }
        }
    }

    // ========================================================================
    //  套餐订阅
    // ========================================================================

    /**
     * 购买套餐：校验儿童档案 + 套餐可用 → 以套餐价快照落订阅（`paid_amount`/`used_amount` 初值 0）。
     *
     * 患者不存在 → 404；非儿童 / 套餐停用 / 参数非法 → 400；套餐不存在 → 404。
     */
    fun createSubscription(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parseSubscriptionCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            loadChild(connection, parsed.patientId).compose { _ ->
                connection.preparedQuery(PACKAGE_BY_ID_SQL).execute(Tuple.of(parsed.packageId))
                    .compose<JsonObject> { rows ->
                        val packageRow = rows.iterator().asSequence().firstOrNull()
                            ?: return@compose Future.failedFuture(
                                HealthcareNotFoundException("child service package not found: ${parsed.packageId}"),
                            )
                        val packageStatus = packageRow.getString("status")
                        if (packageStatus != "启用") {
                            return@compose Future.failedFuture(
                                IllegalArgumentException("package is not active: ${parsed.packageId} ($packageStatus)"),
                            )
                        }
                        val tuple = Tuple.tuple()
                        tuple.addString(id)
                        tuple.addString(parsed.patientId)
                        tuple.addString(parsed.packageId)
                        tuple.addString(packageRow.getString("code"))
                        tuple.addString(packageRow.getString("name"))
                        tuple.addOffsetDateTime(parsed.purchasedAt ?: now)
                        tuple.addValue(packageRow.getBigDecimal("price")) // 应付金额 = 购买时套餐价快照
                        tuple.addValue(BigDecimal.ZERO) // paid_amount 初值
                        tuple.addValue(BigDecimal.ZERO) // used_amount 初值
                        tuple.addString(CREATE_STATUS)
                        tuple.addValue(parsed.metadata)
                        tuple.addString(operator)
                        tuple.addOffsetDateTime(now)
                        tuple.addOffsetDateTime(now)
                        connection.preparedQuery(INSERT_SUBSCRIPTION_SQL).execute(tuple)
                            .compose { getSubscriptionVia(connection, id) }
                    }
            }
        }
    }

    /** 按 id 取订阅（含派生 `pay_state`）；不存在 → [HealthcareNotFoundException]（404）。 */
    fun getSubscription(id: String): Future<JsonObject> = getSubscriptionVia(pool, id)

    /** 订阅列表（`patient_id` / `status` 可选）；空结果也返回 `records: []` 与 `total: 0`。 */
    fun listSubscriptions(patientId: String?, status: String?, limit: Int, offset: Int): Future<JsonObject> {
        val patientFilter = patientId?.trim()?.takeIf(String::isNotBlank)
        val statusFilter = status?.trim()?.takeIf(String::isNotBlank)
        if (statusFilter != null && statusFilter !in SUBSCRIPTION_STATUSES) {
            return Future.failedFuture(IllegalArgumentException("invalid status: $statusFilter"))
        }
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()
        patientFilter?.let { conditions.add("patient_id = \$${values.size + 1}"); values.add(it) }
        statusFilter?.let { conditions.add("status = \$${values.size + 1}"); values.add(it) }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = SUBSCRIPTION_COUNT_SQL + where
        val listSql = "SELECT $SUBSCRIPTION_COLUMNS FROM healthcare.child_package_subscriptions" + where +
            " ORDER BY created_at DESC, id DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"
        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).map { rows ->
                pageJson(rows, total, Companion::subscriptionJson)
            }
        }
    }

    /**
     * 订阅生效/停用（`启用 ⇄ 停用`，行锁内判定）。
     *
     * 不存在 → 404；非法状态或同状态重复提交 → 400。
     */
    fun updateSubscriptionStatus(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val request = try {
            rejectUnsupported(body, SUBSCRIPTION_STATUS_KEYS, "child package subscription status")
            val next = body.getString("status")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("status is required")
            if (next !in SUBSCRIPTION_STATUSES) throw IllegalArgumentException("invalid status: $next")
            next
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val metadata = body.getValue("metadata")?.let {
            it as? JsonObject ?: return Future.failedFuture(
                IllegalArgumentException("metadata must be a JSON object"),
            )
        }
        return pool.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(SUBSCRIPTION_LOCK_SQL).execute(Tuple.of(id)).compose<JsonObject> { rows ->
                val current = rows.iterator().asSequence().firstOrNull()
                    ?: return@compose Future.failedFuture(
                        HealthcareNotFoundException("child package subscription not found: $id"),
                    )
                val from = current.getString("status")
                if (!canTransitionSubscription(from, request)) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("invalid status transition: $from -> $request"),
                    )
                }
                val tuple = Tuple.of(id, request)
                tuple.addValue(metadata)
                tuple.addOffsetDateTime(OffsetDateTime.now())
                connection.preparedQuery(SUBSCRIPTION_STATUS_SQL).execute(tuple)
                    .compose { getSubscriptionVia(connection, id) }
            }
        }
    }

    // ========================================================================
    //  内部：读取、校验与解析
    // ========================================================================

    private fun getSubscriptionVia(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery(SUBSCRIPTION_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(subscriptionJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("child package subscription not found: $id"))
        }

    private fun ensureCodeAvailable(client: SqlClient, code: String, excludeId: String?): Future<Unit> =
        client.preparedQuery(PACKAGE_CODE_TAKEN_SQL).execute(Tuple.of(code, excludeId ?: "")).compose { rows ->
            if (rows.iterator().hasNext()) {
                Future.failedFuture(ConflictException("package code already exists: $code"))
            } else {
                Future.succeededFuture(Unit)
            }
        }

    /** 儿童档案校验：不存在 → 404；非 `person_type=儿童` → 400。 */
    private fun loadChild(client: SqlClient, patientId: String): Future<Row> =
        client.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("patient not found: $patientId"),
                )
            if (row.getString("person_type") != ChildHealth.PERSON_TYPE_CHILD) {
                return@compose Future.failedFuture(
                    IllegalArgumentException("patient is not a child (person_type must be 儿童): $patientId"),
                )
            }
            Future.succeededFuture(row)
        }

    private data class ParsedPackage(
        val code: String,
        val name: String,
        val price: BigDecimal,
        val items: JsonArray,
        val status: String,
        val metadata: JsonObject?,
    )

    private data class ParsedSubscription(
        val patientId: String,
        val packageId: String,
        val purchasedAt: OffsetDateTime?,
        val metadata: JsonObject?,
    )

    private fun parsePackageCreate(body: JsonObject): ParsedPackage {
        rejectUnsupported(body, PACKAGE_KEYS, "child service package")
        return ParsedPackage(
            code = requiredText(body, "code"),
            name = requiredText(body, "name"),
            price = requiredMoney(body, "price"),
            items = requiredItems(body, "items"),
            status = optionalStatus(body, PACKAGE_STATUSES, PACKAGE_STATUSES.first()),
            metadata = optionalJsonObject(body, "metadata"),
        )
    }

    /** PUT 合并：键缺失 → 保留原值；显式 `null` 只允许清空 `metadata`。 */
    private fun mergePackage(current: Row, body: JsonObject): ParsedPackage {
        val code = if (body.containsKey("code")) requiredText(body, "code") else current.getString("code")
        val name = if (body.containsKey("name")) requiredText(body, "name") else current.getString("name")
        val price = if (body.containsKey("price")) {
            requiredMoney(body, "price")
        } else {
            current.getBigDecimal("price")
        }
        val items = if (body.containsKey("items")) {
            requiredItems(body, "items")
        } else {
            current.getJsonArray("items") ?: JsonArray()
        }
        val status = if (body.containsKey("status")) {
            optionalStatus(body, PACKAGE_STATUSES, PACKAGE_STATUSES.first())
        } else {
            current.getString("status")
        }
        val metadata = if (body.containsKey("metadata")) optionalJsonObject(body, "metadata") else current.getJsonObject("metadata")
        return ParsedPackage(code = code, name = name, price = price, items = items, status = status, metadata = metadata)
    }

    private fun parseSubscriptionCreate(body: JsonObject): ParsedSubscription {
        rejectUnsupported(body, SUBSCRIPTION_CREATE_KEYS, "child package subscription")
        return ParsedSubscription(
            patientId = requiredText(body, "patient_id"),
            packageId = requiredText(body, "package_id"),
            purchasedAt = optionalPurchasedAt(body),
            metadata = optionalJsonObject(body, "metadata"),
        )
    }

    private fun optionalPurchasedAt(body: JsonObject): OffsetDateTime? {
        val raw = body.getString("purchased_at")?.trim()?.takeIf(String::isNotBlank) ?: return null
        val value = try {
            OffsetDateTime.parse(raw)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("purchased_at must be an ISO-8601 offset date-time")
        }
        if (value.isAfter(OffsetDateTime.now())) {
            throw IllegalArgumentException("purchased_at must not be in the future")
        }
        return value
    }

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun optionalStatus(body: JsonObject, allowed: Set<String>, fallback: String): String {
        val raw = body.getString("status")?.trim()?.takeIf(String::isNotBlank) ?: return fallback
        if (raw !in allowed) throw IllegalArgumentException("invalid status: $raw")
        return raw
    }

    /** 金额入参：非负、最多 2 位小数（超出直接 400，不静默进位）。 */
    private fun requiredMoney(body: JsonObject, key: String): BigDecimal {
        val raw = body.getValue(key) ?: throw IllegalArgumentException("$key is required")
        val value = decimalValue(raw, key)
        if (value.signum() < 0) throw IllegalArgumentException("$key must not be negative")
        if (value.stripTrailingZeros().scale() > 2) {
            throw IllegalArgumentException("$key exceeds precision of 2 decimals")
        }
        return value.setScale(2, RoundingMode.HALF_UP)
    }

    private fun decimalValue(raw: Any?, label: String): BigDecimal = when (raw) {
        is BigDecimal -> raw
        is Number -> raw.toString().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("$label must be a number")
        is String -> raw.trim().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("$label must be a number")
        else -> throw IllegalArgumentException("$label must be a number")
    }

    /** 服务项快照：非空数组，逐项重建为 `{item_code,item_name,quantity}`（丢弃额外键）。 */
    private fun requiredItems(body: JsonObject, key: String): JsonArray {
        val raw = body.getValue(key) ?: throw IllegalArgumentException("$key is required")
        val array = raw as? JsonArray ?: throw IllegalArgumentException("$key must be a JSON array")
        if (array.isEmpty) throw IllegalArgumentException("$key must not be empty")
        val normalized = JsonArray()
        array.forEachIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw IllegalArgumentException("$key[$index] must be a JSON object")
            val itemCode = item.getString("item_code")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$key[$index].item_code is required")
            val itemName = item.getString("item_name")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$key[$index].item_name is required")
            val quantityRaw = item.getValue("quantity")
                ?: throw IllegalArgumentException("$key[$index].quantity is required")
            val quantity = decimalValue(quantityRaw, "$key[$index].quantity")
            if (quantity.signum() <= 0) throw IllegalArgumentException("$key[$index].quantity must be positive")
            normalized.add(
                JsonObject()
                    .put("item_code", itemCode)
                    .put("item_name", itemName)
                    .put("quantity", quantity),
            )
        }
        return normalized
    }

    private fun optionalJsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key) ?: return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun rejectUnsupported(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }

    private fun tupleOf(values: List<Any>): Tuple {
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        return tuple
    }

    /** 唯一索引兜底（并发撞码）：委托共享判定 [isUniqueViolation]（遍历 cause 链 + `sqlState == 23505` + 约束名兜底）。 */
    private fun <T> Future<T>.mapUniqueViolation(constraint: String, message: String): Future<T> =
        recover { error ->
            if (isUniqueViolation(error, setOf(constraint))) {
                Future.failedFuture(ConflictException(message))
            } else {
                Future.failedFuture(error)
            }
        }
}
