package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 家庭训练指导手册分发服务（054 B4，迁移 V412）。
 *
 * 规则：
 *  1. **模板为常量**（[TEMPLATES]，8 册）：`POST` 只需 `template_code`，正文默认取模板；
 *     客户端传非空 `content` 时作为**个性化补充**覆盖正文（快照落库，模板后续修订不影响历史）。
 *  2. 台账**可查**：`GET /child-rehab-handouts?patient_id=` 走 `{records, meta:{total}}`。
 *  3. **签收单向**：`acknowledged_at IS NULL` ⇒ `未签收`；签收只能由未签收 → 已签收
 *     （`PATCH /child-rehab-handouts/:id/acknowledge`）；已签收再签 → **409**（`ConflictException`），
 *     并发重复签收由 `UPDATE ... WHERE acknowledged_at IS NULL` 的**行数判定**兜底（0 行 → 409）。
 *  4. 创建时**不接受**客户端 `acknowledged_at`（签收必须是显式动作，§11.2 Q10）。
 *  5. `channel` 未列入 §5.8 白名单 → 自由文本，缺省 `现场`；`operator` 取认证主体（042 口径）。
 *  6. 持久化走**原生参数化 SQL**（`$1..$n` + `Tuple`），本文件不引入 jOOQ。
 */
class ChildRehabHandoutService(private val pool: Pool) {

    companion object {
        /** 签收状态（由 `acknowledged_at` 派生，不落库）。 */
        const val STATUS_PENDING = "未签收"
        const val STATUS_ACKNOWLEDGED = "已签收"

        /** 渠道缺省值（§5.8 未冻结该枚举，自由文本；前端建议值：现场/微信/纸质）。 */
        const val DEFAULT_CHANNEL = "现场"

        private const val PATIENT_SQL = "SELECT id FROM healthcare.patients WHERE id = \$1"

        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 手册模板（常量，版本化维护）。 */
        data class Template(val code: String, val name: String, val content: String)

        val TEMPLATES: List<Template> = listOf(
            Template(
                "HOME_HEAD_CONTROL",
                "家庭抬头训练指导",
                "每日 3 组、每组 5 分钟俯卧抬头：让儿童俯卧在硬质软垫上，用玩具在视线前方引导抬头；" +
                    "出现哭闹抗拒立即停止，喂奶后 30 分钟内不做。",
            ),
            Template(
                "HOME_ROLL_OVER",
                "家庭翻身训练指导",
                "在软垫上引导儿童从仰卧到侧卧再到俯卧，左右各 5 次；家长一手护住肩部、一手轻推骨盆，" +
                    "全程不牵拉单侧上肢。",
            ),
            Template(
                "HOME_SIT",
                "家庭独坐训练指导",
                "靠坐 → 扶坐 → 独坐循序渐进，每次 5–10 分钟；背后与两侧放置软垫防后仰侧倒，" +
                    "儿童疲劳时改为靠坐休息。",
            ),
            Template(
                "HOME_CRAWL",
                "家庭爬行训练指导",
                "用玩具在前方引导爬行 3–5 米，每日 2 次；清理地面障碍与硬物，膝下可加护膝。",
            ),
            Template(
                "HOME_STAND_WALK",
                "家庭站立行走训练指导",
                "扶站每次 5 分钟、每日 3 次；扶走时家长在侧后方保护，避免牵拉单侧上肢或使用学步车。",
            ),
            Template(
                "HOME_FINE_MOTOR",
                "家庭精细动作训练指导",
                "抓握不同质地小球、拇食指捏取小物件（需全程看护、防误吞），每日 10 分钟；" +
                    "从大物件逐步过渡到小物件。",
            ),
            Template(
                "HOME_SPEECH",
                "家庭语言训练指导",
                "面对面慢速发音、命名日常物品并等待儿童回应，每日 15 分钟；减少屏幕时间，" +
                    "不代替儿童表达需求。",
            ),
            Template(
                "HOME_DAILY_CARE",
                "家庭日常照护指导",
                "规律作息、均衡膳食、回避已知过敏原、按医嘱用药；按时复诊与康复随访，异常及时就诊。",
            ),
        )

        private val BY_CODE: Map<String, Template> = TEMPLATES.associateBy { it.code }

        /** 按 code 取模板；未收录返回 `null`。 */
        fun templateOf(code: String): Template? = BY_CODE[code.trim()]

        /** 行 → 分发台账 JSON（`status` 由 `acknowledged_at` 派生）。 */
        internal fun toJson(row: Row): JsonObject {
            val acknowledgedAt = row.getOffsetDateTime("acknowledged_at")
            return JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("template_code", row.getString("template_code"))
                .put("template_name", row.getString("template_name"))
                .put("content", row.getString("content"))
                .put("channel", row.getString("channel"))
                .put("delivered_at", row.getOffsetDateTime("delivered_at")?.toString())
                .put("acknowledged_at", acknowledgedAt?.toString())
                .put("acknowledged_by", row.getString("acknowledged_by"))
                .put("status", if (acknowledgedAt == null) STATUS_PENDING else STATUS_ACKNOWLEDGED)
                .put("operator", row.getString("operator"))
                .put("metadata", row.getJsonObject("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
        }
    }

    /** 模板清单（`{records, meta:{total}}`，与仓库列表口径一致）。 */
    fun templates(): Future<JsonObject> {
        val records = JsonArray(TEMPLATES.map {
            JsonObject()
                .put("template_code", it.code)
                .put("template_name", it.name)
                .put("content", it.content)
        })
        return Future.succeededFuture(
            JsonObject().put("records", records).put("meta", JsonObject().put("total", TEMPLATES.size)),
        )
    }

    /**
     * 分发手册（创建台账行，初始 `未签收`）。
     * 参数非法抛 `IllegalArgumentException`（400）；儿童不存在抛 [NotFoundException]（404）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        val patientId = body.getString("patient_id")?.trim()
        if (patientId.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")

        val code = body.getString("template_code")?.trim()
        if (code.isNullOrEmpty()) throw IllegalArgumentException("template_code is required")
        val template = templateOf(code)
            ?: throw IllegalArgumentException(
                "unknown template_code: $code, must be one of: ${TEMPLATES.map { it.code }}",
            )

        val content = body.getString("content")?.trim()?.takeIf { it.isNotEmpty() } ?: template.content
        val channel = body.getString("channel")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CHANNEL
        val deliveredAt = parseDeliveredAt(body.getString("delivered_at"))
        val metadata = parseMetadata(body.getValue("metadata"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addString(patientId)
        tuple.addString(template.code)
        tuple.addString(template.name)
        tuple.addString(content)
        tuple.addString(channel)
        tuple.addOffsetDateTime(deliveredAt)
        tuple.addValue(operator)
        tuple.addValue(metadata)
        tuple.addOffsetDateTime(now)
        tuple.addOffsetDateTime(now)

        return verifyPatient(patientId).compose {
            pool.preparedQuery(INSERT_SQL).execute(tuple).compose { get(id) }
        }
    }

    /**
     * 签收（**单向**）：未签收 → 已签收。
     * 台账不存在 → 404；已签收再签 → 409；并发重复签收（条件更新 0 行）→ 409。
     */
    fun acknowledge(id: String, operator: String?): Future<JsonObject> {
        val handoutId = id.trim()
        if (handoutId.isEmpty()) throw IllegalArgumentException("id is required")

        return get(handoutId).compose { existing ->
            if (existing.getString("acknowledged_at") != null)
                throw ConflictException("handout already acknowledged: $handoutId")

            val now = OffsetDateTime.now()
            pool.preparedQuery(ACKNOWLEDGE_SQL)
                .execute(Tuple.of(now, operator, now, handoutId))
                .compose { result ->
                    if (result.rowCount() == 0)
                        Future.failedFuture(ConflictException("handout already acknowledged: $handoutId"))
                    else get(handoutId)
                }
        }
    }

    /** 按 id 取单条；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("child rehab handout not found: $id"))
            Future.succeededFuture(toJson(row))
        }

    /** 台账列表（空结果也返回 `records: []` 与 `total: 0`）。过滤：`patient_id`。 */
    fun list(
        patientId: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()
        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("h.patient_id = \$${values.size + 1}")
            values.add(it)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY h.delivered_at DESC, h.created_at DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::toJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  SQL 与辅助
    // ========================================================================

    private val SELECT_COLUMNS =
        "h.id, h.patient_id, h.template_code, h.template_name, h.content, h.channel, h.delivered_at, " +
            "h.acknowledged_at, h.acknowledged_by, h.operator, h.metadata, h.created_at, h.updated_at"

    private val SELECT_FROM = " FROM nursing.nursing_child_rehab_handouts h "

    private val INSERT_SQL =
        "INSERT INTO nursing.nursing_child_rehab_handouts " +
            "(id, patient_id, template_code, template_name, content, channel, delivered_at, " +
            "operator, metadata, created_at, updated_at) " +
            "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11)"

    private val ACKNOWLEDGE_SQL =
        "UPDATE nursing.nursing_child_rehab_handouts " +
            "SET acknowledged_at = \$1, acknowledged_by = \$2, updated_at = \$3 " +
            "WHERE id = \$4 AND acknowledged_at IS NULL"

    private val GET_SQL = "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE h.id = \$1"

    private val COUNT_SQL = "SELECT count(*) AS total" + SELECT_FROM

    private fun verifyPatient(patientId: String): Future<Unit> =
        pool.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            if (rows.iterator().asSequence().firstOrNull() == null)
                Future.failedFuture(NotFoundException("patient not found: $patientId"))
            else Future.succeededFuture(Unit)
        }

    /** `delivered_at` 可选：接受带偏移的 ISO-8601 时间或纯日期（按业务时区当日 00:00）；缺省当前时刻。 */
    private fun parseDeliveredAt(value: String?): OffsetDateTime {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return OffsetDateTime.now()
        runCatching { return OffsetDateTime.parse(text) }
        runCatching { return LocalDate.parse(text).atStartOfDay(businessZone).toOffsetDateTime() }
        throw IllegalArgumentException("delivered_at must be a valid ISO-8601 date or date-time")
    }

    private fun parseMetadata(value: Any?): JsonObject? = when (value) {
        null -> null
        is JsonObject -> value
        else -> throw IllegalArgumentException("metadata must be an object")
    }

    private fun tupleOf(values: List<Any?>): Tuple {
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        return tuple
    }
}
