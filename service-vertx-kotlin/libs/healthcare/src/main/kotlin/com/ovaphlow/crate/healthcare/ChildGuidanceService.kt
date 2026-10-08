package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
import java.time.OffsetDateTime

/**
 * 儿保日常健康指导服务（052 P5，V530）。
 *
 * 业务规则（服务端强制）：
 *  1. 只服务 `person_type=儿童` 且 `birth_date` 非空的患者（`childPatientRow`）。
 *  2. 指导模板为**静态常量** [GUIDANCE_TEMPLATES]（月龄段 × 主题），无 DB 内容管理（052 D7）；
 *     前端按当前月龄取推荐模板，模板后续调整不影响历史开展记录（记录存内容快照）。
 *  3. `channel` 与通报同义：站内/短信/微信；未接入渠道不得宣称已送达（052 裁定 4）。
 *  4. `delivered_at` 缺省当前时间，不得晚于当前。
 *  5. `operator` 一律取认证主体（042 范式）。
 */
class ChildGuidanceService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 月龄段中文枚举。 */
        val ageBands = listOf("0-6月", "7-12月", "13-24月", "25-60月")

        /** 主题中文枚举。 */
        val topics = setOf("喂养", "睡眠", "口腔", "安全", "发育促进", "疾病预防")

        /**
         * 指导口径中文枚举（053 B3 / V534）：日常（052 护士日常指导）/ 疾病防控（医生指令）。
         *
         * `kind` 是**业务字段而非权限边界**：写路由同路径双码（`health:manage` / `clinical:write`，
         * 任一放行），医生疾病防控指导复用 052 的 POST 路径（053 §5）。
         */
        val kinds = setOf("日常", "疾病防控")

        /** 通报渠道白名单（与 V529 同义）。 */
        val channels = setOf("站内", "短信", "微信")

        /** 一条指导模板；[kind] 缺省 `日常`，052 既有模板无需显式声明。 */
        data class Template(
            val code: String,
            val ageBand: String,
            val topic: String,
            val title: String,
            val content: String,
            val kind: String = "日常",
        )

        /** 指导模板常量（月龄段 × 主题，052 D7）。 */
        val GUIDANCE_TEMPLATES: List<Template> = listOf(
            Template(
                "FEED_0_6", "0-6月", "喂养", "6 月龄内纯母乳喂养",
                "6 月龄内建议纯母乳喂养，按需哺乳（每天 8 次以上）；" +
                    "无法母乳喂养时使用配方奶，按说明冲调，不加糖、不加米糊。出生后数日开始补充维生素 D。",
            ),
            Template(
                "SLEEP_0_6", "0-6月", "睡眠", "新生儿睡眠与安全睡姿",
                "新生儿每天睡眠 14–17 小时，宜仰卧睡眠，床面平整、无软枕与松软被褥；" +
                    "避免包裹过紧与过热，夜间喂奶后拍嗝再放回床上。",
            ),
            Template(
                "SAFE_0_6", "0-6月", "安全", "居家安全与看护",
                "抱婴时托住头颈；不将婴儿单独留在床边、沙发或水中；" +
                    "避免接触吸烟环境与过热环境；按时接种疫苗并定期儿保体检。",
            ),
            Template(
                "FEED_7_12", "7-12月", "喂养", "及时添加辅食",
                "满 6 月龄起添加富铁辅食（强化铁米粉、肉泥、肝泥），由少到多、由稀到稠、逐样添加；" +
                    "1 岁内不加盐、不加糖、不喂蜂蜜；继续母乳或配方奶至 1 岁以后。",
            ),
            Template(
                "DEV_7_12", "7-12月", "发育促进", "大运动与语言启蒙",
                "鼓励俯卧、翻身、独坐与爬行练习；多与婴儿面对面说话、回应发音；" +
                    "提供安全的抓握玩具，避免长时间使用学步车与电子屏幕。",
            ),
            Template(
                "ORAL_7_12", "7-12月", "口腔", "乳牙萌出期护理",
                "第一颗乳牙萌出后即开始清洁：用指套刷或软毛牙刷蘸清水早晚擦拭；" +
                    "睡前不喂含糖奶液，避免奶瓶龋；1 岁前完成首次口腔检查。",
            ),
            Template(
                "FEED_13_24", "13-24月", "喂养", "幼儿饮食结构与自主进食",
                "每日 3 餐 2 点，食物多样、荤素搭配；鼓励自主进食但避免呛噎风险食物（整粒坚果、果冻）；" +
                    "继续补充维生素 D，奶量约每天 400–500ml。",
            ),
            Template(
                "DEV_13_24", "13-24月", "发育促进", "语言与社交发育",
                "每天亲子共读 10–15 分钟；鼓励用词语表达需求；安排同龄儿童互动；" +
                    "屏幕时间每天不超过 1 小时且需家长陪同。",
            ),
            Template(
                "SAFE_13_24", "13-24月", "安全", "学步期伤害预防",
                "安装楼梯护栏与窗户限位，收好药品、清洁剂与尖锐物品；" +
                    "热水与热汤远离桌边；外出乘车使用安全座椅。",
            ),
            Template(
                "PREVENT_25_60", "25-60月", "疾病预防", "托幼机构防病与手卫生",
                "教会儿童七步洗手法，饭前便后、外出回家后洗手；" +
                    "按时完成免疫规划疫苗接种；发热或腹泻时暂缓入园并及时就诊。",
            ),
            Template(
                "DEV_25_60", "25-60月", "发育促进", "学龄前认知与运动",
                "每天户外活动不少于 2 小时；通过游戏发展精细动作与规则意识；" +
                    "限制屏幕时间在每天 1 小时内，保证 10–13 小时睡眠。",
            ),
            Template(
                "ORAL_25_60", "25-60月", "口腔", "乳牙列口腔保健",
                "家长协助早晚刷牙并使用含氟牙膏（米粒至豌豆大小）；" +
                    "每 6 个月口腔检查一次，及时处理龋齿；控制含糖零食与饮料频次。",
            ),
            // —— 053 B3 医生疾病防控指导（kind=疾病防控）——
            Template(
                "PREVENT_INFECT_25_60", "25-60月", "疾病预防", "传染病防控",
                "发现儿童出现发热、皮疹、腹泻、呕吐等症状时及时就医并居家观察，不带病入园；" +
                    "按免疫规划完成疫苗接种，坚持饭前便后洗手与开窗通风，流行季节减少前往人群密集场所。",
                kind = "疾病防控",
            ),
            Template(
                "PREVENT_DISINFECT_13_24", "13-24月", "疾病预防", "隔离消毒",
                "患儿居家隔离期间单独使用餐具与毛巾，玩具、桌面、门把手等高频接触面每日用含氯消毒剂擦拭；" +
                    "照顾者接触患儿后及时洗手，排泄物、呕吐物按规范处理，隔离期限遵医嘱。",
                kind = "疾病防控",
            ),
            Template(
                "PREVENT_RESUME_25_60", "25-60月", "疾病预防", "复课证明",
                "患儿症状消失且达到隔离期限后，须由医疗机构开具复课证明（或按当地托幼机构要求提供相应健康证明），" +
                    "经园方查验后方可返园；返园初期仍需观察体温与精神状态。",
                kind = "疾病防控",
            ),
            Template(
                "PREVENT_GROUP_25_60", "25-60月", "疾病预防", "群体防控",
                "同一班级或机构出现聚集性病例时，配合疾控与园方开展晨午检、缺勤追踪与环境消毒；" +
                    "及时上报异常情况，必要时按疾控指导采取停课、错峰等措施，并做好家长沟通与健康教育。",
                kind = "疾病防控",
            ),
        )

        /** 月龄 → 月龄段（与前端 `lib/guidance.ts` 同口径）。 */
        fun ageBandOf(ageMonths: Int): String = when {
            ageMonths < 7 -> "0-6月"
            ageMonths < 13 -> "7-12月"
            ageMonths < 25 -> "13-24月"
            else -> "25-60月"
        }

        /** 模板 → JSON。 */
        fun templateJson(template: Template): JsonObject =
            JsonObject()
                .put("template_code", template.code)
                .put("age_band", template.ageBand)
                .put("topic", template.topic)
                .put("kind", template.kind)
                .put("title", template.title)
                .put("content", template.content)

        internal fun recordJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("patient_name", row.getString("patient_name"))
                .put("template_code", row.getString("template_code"))
                .put("age_band", row.getString("age_band"))
                .put("topic", row.getString("topic"))
                .put("kind", row.getString("kind"))
                .put("content", row.getString("content"))
                .put("delivered_at", row.getOffsetDateTime("delivered_at")?.toString())
                .put("channel", row.getString("channel"))
                .put("operator", row.getString("operator"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())

        private val createKeys = setOf(
            "patient_id", "template_code", "age_band", "topic", "kind",
            "content", "delivered_at", "channel", "metadata",
        )
    }

    private val table = DSL.table(DSL.name("healthcare", "child_health_guidance_records"))

    private val fId = DSL.field(DSL.name("child_health_guidance_records", "id"), String::class.java)
    private val fPatientId = DSL.field(DSL.name("child_health_guidance_records", "patient_id"), String::class.java)
    private val fTemplateCode = DSL.field(DSL.name("child_health_guidance_records", "template_code"), String::class.java)
    private val fAgeBand = DSL.field(DSL.name("child_health_guidance_records", "age_band"), String::class.java)
    private val fTopic = DSL.field(DSL.name("child_health_guidance_records", "topic"), String::class.java)
    private val fKind = DSL.field(DSL.name("child_health_guidance_records", "kind"), String::class.java)
    private val fContent = DSL.field(DSL.name("child_health_guidance_records", "content"), String::class.java)
    private val fDeliveredAt = DSL.field(DSL.name("child_health_guidance_records", "delivered_at"), OffsetDateTime::class.java)
    private val fChannel = DSL.field(DSL.name("child_health_guidance_records", "channel"), String::class.java)
    private val fOperator = DSL.field(DSL.name("child_health_guidance_records", "operator"), String::class.java)
    private val fMetadata = DSL.field(DSL.name("child_health_guidance_records", "metadata"), JSONB::class.java)
    private val fCreatedAt = DSL.field(DSL.name("child_health_guidance_records", "created_at"), OffsetDateTime::class.java)

    private val selectFields: List<Field<*>> = listOf(
        fId, fPatientId, fTemplateCode, fAgeBand, fTopic, fKind, fContent,
        fDeliveredAt, fChannel, fOperator, fMetadata, fCreatedAt,
    )

    private val wId = DSL.field(DSL.name("id"), String::class.java)
    private val wPatientId = DSL.field(DSL.name("patient_id"), String::class.java)
    private val wTemplateCode = DSL.field(DSL.name("template_code"), String::class.java)
    private val wAgeBand = DSL.field(DSL.name("age_band"), String::class.java)
    private val wTopic = DSL.field(DSL.name("topic"), String::class.java)
    private val wKind = DSL.field(DSL.name("kind"), String::class.java)
    private val wContent = DSL.field(DSL.name("content"), String::class.java)
    private val wDeliveredAt = DSL.field(DSL.name("delivered_at"), OffsetDateTime::class.java)
    private val wChannel = DSL.field(DSL.name("channel"), String::class.java)
    private val wOperator = DSL.field(DSL.name("operator"), String::class.java)
    private val wMetadata = DSL.field(DSL.name("metadata"), JSONB::class.java)
    private val wCreatedAt = DSL.field(DSL.name("created_at"), OffsetDateTime::class.java)

    // ========================================================================
    //  模板
    // ========================================================================

    /**
     * 按当前月龄取推荐模板；省略 `age_months` 时返回全部模板（含各月龄段）。
     *
     * 053 B3：`kind` 可选过滤（日常 / 疾病防控），缺省返回全部（`kind=null` 不改既有行为）；
     * 非法 `kind` 在进库/返回前即 400。
     */
    fun listTemplates(ageMonths: Int?, kind: String? = null): Future<JsonObject> {
        if (ageMonths != null && ageMonths < 0) {
            return Future.failedFuture(IllegalArgumentException("age_months must not be negative"))
        }
        val normalizedKind = kind?.trim()?.takeIf(String::isNotBlank)
        if (normalizedKind != null && normalizedKind !in kinds) {
            return Future.failedFuture(IllegalArgumentException("invalid kind, must be one of: $kinds"))
        }
        val band = ageMonths?.let { ageBandOf(it) }
        val records = GUIDANCE_TEMPLATES
            .filter { band == null || it.ageBand == band }
            .filter { normalizedKind == null || it.kind == normalizedKind }
            .map(Companion::templateJson)
        return Future.succeededFuture(
            JsonObject()
                .put("records", JsonArray(records))
                .put("meta", JsonObject().put("total", records.size.toLong()).put("age_band", band)),
        )
    }

    // ========================================================================
    //  开展记录
    // ========================================================================

    fun createRecord(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed: Parsed = try {
            parseCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        val id = Ulid.generate()
        return pool.withTransaction<JsonObject> { connection ->
            childPatientRow(ctx, connection, parsed.patientId).compose { patient ->
                val ageMonths = ChildHealth.ageMonths(
                    patient.getLocalDate("birth_date"),
                    parsed.deliveredAt.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(),
                )
                val ageBand = parsed.ageBand ?: ageBandOf(ageMonths)
                val insert = ctx.insertInto(table)
                    .set(wId, id)
                    .set(wPatientId, parsed.patientId)
                    .set(wTemplateCode, parsed.templateCode)
                    .set(wAgeBand, ageBand)
                    .set(wKind, parsed.kind)
                    .set(wContent, parsed.content)
                    .set(wDeliveredAt, parsed.deliveredAt)
                    .set(wChannel, parsed.channel)
                    .set(wOperator, operator)
                    .set(wCreatedAt, now)
                    .apply {
                        parsed.topic?.let { set(wTopic, it) }
                        parsed.metadata?.let { set(wMetadata, JSONB.valueOf(it.encode())) }
                    }
                executeChildQuery(connection, insert).compose { getRecordVia(connection, id) }
            }
        }
    }

    fun listRecords(patientId: String?, limit: Int, offset: Int, kind: String? = null): Future<JsonObject> {
        val normalizedKind = kind?.trim()?.takeIf(String::isNotBlank)
        if (normalizedKind != null && normalizedKind !in kinds) {
            return Future.failedFuture(IllegalArgumentException("invalid kind, must be one of: $kinds"))
        }
        val conditions = try {
            val patient = patientId?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("patient_id is required")
            mutableListOf<Condition>(fPatientId.eq(patient))
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        normalizedKind?.let { conditions.add(fKind.eq(it)) }
        val countQuery = ctx.select(DSL.count().`as`("total")).from(table).where(conditions)
        val dataQuery = baseQuery().where(conditions).orderBy(fDeliveredAt.desc()).limit(limit).offset(offset)
        return executeChildQuery(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            executeChildQuery(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::recordJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getRecord(id: String): Future<JsonObject> = getRecordVia(pool, id)

    private fun getRecordVia(client: SqlClient, id: String): Future<JsonObject> =
        executeChildQuery(client, baseQuery().where(fId.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(recordJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("child guidance record not found: $id"))
        }

    // ========================================================================
    //  解析与校验
    // ========================================================================

    private data class Parsed(
        val patientId: String,
        val templateCode: String,
        val ageBand: String?,
        val topic: String?,
        val kind: String,
        val content: String,
        val deliveredAt: OffsetDateTime,
        val channel: String,
        val metadata: JsonObject?,
    )

    private fun parseCreate(body: JsonObject): Parsed {
        rejectForbiddenKeys(body, createKeys, "child guidance record")
        val patientId = body.getString("patient_id")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("patient_id is required")
        val templateCode = body.getString("template_code")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("template_code is required")
        val template = GUIDANCE_TEMPLATES.firstOrNull { it.code == templateCode }
        val ageBand = body.getString("age_band")?.trim()?.takeIf(String::isNotBlank)?.let {
            it.takeIf { value -> value in ageBands }
                ?: throw IllegalArgumentException("invalid age_band, must be one of: $ageBands")
        } ?: template?.ageBand
        val topic = body.getString("topic")?.trim()?.takeIf(String::isNotBlank)?.let {
            it.takeIf { value -> value in topics }
                ?: throw IllegalArgumentException("invalid topic, must be one of: $topics")
        } ?: template?.topic
        val kind = body.getString("kind")?.trim()?.takeIf(String::isNotBlank)?.let {
            it.takeIf { value -> value in kinds }
                ?: throw IllegalArgumentException("invalid kind, must be one of: $kinds")
        } ?: template?.kind ?: "日常"
        val content = body.getString("content")?.trim()?.takeIf(String::isNotBlank)
            ?: template?.content
            ?: throw IllegalArgumentException("content is required when template_code is not a known template")
        if (content.length > 4000) throw IllegalArgumentException("content must not exceed 4000 characters")
        val deliveredAt = body.getString("delivered_at")?.let {
            try {
                OffsetDateTime.parse(it)
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("delivered_at must be an ISO-8601 offset date-time")
            }
        } ?: OffsetDateTime.now()
        if (deliveredAt.isAfter(OffsetDateTime.now())) {
            throw IllegalArgumentException("delivered_at must not be in the future")
        }
        val channel = body.getString("channel")?.trim()?.takeIf(String::isNotBlank) ?: "站内"
        if (channel !in channels) {
            throw IllegalArgumentException("invalid channel, must be one of: $channels")
        }
        return Parsed(
            patientId = patientId,
            templateCode = templateCode,
            ageBand = ageBand,
            topic = topic,
            kind = kind,
            content = content,
            deliveredAt = deliveredAt,
            channel = channel,
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun baseQuery() = ctx.select(
        selectFields + listOf(DSL.field(DSL.name("patients", "name"), String::class.java).`as`("patient_name")),
    )
        .from(table)
        .join(DSL.table(DSL.name("healthcare", "patients")))
        .on(fPatientId.eq(DSL.field(DSL.name("patients", "id"), String::class.java)))

    private fun jsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key) ?: return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun rejectForbiddenKeys(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }
}
