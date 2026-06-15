package com.ovaphlow.crate.aiassistant

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.AiQaLogs
import com.ovaphlow.crate.database.gen.public_.tables.FaqPairs
import com.ovaphlow.crate.database.gen.public_.tables.PreventivePushRules
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

class AiAssistantService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(AiAssistantService::class.java)
    private val ql = AiQaLogs.AI_QA_LOGS
    private val fp = FaqPairs.FAQ_PAIRS
    private val ppr = PreventivePushRules.PREVENTIVE_PUSH_RULES

    // ==============================
    // QA Logs
    // ==============================

    fun askQuestion(userId: String, question: String): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val answer = "This is a simulated answer for: $question"
        val sources = JsonArray()

        val query = ctx.insertInto(ql)
            .columns(ql.ID, ql.USER_ID, ql.QUESTION, ql.ANSWER, ql.SOURCES, ql.FEEDBACK, ql.CREATED_AT)
            .values(id, userId, question, answer, JSONB.valueOf(sources.encode()), "", now)
            .returning(ql.ID, ql.USER_ID, ql.QUESTION, ql.ANSWER, ql.SOURCES, ql.FEEDBACK, ql.CREATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> qaLogToJson(rows.iterator().next()) }
    }

    fun submitFeedback(id: String, feedback: String): Future<JsonObject> {
        val validFeedbacks = listOf("有用", "没用")
        if (feedback !in validFeedbacks) {
            return Future.failedFuture(IllegalArgumentException("feedback must be one of $validFeedbacks"))
        }
        val query = ctx.update(ql)
            .set(ql.FEEDBACK, feedback)
            .where(ql.ID.eq(id))
            .returning(ql.ID, ql.USER_ID, ql.QUESTION, ql.ANSWER, ql.SOURCES, ql.FEEDBACK, ql.CREATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("qa log not found"))
                else Future.succeededFuture(qaLogToJson(rows.iterator().next()))
            }
    }

    // ==============================
    // FAQ Pairs
    // ==============================

    fun listFaq(
        search: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()

        if (!search.isNullOrBlank()) {
            conditions.add(fp.QUESTION.likeIgnoreCase("%$search%").or(fp.ANSWER.likeIgnoreCase("%$search%")))
        }

        val countQuery = ctx.select(count().`as`("total")).from(fp).where(conditions)
        val dataQuery = ctx.select(fp.ID, fp.QUESTION, fp.ANSWER, fp.TAGS, fp.ENABLED, fp.CREATED_BY, fp.CREATED_AT, fp.UPDATED_AT)
            .from(fp)
            .where(conditions)
            .orderBy(fp.CREATED_AT.desc())
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
                            records.add(faqToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createFaq(
        question: String,
        answer: String,
        tags: List<String> = emptyList(),
        enabled: Boolean = true,
        createdBy: String = ""
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val query = ctx.insertInto(fp)
            .columns(fp.ID, fp.QUESTION, fp.ANSWER, fp.TAGS, fp.ENABLED, fp.CREATED_BY, fp.CREATED_AT, fp.UPDATED_AT)
            .values(id, question, answer, tags.toTypedArray(), enabled, createdBy, now, now)
            .returning(fp.ID, fp.QUESTION, fp.ANSWER, fp.TAGS, fp.ENABLED, fp.CREATED_BY, fp.CREATED_AT, fp.UPDATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> faqToJson(rows.iterator().next()) }
    }

    fun getFaq(id: String): Future<JsonObject> {
        val query = ctx.select(fp.ID, fp.QUESTION, fp.ANSWER, fp.TAGS, fp.ENABLED, fp.CREATED_BY, fp.CREATED_AT, fp.UPDATED_AT)
            .from(fp)
            .where(fp.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("faq pair not found"))
                else Future.succeededFuture(faqToJson(rows.iterator().next()))
            }
    }

    fun updateFaq(
        id: String,
        question: String? = null,
        answer: String? = null,
        tags: List<String>? = null,
        enabled: Boolean? = null
    ): Future<JsonObject> {
        return getFaqRaw(id).flatMap { existing: JsonObject ->
            val setClauses = mutableListOf<String>()
            val params = mutableListOf<Any?>()
            var idx = 1

            if (question != null) { setClauses.add("question = \${'$'}${idx}"); params.add(question); idx++ }
            if (answer != null) { setClauses.add("answer = \${'$'}${idx}"); params.add(answer); idx++ }
            if (tags != null) { setClauses.add("tags = \${'$'}${idx}"); params.add(tags.toTypedArray()); idx++ }
            if (enabled != null) { setClauses.add("enabled = \${'$'}${idx}"); params.add(enabled); idx++ }

            if (setClauses.isEmpty()) return@flatMap Future.succeededFuture(existing)

            setClauses.add("updated_at = \${'$'}${idx}"); params.add(OffsetDateTime.now()); idx++

            val sql = """UPDATE faq_pairs
                         SET ${setClauses.joinToString(", ")}
                         WHERE id = \${'$'}${idx}
                         RETURNING id, question, answer, tags, enabled, created_by, created_at, updated_at""".trimIndent()
            params.add(id)

            val tuple = Tuple.tuple()
            for (p in params) {
                when (p) {
                    is String -> tuple.addString(p)
                    is Boolean -> tuple.addBoolean(p)
                    is OffsetDateTime -> tuple.addOffsetDateTime(p)
                    is Array<*> -> tuple.addValue(p)
                    else -> tuple.addValue(p)
                }
            }

            pool.preparedQuery(sql)
                .execute(tuple)
                .flatMap { rows: RowSet<Row> ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("faq pair not found"))
                    else Future.succeededFuture(faqToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteFaq(id: String): Future<Void> {
        val query = ctx.deleteFrom(fp).where(fp.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null as Void? }
    }

    // ==============================
    // Preventive Push Rules
    // ==============================

    fun listPushRules(
        enabled: Boolean? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()

        if (enabled != null) {
            conditions.add(ppr.ENABLED.eq(enabled))
        }

        val countQuery = ctx.select(count().`as`("total")).from(ppr).where(conditions)
        val dataQuery = ctx.select(ppr.ID, ppr.NAME, ppr.TRIGGER_METRIC, ppr.THRESHOLD, ppr.TARGET_POSITIONS, ppr.TARGET_COURSE_ID, ppr.ENABLED, ppr.EXTRA, ppr.CREATED_AT, ppr.UPDATED_AT)
            .from(ppr)
            .where(conditions)
            .orderBy(ppr.CREATED_AT.desc())
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
                            records.add(pushRuleToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createPushRule(
        name: String,
        triggerMetric: String,
        threshold: Double,
        targetPositions: List<String> = emptyList(),
        targetCourseId: String? = null,
        enabled: Boolean = true,
        extra: JsonObject = JsonObject()
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val sql = """INSERT INTO preventive_push_rules (id, name, trigger_metric, threshold, target_positions, target_course_id, enabled, extra, created_at, updated_at)
                     VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8::jsonb, ${'$'}9, ${'$'}10)
                     RETURNING id, name, trigger_metric, threshold, target_positions, target_course_id, enabled, extra, created_at, updated_at""".trimIndent()
        val tuple = Tuple.of(id, name, triggerMetric, threshold, targetPositions.toTypedArray(), targetCourseId ?: "", enabled, extra.encode(), now, now)
        return pool.preparedQuery(sql)
            .execute(tuple)
            .map { rows -> pushRuleToJson(rows.iterator().next()) }
    }

    fun getPushRule(id: String): Future<JsonObject> {
        val query = ctx.select(ppr.ID, ppr.NAME, ppr.TRIGGER_METRIC, ppr.THRESHOLD, ppr.TARGET_POSITIONS, ppr.TARGET_COURSE_ID, ppr.ENABLED, ppr.EXTRA, ppr.CREATED_AT, ppr.UPDATED_AT)
            .from(ppr)
            .where(ppr.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("push rule not found"))
                else Future.succeededFuture(pushRuleToJson(rows.iterator().next()))
            }
    }

    fun updatePushRule(
        id: String,
        name: String? = null,
        triggerMetric: String? = null,
        threshold: Double? = null,
        targetPositions: List<String>? = null,
        targetCourseId: String? = null,
        enabled: Boolean? = null,
        extra: JsonObject? = null
    ): Future<JsonObject> {
        return getPushRuleRaw(id).flatMap { existing: JsonObject ->
            val setClauses = mutableListOf<String>()
            val params = mutableListOf<Any?>()
            var idx = 1

            if (name != null) { setClauses.add("name = \${'$'}${idx}"); params.add(name); idx++ }
            if (triggerMetric != null) { setClauses.add("trigger_metric = \${'$'}${idx}"); params.add(triggerMetric); idx++ }
            if (threshold != null) { setClauses.add("threshold = \${'$'}${idx}"); params.add(threshold); idx++ }
            if (targetPositions != null) { setClauses.add("target_positions = \${'$'}${idx}"); params.add(targetPositions.toTypedArray()); idx++ }
            if (targetCourseId != null) { setClauses.add("target_course_id = \${'$'}${idx}"); params.add(targetCourseId); idx++ }
            if (enabled != null) { setClauses.add("enabled = \${'$'}${idx}"); params.add(enabled); idx++ }
            if (extra != null) { setClauses.add("extra = \${'$'}${idx}::jsonb"); params.add(extra.encode()); idx++ }

            if (setClauses.isEmpty()) return@flatMap Future.succeededFuture(existing)

            setClauses.add("updated_at = \${'$'}${idx}"); params.add(OffsetDateTime.now()); idx++

            val sql = """UPDATE preventive_push_rules
                         SET ${setClauses.joinToString(", ")}
                         WHERE id = \${'$'}${idx}
                         RETURNING id, name, trigger_metric, threshold, target_positions, target_course_id, enabled, extra, created_at, updated_at""".trimIndent()
            params.add(id)

            val tuple = Tuple.tuple()
            for (p in params) {
                when (p) {
                    is String -> tuple.addString(p)
                    is Boolean -> tuple.addBoolean(p)
                    is Double -> tuple.addDouble(p)
                    is OffsetDateTime -> tuple.addOffsetDateTime(p)
                    is Array<*> -> tuple.addValue(p)
                    else -> tuple.addValue(p)
                }
            }

            pool.preparedQuery(sql)
                .execute(tuple)
                .flatMap { rows: RowSet<Row> ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("push rule not found"))
                    else Future.succeededFuture(pushRuleToJson(rows.iterator().next()))
                }
        }
    }

    fun deletePushRule(id: String): Future<Void> {
        val query = ctx.deleteFrom(ppr).where(ppr.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null as Void? }
    }

    // ==============================
    // Helpers
    // ==============================

    private fun getFaqRaw(id: String): Future<JsonObject> {
        val query = ctx.select(fp.ID, fp.QUESTION, fp.ANSWER, fp.TAGS, fp.ENABLED, fp.CREATED_BY, fp.CREATED_AT, fp.UPDATED_AT)
            .from(fp)
            .where(fp.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("faq pair not found"))
                else Future.succeededFuture(faqToJson(rows.iterator().next()))
            }
    }

    private fun getPushRuleRaw(id: String): Future<JsonObject> {
        val query = ctx.select(ppr.ID, ppr.NAME, ppr.TRIGGER_METRIC, ppr.THRESHOLD, ppr.TARGET_POSITIONS, ppr.TARGET_COURSE_ID, ppr.ENABLED, ppr.EXTRA, ppr.CREATED_AT, ppr.UPDATED_AT)
            .from(ppr)
            .where(ppr.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("push rule not found"))
                else Future.succeededFuture(pushRuleToJson(rows.iterator().next()))
            }
    }

    companion object {
        fun qaLogToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("user_id", row.getValue("user_id")?.toString())
                .put("question", row.getValue("question")?.toString())
                .put("answer", row.getValue("answer")?.toString())
                .put("sources", row.getValue("sources")?.let { JsonObject(it.toString()) })
                .put("feedback", row.getValue("feedback")?.toString())
                .put("created_at", row.getValue("created_at")?.toString())
        }

        fun faqToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("question", row.getValue("question")?.toString())
                .put("answer", row.getValue("answer")?.toString())
                .put("tags", arrayToJsonArray(row.getValue("tags")))
                .put("enabled", row.getValue("enabled")?.let { (it as Boolean) })
                .put("created_by", row.getValue("created_by")?.toString())
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }

        fun pushRuleToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("name", row.getValue("name")?.toString())
                .put("trigger_metric", row.getValue("trigger_metric")?.toString())
                .put("threshold", row.getValue("threshold")?.let { (it as Number).toDouble() })
                .put("target_positions", arrayToJsonArray(row.getValue("target_positions")))
                .put("target_course_id", row.getValue("target_course_id")?.toString())
                .put("enabled", row.getValue("enabled")?.let { (it as Boolean) })
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
