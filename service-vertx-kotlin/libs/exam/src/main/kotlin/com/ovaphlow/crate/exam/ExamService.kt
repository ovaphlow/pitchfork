package com.ovaphlow.crate.exam

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.Questions
import com.ovaphlow.crate.database.gen.public_.tables.ExamPapers
import com.ovaphlow.crate.database.gen.public_.tables.ExamRecords
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

class ExamService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(ExamService::class.java)
    private val q = Questions.QUESTIONS
    private val ep = ExamPapers.EXAM_PAPERS
    private val er = ExamRecords.EXAM_RECORDS

    // ==========================================
    // Questions
    // ==========================================

    fun listQuestions(
        type: String? = null,
        difficulty: Int? = null,
        tag: String? = null,
        queryStr: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()

        if (!type.isNullOrBlank()) {
            conditions.add(q.TYPE.eq(type))
        }
        if (difficulty != null) {
            conditions.add(q.DIFFICULTY.eq(difficulty.toShort()))
        }
        if (!tag.isNullOrBlank()) {
            conditions.add(org.jooq.impl.DSL.condition("{0} = ANY({1})", org.jooq.impl.DSL.value(tag), q.TAGS))
        }
        if (!queryStr.isNullOrBlank()) {
            conditions.add(org.jooq.impl.DSL.condition("{0} ->> 'title' ILIKE {1}", q.CONTENT, org.jooq.impl.DSL.value("%$queryStr%")))
        }

        val countQuery = ctx.select(count().`as`("total")).from(q).where(conditions)
        val dataQuery = ctx.select(q.ID, q.TYPE, q.DIFFICULTY, q.TAGS, q.CONTENT, q.OPTIONS, q.ANSWER, q.EXPLANATION, q.CREATED_BY, q.CREATED_AT, q.UPDATED_AT)
            .from(q)
            .where(conditions)
            .orderBy(q.CREATED_AT.desc())
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
                            records.add(questionToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createQuestion(
        type: String,
        difficulty: Int,
        tags: JsonArray,
        content: JsonObject,
        options: JsonObject,
        answer: JsonObject,
        explanation: String = "",
        createdBy: String = ""
    ): Future<JsonObject> {
        val validTypes = listOf("单选", "多选", "判断", "填空", "看图识错")
        if (type !in validTypes) {
            return Future.failedFuture(IllegalArgumentException("invalid type: $type, must be one of $validTypes"))
        }
        if (difficulty < 1 || difficulty > 5) {
            return Future.failedFuture(IllegalArgumentException("difficulty must be between 1 and 5"))
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val qInsert = ctx.insertInto(q)
            .columns(q.ID, q.TYPE, q.DIFFICULTY, q.TAGS, q.CONTENT, q.OPTIONS, q.ANSWER, q.EXPLANATION, q.CREATED_BY, q.CREATED_AT, q.UPDATED_AT)
            .values(id, type, difficulty.toShort(), tags.toList().map { it?.toString() ?: "" }.toTypedArray(), JSONB.valueOf(content.encode()), JSONB.valueOf(options.encode()), JSONB.valueOf(answer.encode()), explanation, createdBy, now, now)
        @Suppress("UNCHECKED_CAST")
        val query = (qInsert as org.jooq.InsertSetMoreStep<*>)
            .returning(q.ID, q.TYPE, q.DIFFICULTY, q.TAGS, q.CONTENT, q.OPTIONS, q.ANSWER, q.EXPLANATION, q.CREATED_BY, q.CREATED_AT, q.UPDATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> questionToJson(rows.iterator().next()) }
    }

    fun importQuestions(questions: JsonArray): Future<JsonArray> {
        val results = JsonArray()
        var chain: Future<*> = Future.succeededFuture<Void>(null)

        for (i in 0 until questions.size()) {
            val qObj = questions.getJsonObject(i)
            val type = qObj.getString("type", "")
            val difficulty = qObj.getInteger("difficulty", 1)
            val tags = qObj.getJsonArray("tags", JsonArray())
            val content = qObj.getJsonObject("content", JsonObject())
            val options = qObj.getJsonObject("options", JsonObject())
            val answer = qObj.getJsonObject("answer", JsonObject())
            val explanation = qObj.getString("explanation", "")
            val createdBy = qObj.getString("created_by", "")

            chain = chain.flatMap {
                createQuestion(type, difficulty, tags, content, options, answer, explanation, createdBy)
                    .map { result ->
                        results.add(result)
                        null as Void?
                    }
                    .otherwise { err ->
                        log.error("import question failed at index $i", err)
                        results.add(JsonObject().put("error", err.message).put("index", i))
                        null as Void?
                    }
            }
        }

        return chain.map { results }
    }

    fun getQuestion(id: String): Future<JsonObject> {
        val query = ctx.select(q.ID, q.TYPE, q.DIFFICULTY, q.TAGS, q.CONTENT, q.OPTIONS, q.ANSWER, q.EXPLANATION, q.CREATED_BY, q.CREATED_AT, q.UPDATED_AT)
            .from(q)
            .where(q.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("question not found"))
                else Future.succeededFuture(questionToJson(rows.iterator().next()))
            }
    }

    fun updateQuestion(
        id: String,
        type: String? = null,
        difficulty: Int? = null,
        tags: JsonArray? = null,
        content: JsonObject? = null,
        options: JsonObject? = null,
        answer: JsonObject? = null,
        explanation: String? = null
    ): Future<JsonObject> {
        return getQuestion(id).flatMap { existing ->
            val newType = type ?: existing.getString("type")
            if (type != null) {
                val validTypes = listOf("单选", "多选", "判断", "填空", "看图识错")
                if (type !in validTypes) {
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid type: $type, must be one of $validTypes"))
                }
            }
            val newDifficulty = difficulty ?: existing.getInteger("difficulty")
            if (difficulty != null && (difficulty < 1 || difficulty > 5)) {
                return@flatMap Future.failedFuture(IllegalArgumentException("difficulty must be between 1 and 5"))
            }
            val newTags = tags ?: existing.getJsonArray("tags") ?: JsonArray()
            val newContent = content ?: existing.getJsonObject("content") ?: JsonObject()
            val newOptions = options ?: existing.getJsonObject("options") ?: JsonObject()
            val newAnswer = answer ?: existing.getJsonObject("answer") ?: JsonObject()
            val newExplanation = explanation ?: existing.getString("explanation") ?: ""
            val now = OffsetDateTime.now()

            val sql = """UPDATE questions
                         SET type = ${'$'}1, difficulty = ${'$'}2, tags = ${'$'}3, content = ${'$'}4::jsonb,
                             options = ${'$'}5::jsonb, answer = ${'$'}6::jsonb, explanation = ${'$'}7, updated_at = ${'$'}8
                         WHERE id = ${'$'}9
                         RETURNING id, type, difficulty, tags, content, options, answer, explanation, created_by, created_at, updated_at""".trimIndent()
            val tuple = Tuple.of(newType, newDifficulty.toShort(), newTags.toList().map { it?.toString() ?: "" }.toTypedArray(), newContent.encode(), newOptions.encode(), newAnswer.encode(), newExplanation, now, id)

            pool.preparedQuery(sql)
                .execute(tuple)
                .flatMap { rows: RowSet<Row> ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("question not found"))
                    else Future.succeededFuture(questionToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteQuestion(id: String): Future<Void> {
        val query = ctx.deleteFrom(q).where(q.ID.eq(id)).returning(q.ID)
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("question not found"))
                else Future.succeededFuture(null as Void?)
            }
    }

    // ==========================================
    // Exam Papers
    // ==========================================

    fun listPapers(
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val countSql = """SELECT count(*) AS total FROM exam_papers""".trimIndent()
        val dataSql = """SELECT id, title, duration_minutes, pass_score, generation_strategy, anti_cheat_config, extra_rules, created_by, created_at
                         FROM exam_papers
                         ORDER BY created_at DESC
                         LIMIT ${'$'}1 OFFSET ${'$'}2""".trimIndent()

        return pool.preparedQuery(countSql)
            .execute()
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(dataSql)
                    .execute(Tuple.of(limit, offset))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) {
                            records.add(paperToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createPaper(
        title: String,
        durationMinutes: Int,
        passScore: Int,
        generationStrategy: JsonObject = JsonObject(),
        antiCheatConfig: JsonObject = JsonObject(),
        extraRules: JsonObject = JsonObject(),
        createdBy: String = ""
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val sql = """INSERT INTO exam_papers (id, title, duration_minutes, pass_score, generation_strategy, anti_cheat_config, extra_rules, created_by, created_at)
                     VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5::jsonb, ${'$'}6::jsonb, ${'$'}7::jsonb, ${'$'}8, ${'$'}9)
                     RETURNING id, title, duration_minutes, pass_score, generation_strategy, anti_cheat_config, extra_rules, created_by, created_at""".trimIndent()
        val tuple = Tuple.of(id, title, durationMinutes, passScore, generationStrategy.encode(), antiCheatConfig.encode(), extraRules.encode(), createdBy, now)
        return pool.preparedQuery(sql)
            .execute(tuple)
            .map { rows -> paperToJson(rows.iterator().next()) }
    }

    fun getPaper(id: String): Future<JsonObject> {
        val sql = """SELECT id, title, duration_minutes, pass_score, generation_strategy, anti_cheat_config, extra_rules, created_by, created_at, updated_at
                     FROM exam_papers
                     WHERE id = ${'$'}1""".trimIndent()
        return pool.preparedQuery(sql)
            .execute(Tuple.of(id))
            .flatMap { rows: RowSet<Row> ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("paper not found"))
                else Future.succeededFuture(paperToJson(rows.iterator().next()))
            }
    }

    fun updatePaper(
        id: String,
        title: String? = null,
        durationMinutes: Int? = null,
        passScore: Int? = null,
        generationStrategy: JsonObject? = null,
        antiCheatConfig: JsonObject? = null,
        extraRules: JsonObject? = null
    ): Future<JsonObject> {
        return getPaper(id).flatMap { existing ->
            val newTitle = title ?: existing.getString("title")
            val newDurationMinutes = durationMinutes ?: existing.getInteger("duration_minutes")
            val newPassScore = passScore ?: existing.getInteger("pass_score")
            val newGenerationStrategy = generationStrategy ?: existing.getJsonObject("generation_strategy") ?: JsonObject()
            val newAntiCheatConfig = antiCheatConfig ?: existing.getJsonObject("anti_cheat_config") ?: JsonObject()
            val newExtraRules = extraRules ?: existing.getJsonObject("extra_rules") ?: JsonObject()
            val now = OffsetDateTime.now()

            val sql = """UPDATE exam_papers
                         SET title = ${'$'}1, duration_minutes = ${'$'}2, pass_score = ${'$'}3,
                             generation_strategy = ${'$'}4::jsonb, anti_cheat_config = ${'$'}5::jsonb,
                             extra_rules = ${'$'}6::jsonb
                         WHERE id = ${'$'}7
                         RETURNING id, title, duration_minutes, pass_score, generation_strategy, anti_cheat_config, extra_rules, created_by, created_at""".trimIndent()
            val tuple = Tuple.of(newTitle, newDurationMinutes, newPassScore, newGenerationStrategy.encode(), newAntiCheatConfig.encode(), newExtraRules.encode(), id)

            pool.preparedQuery(sql)
                .execute(tuple)
                .flatMap { rows: RowSet<Row> ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("paper not found"))
                    else Future.succeededFuture(paperToJson(rows.iterator().next()))
                }
        }
    }

    fun deletePaper(id: String): Future<Void> {
        val checkRecords = ctx.select(count().`as`("cnt")).from(er).where(er.PAPER_ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(checkRecords))
            .execute(DatabaseConfig.tuple(checkRecords))
            .flatMap { rows ->
                val count = rows.iterator().next().getLong("cnt") ?: 0L
                if (count > 0) {
                    Future.failedFuture(IllegalArgumentException("cannot delete paper with existing exam records"))
                } else {
                    val query = ctx.deleteFrom(ep).where(ep.ID.eq(id)).returning(ep.ID)
                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .flatMap { delRows ->
                            if (delRows.size() == 0) Future.failedFuture(NotFoundException("paper not found"))
                            else Future.succeededFuture(null as Void?)
                        }
                }
            }
    }

    /**
     * Generate questions for a paper based on its generation_strategy.
     */
    fun generatePaperQuestions(paperId: String): Future<JsonObject> {
        return getPaper(paperId).flatMap { paper ->
            val strategy = paper.getJsonObject("generation_strategy") ?: JsonObject()
            val sections = strategy.getJsonArray("sections") ?: JsonArray()
            if (sections.size() == 0) {
                return@flatMap Future.failedFuture(IllegalArgumentException("generation_strategy has no sections"))
            }

            var chain: Future<JsonArray> = Future.succeededFuture(JsonArray())
            for (i in 0 until sections.size()) {
                val section = sections.getJsonObject(i)
                val tag = section.getString("tag", "")
                val type = section.getString("type", "")
                val difficulty = section.getInteger("difficulty")
                val count = section.getInteger("count", 1)

                chain = chain.flatMap { accumulated ->
                    pickQuestions(tag, type, difficulty, count).map { picked ->
                        for (j in 0 until picked.size()) {
                            accumulated.add(picked.getJsonObject(j))
                        }
                        accumulated
                    }
                }
            }

            chain.map { questions ->
                val result = JsonArray()
                for (i in 0 until questions.size()) {
                    val qObj = questions.getJsonObject(i)
                    result.add(JsonObject()
                        .put("id", qObj.getString("id"))
                        .put("type", qObj.getString("type"))
                        .put("difficulty", qObj.getInteger("difficulty"))
                        .put("tags", qObj.getJsonArray("tags"))
                        .put("content", qObj.getJsonObject("content"))
                        .put("options", qObj.getJsonObject("options"))
                    )
                }
                JsonObject().put("paper", paper).put("questions", result)
            }
        }
    }

    private fun pickQuestions(tag: String, type: String, difficulty: Int?, count: Int): Future<JsonArray> {
        val conditions = mutableListOf<Condition>()

        if (tag.isNotBlank()) {
            conditions.add(org.jooq.impl.DSL.condition("{0} = ANY({1})", org.jooq.impl.DSL.value(tag), q.TAGS))
        }
        if (type.isNotBlank()) {
            conditions.add(q.TYPE.eq(type))
        }
        if (difficulty != null) {
            conditions.add(q.DIFFICULTY.eq(difficulty.toShort()))
        }

        val query = ctx.select(q.ID, q.TYPE, q.DIFFICULTY, q.TAGS, q.CONTENT, q.OPTIONS, q.ANSWER, q.EXPLANATION, q.CREATED_BY, q.CREATED_AT, q.UPDATED_AT)
            .from(q)
            .where(conditions)
            .orderBy(org.jooq.impl.DSL.rand())
            .limit(count)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val records = JsonArray()
                for (row in rows) {
                    records.add(questionToJson(row))
                }
                records
            }
    }

    // ==========================================
    // Exam Records
    // ==========================================

    fun listRecords(
        employeeId: String? = null,
        paperId: String? = null,
        passed: Boolean? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()

        if (!employeeId.isNullOrBlank()) {
            conditions.add(er.EMPLOYEE_ID.eq(employeeId))
        }
        if (!paperId.isNullOrBlank()) {
            conditions.add(er.PAPER_ID.eq(paperId))
        }
        if (passed != null) {
            conditions.add(er.PASSED.eq(passed))
        }

        val countQuery = ctx.select(count().`as`("total")).from(er).where(conditions)
        val dataQuery = ctx.select(
                er.ID, er.EMPLOYEE_ID, er.PAPER_ID, er.START_TIME, er.END_TIME, er.SCORE, er.PASSED, er.ANSWERS_SNAPSHOT, er.CHEAT_FLAGS,
                ep.TITLE.`as`("paper_title")
            )
            .from(er)
            .leftJoin(ep).on(ep.ID.eq(er.PAPER_ID))
            .where(conditions)
            .orderBy(er.START_TIME.desc())
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
                            records.add(recordToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    /**
     * Start an exam: create a record with start_time=NOW, generate questions from paper's strategy.
     */
    fun startExam(employeeId: String, paperId: String): Future<JsonObject> {
        return getPaper(paperId).flatMap { paper ->
            val strategy = paper.getJsonObject("generation_strategy") ?: JsonObject()
            val sections = strategy.getJsonArray("sections") ?: JsonArray()
            if (sections.size() == 0) {
                return@flatMap Future.failedFuture(IllegalArgumentException("paper generation_strategy has no sections"))
            }

            var chain: Future<JsonArray> = Future.succeededFuture(JsonArray())
            for (i in 0 until sections.size()) {
                val section = sections.getJsonObject(i)
                chain = chain.flatMap { accumulated ->
                    pickQuestions(
                        section.getString("tag", ""),
                        section.getString("type", ""),
                        section.getInteger("difficulty"),
                        section.getInteger("count", 1)
                    ).map { picked ->
                        for (j in 0 until picked.size()) {
                            accumulated.add(picked.getJsonObject(j))
                        }
                        accumulated
                    }
                }
            }

            chain.flatMap { questions ->
                val recordId = Ulid.generate()
                val now = OffsetDateTime.now()

                val snapshot = JsonArray()
                for (i in 0 until questions.size()) {
                    val qObj = questions.getJsonObject(i)
                    snapshot.add(JsonObject()
                        .put("question_id", qObj.getString("id"))
                        .put("type", qObj.getString("type"))
                        .put("difficulty", qObj.getInteger("difficulty"))
                        .put("tags", qObj.getJsonArray("tags"))
                        .put("content", qObj.getJsonObject("content"))
                        .put("options", qObj.getJsonObject("options"))
                        .put("score_per_question", 100 / questions.size())
                        .put("answer", null)
                        .put("user_answer", null)
                        .put("correct", null)
                    )
                }

                val query = ctx.insertInto(er)
                    .columns(er.ID, er.EMPLOYEE_ID, er.PAPER_ID, er.START_TIME, er.ANSWERS_SNAPSHOT, er.CHEAT_FLAGS)
                    .values(recordId, employeeId, paperId, now, JSONB.valueOf(snapshot.encode()), JSONB.valueOf(JsonObject().encode()))
                    .returning(er.ID, er.EMPLOYEE_ID, er.PAPER_ID, er.START_TIME, er.END_TIME, er.SCORE, er.PASSED, er.ANSWERS_SNAPSHOT, er.CHEAT_FLAGS)

                pool.preparedQuery(DatabaseConfig.sql(query))
                    .execute(DatabaseConfig.tuple(query))
                    .map { rows ->
                        val record = recordToJson(rows.iterator().next())
                        val questionsForUser = JsonArray()
                        for (i in 0 until snapshot.size()) {
                            val sq = snapshot.getJsonObject(i)
                            questionsForUser.add(JsonObject()
                                .put("question_id", sq.getString("question_id"))
                                .put("type", sq.getString("type"))
                                .put("difficulty", sq.getInteger("difficulty"))
                                .put("tags", sq.getJsonArray("tags"))
                                .put("content", sq.getJsonObject("content"))
                                .put("options", sq.getJsonObject("options"))
                                .put("score_per_question", sq.getInteger("score_per_question"))
                            )
                        }
                        JsonObject()
                            .put("record", record)
                            .put("questions", questionsForUser)
                    }
            }
        }
    }

    fun getRecord(id: String): Future<JsonObject> {
        val query = ctx.select(
                er.ID, er.EMPLOYEE_ID, er.PAPER_ID, er.START_TIME, er.END_TIME, er.SCORE, er.PASSED, er.ANSWERS_SNAPSHOT, er.CHEAT_FLAGS,
                ep.TITLE.`as`("paper_title")
            )
            .from(er)
            .leftJoin(ep).on(ep.ID.eq(er.PAPER_ID))
            .where(er.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("exam record not found"))
                else Future.succeededFuture(recordToJson(rows.iterator().next()))
            }
    }

    /**
     * Submit answers for an exam.
     */
    fun submitExam(id: String, answers: JsonArray): Future<JsonObject> {
        return getRecord(id).flatMap { record ->
            if (record.getString("end_time") != null && record.getString("end_time") != "") {
                return@flatMap Future.failedFuture(IllegalArgumentException("exam already submitted"))
            }

            val paperId = record.getString("paper_id") ?: return@flatMap Future.failedFuture(IllegalArgumentException("record has no paper_id"))
            getPaper(paperId).flatMap { paper ->
                val passScore = paper.getInteger("pass_score") ?: 60
                val snapshot = record.getJsonArray("answers_snapshot") ?: JsonArray()

                val answerMap = mutableMapOf<String, JsonObject>()
                for (i in 0 until answers.size()) {
                    val a = answers.getJsonObject(i)
                    val qid = a.getString("question_id")
                    if (qid != null) {
                        answerMap[qid] = a
                    }
                }

                var totalScore = 0
                val updatedSnapshot = JsonArray()

                for (i in 0 until snapshot.size()) {
                    val sq = snapshot.getJsonObject(i)
                    val qid = sq.getString("question_id")
                    val scorePerQ = sq.getInteger("score_per_question") ?: 0
                    val correctAnswer = sq.getValue("answer")

                    val submitted = answerMap[qid]
                    val userAnswer = submitted?.getValue("answer")

                    val isCorrect = if (userAnswer != null && correctAnswer != null) {
                        userAnswer.toString() == correctAnswer.toString()
                    } else false

                    if (isCorrect) {
                        totalScore += scorePerQ
                    }

                    updatedSnapshot.add(JsonObject()
                        .put("question_id", qid)
                        .put("type", sq.getString("type"))
                        .put("difficulty", sq.getInteger("difficulty"))
                        .put("tags", sq.getJsonArray("tags"))
                        .put("content", sq.getJsonObject("content"))
                        .put("options", sq.getJsonObject("options"))
                        .put("score_per_question", scorePerQ)
                        .put("answer", correctAnswer)
                        .put("user_answer", userAnswer)
                        .put("correct", isCorrect)
                    )
                }

                val passed = totalScore >= passScore
                val now = OffsetDateTime.now()

                val sql = """UPDATE exam_records
                             SET end_time = ${'$'}1, score = ${'$'}2, passed = ${'$'}3,
                                 answers_snapshot = ${'$'}4::jsonb
                             WHERE id = ${'$'}5
                             RETURNING id, employee_id, paper_id, start_time, end_time, score, passed, answers_snapshot, cheat_flags""".trimIndent()
                val tuple = Tuple.of(now, totalScore, passed, updatedSnapshot.encode(), id)

                pool.preparedQuery(sql)
                    .execute(tuple)
                    .map { rows ->
                        JsonObject()
                            .put("record", recordToJson(rows.iterator().next()))
                            .put("score", totalScore)
                            .put("passed", passed)
                            .put("pass_score", passScore)
                    }
            }
        }
    }

    fun getExamResult(id: String): Future<JsonObject> {
        return getRecord(id).flatMap { record ->
            if (record.getString("end_time") == null || record.getString("end_time") == "") {
                return@flatMap Future.failedFuture(IllegalArgumentException("exam not yet submitted"))
            }
            val paperId = record.getString("paper_id") ?: ""
            getPaper(paperId).map { paper ->
                val passScore = paper.getInteger("pass_score") ?: 60
                val snapshot = record.getJsonArray("answers_snapshot") ?: JsonArray()

                var correctCount = 0
                var wrongCount = 0
                val details = JsonArray()
                for (i in 0 until snapshot.size()) {
                    val sq = snapshot.getJsonObject(i)
                    val isCorrect = sq.getBoolean("correct") ?: false
                    if (isCorrect) correctCount++ else wrongCount++
                    details.add(sq)
                }

                JsonObject()
                    .put("record", record)
                    .put("paper", paper)
                    .put("score", record.getInteger("score"))
                    .put("passed", record.getBoolean("passed"))
                    .put("pass_score", passScore)
                    .put("total_questions", snapshot.size())
                    .put("correct_count", correctCount)
                    .put("wrong_count", wrongCount)
                    .put("details", details)
            }
        }
    }

    // ==========================================
    // JSON Conversions
    // ==========================================

    companion object {
        fun questionToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("type", row.getValue("type")?.toString())
                .put("difficulty", row.getValue("difficulty") as? Int ?: 1)
                .put("tags", arrayToJsonArray(row.getValue("tags")))
                .put("content", row.getValue("content") as? JsonObject ?: JsonObject())
                .put("options", row.getValue("options") as? JsonArray ?: row.getValue("options") as? JsonObject ?: JsonObject())
                .put("answer", row.getValue("answer") as? JsonObject ?: JsonObject())
                .put("explanation", row.getValue("explanation")?.toString() ?: "")
                .put("created_by", row.getValue("created_by")?.toString() ?: "")
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }

        fun paperToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("title", row.getValue("title")?.toString())
                .put("duration_minutes", row.getValue("duration_minutes") as? Int ?: 0)
                .put("pass_score", row.getValue("pass_score") as? Int ?: 60)
                .put("generation_strategy", row.getValue("generation_strategy") as? JsonObject ?: JsonObject())
                .put("anti_cheat_config", row.getValue("anti_cheat_config") as? JsonObject ?: JsonObject())
                .put("extra_rules", row.getValue("extra_rules") as? JsonObject ?: JsonObject())
                .put("created_by", row.getValue("created_by")?.toString() ?: "")
                .put("created_at", row.getValue("created_at")?.toString())
        }

        fun recordToJson(row: Row): JsonObject {
            val obj = JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("employee_id", row.getValue("employee_id")?.toString())
                .put("paper_id", row.getValue("paper_id")?.toString())
                .put("start_time", row.getValue("start_time")?.toString())
                .put("end_time", row.getValue("end_time")?.toString() ?: "")
                .put("score", row.getValue("score") as? Int ?: 0)
                .put("passed", row.getValue("passed") as? Boolean ?: false)
                .put("answers_snapshot", row.getValue("answers_snapshot") as? JsonObject ?: row.getValue("answers_snapshot") as? JsonArray ?: JsonArray())
                .put("cheat_flags", row.getValue("cheat_flags") as? JsonObject ?: JsonObject())
            val paperTitle = row.getValue("paper_title")?.toString()
            if (paperTitle != null) {
                obj.put("paper_title", paperTitle)
            }
            return obj
        }

        @Suppress("UNCHECKED_CAST")
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
