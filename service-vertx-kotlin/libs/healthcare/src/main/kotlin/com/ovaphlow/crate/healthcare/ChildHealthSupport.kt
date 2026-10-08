package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.Patients.PATIENTS
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 052 儿保（儿童健康）日常功能链的公共口径。
 *
 * 五个儿保服务（体检 / 成长 / 接种 / 异常通报 / 指导）共用：
 *  - `patients.person_type` 白名单校验（V525：居民/长者/儿童）；
 *  - 月龄/天龄派生（与前端 `lib/age.ts` 的 `formatAge` 日历差口径一致）；
 *  - 新表按名引用（V526–V530 未重跑 jOOQ codegen，与 V523–V525 同约定）。
 */
internal object ChildHealth {
    /** `patients.person_type` 的儿童取值，与 V525 注释、前端 `DOMAIN_ENTITY` 逐字一致。 */
    const val PERSON_TYPE_CHILD = "儿童"

    private val businessZone = ZoneId.of("Asia/Shanghai")

    /** 业务时区「今天」。 */
    fun today(): LocalDate = LocalDate.now(businessZone)

    /**
     * 完整月龄：`birthDate` → `on` 的整月数。
     *
     * 口径与前端 `lib/age.ts` 的 `formatAge` **逐字一致**：先按月对齐，
     * 「日」不足则退一个月（`2026-01-31 → 2026-02-28` 记 0 月而不是 1 月）。
     */
    fun ageMonths(birthDate: LocalDate, on: LocalDate): Int {
        if (on.isBefore(birthDate)) {
            throw IllegalArgumentException("date must not be before child birth_date")
        }
        var months = (on.year - birthDate.year) * 12 + (on.monthValue - birthDate.monthValue)
        if (on.dayOfMonth < birthDate.dayOfMonth) months -= 1
        return months.coerceAtLeast(0)
    }

    /** 天龄：`birthDate` → `on` 的整天差（出生当天为 0）。 */
    fun ageDays(birthDate: LocalDate, on: LocalDate): Long {
        if (on.isBefore(birthDate)) {
            throw IllegalArgumentException("date must not be before child birth_date")
        }
        return ChronoUnit.DAYS.between(birthDate, on)
    }
}

/** `patients.person_type`（SELECT 带表名限定；jOOQ codegen 未含 V525 新列）。 */
internal val patientsPersonTypeField =
    DSL.field(DSL.name("patients", "person_type"), String::class.java)

/** 执行一条 jOOQ 查询（与各服务内的私有 `execute` 同实现，抽出来给公共辅助函数复用）。 */
internal fun executeChildQuery(client: SqlClient, query: Query): Future<RowSet<Row>> =
    client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))

/**
 * 读取**儿童**患者行（含 `person_type` / `birth_date`）。
 *
 * 非 `person_type=儿童` 一律 400：儿保五个服务只服务儿童，不接受成人/长者误入
 * （052 D1/D10：儿童档案含未成年人健康数据，读入口径必须显式）。
 */
internal fun childPatientRow(ctx: DSLContext, client: SqlClient, patientId: String): Future<Row> =
    executeChildQuery(
        client,
        ctx.select(PATIENTS.ID, PATIENTS.NAME, PATIENTS.BIRTH_DATE, patientsPersonTypeField)
            .from(PATIENTS)
            .where(PATIENTS.ID.eq(patientId)),
    ).compose { rows ->
        val row = rows.iterator().asSequence().firstOrNull()
            ?: return@compose Future.failedFuture(
                HealthcareNotFoundException("patient not found: $patientId"),
            )
        if (row.getString("person_type") != ChildHealth.PERSON_TYPE_CHILD) {
            return@compose Future.failedFuture(
                IllegalArgumentException("patient is not a child (person_type must be 儿童): $patientId"),
            )
        }
        if (row.getLocalDate("birth_date") == null) {
            return@compose Future.failedFuture(
                IllegalArgumentException("child birth_date is required: $patientId"),
            )
        }
        Future.succeededFuture(row)
    }

/**
 * 052 P4：家长通报的外发端口（Nexus `messages`）。
 *
 * 由 App 编排层（`apps/aceso`）注入 `NexusMessageClient`；未接线（嵌入式测试）时为 null，
 * 通报仍写本域台账，但状态记「待接入」且不谎报送达（052 D6 / 裁定 4）。
 */
interface ChildHealthMessagePort {
    /**
     * 创建一条 Nexus message，返回 `message_id`。
     *
     * @param cookie 用户会话 cookie（Nexus 写路由要求有效会话；Aceso 后端按 042 的
     *   会话透传范式转发，不自行铸造身份）。
     */
    fun send(
        messageType: String,
        receiverId: String,
        receiverType: String,
        payload: JsonObject,
        cookie: String?,
    ): Future<String>
}
