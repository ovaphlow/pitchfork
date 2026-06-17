package com.ovaphlow.crate.training

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.Courses
import com.ovaphlow.crate.database.gen.public_.tables.CourseChapters
import com.ovaphlow.crate.database.gen.public_.tables.TrainingAssignments
import com.ovaphlow.crate.database.gen.public_.tables.LearningProgress
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import org.jooq.impl.DSL.`when`
import org.jooq.impl.DSL.count

class TrainingService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(TrainingService::class.java)
    private val c = Courses.COURSES
    private val cc = CourseChapters.COURSE_CHAPTERS
    private val ta = TrainingAssignments.TRAINING_ASSIGNMENTS
    private val lp = LearningProgress.LEARNING_PROGRESS

    // ==========================================
    // Courses
    // ==========================================

    fun listCourses(
        status: String? = null,
        type: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        if (!status.isNullOrBlank()) {
            conditions.add(c.STATUS.eq(status))
        }
        if (!type.isNullOrBlank()) {
            conditions.add(c.TYPE.eq(type))
        }

        val countQuery = ctx.select(count().`as`("total")).from(c).where(conditions)
        val dataQuery = ctx.select(
                c.ID, c.TITLE, c.TYPE, c.COVER_IMAGE, c.TARGET_POSITIONS,
                c.COMPLETION_RULES, org.jooq.impl.DSL.field("metadata", org.jooq.impl.SQLDataType.JSONB), c.STATUS, c.CREATED_BY, c.CREATED_AT, c.UPDATED_AT
            )
            .from(c)
            .where(conditions)
            .orderBy(c.CREATED_AT.desc())
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
                            records.add(courseToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createCourse(
        title: String,
        type: String,
        coverImage: String = "",
        targetPositions: JsonArray = JsonArray(),
        completionRules: JsonObject = JsonObject(),
        metadata: JsonObject = JsonObject(),
        status: String = "启用",
        createdBy: String = ""
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val validTypes = listOf("线上", "线下实操")
        if (type !in validTypes) {
            return Future.failedFuture(IllegalArgumentException("invalid type: $type, must be one of $validTypes"))
        }
        val validStatuses = listOf("启用", "停用")
        if (status !in validStatuses) {
            return Future.failedFuture(IllegalArgumentException("invalid status: $status, must be one of $validStatuses"))
        }

        val query = ctx.insertInto(c)
            .columns(c.ID, c.TITLE, c.TYPE, c.COVER_IMAGE, c.TARGET_POSITIONS, c.COMPLETION_RULES, c.METADATA, c.STATUS, c.CREATED_BY, c.CREATED_AT, c.UPDATED_AT)
            .values(id, title, type, coverImage, targetPositions.toList().map { it?.toString() ?: "" }.toTypedArray(), JSONB.valueOf(completionRules.encode()), JSONB.valueOf(metadata.encode()), status, createdBy, now, now)
            .returning(c.ID, c.TITLE, c.TYPE, c.COVER_IMAGE, c.TARGET_POSITIONS, c.COMPLETION_RULES, c.METADATA, c.STATUS, c.CREATED_BY, c.CREATED_AT, c.UPDATED_AT)
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> courseToJson(rows.iterator().next()) }
    }

    fun getCourse(id: String): Future<JsonObject> {
        val query = ctx.select(
                c.ID, c.TITLE, c.TYPE, c.COVER_IMAGE, c.TARGET_POSITIONS,
                c.COMPLETION_RULES, org.jooq.impl.DSL.field("metadata", org.jooq.impl.SQLDataType.JSONB), c.STATUS, c.CREATED_BY, c.CREATED_AT, c.UPDATED_AT
            )
            .from(c)
            .where(c.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("course not found"))
                else Future.succeededFuture(courseToJson(rows.iterator().next()))
            }
    }

    fun updateCourse(
        id: String,
        title: String? = null,
        type: String? = null,
        coverImage: String? = null,
        targetPositions: JsonArray? = null,
        completionRules: JsonObject? = null,
        metadata: JsonObject? = null,
        status: String? = null
    ): Future<JsonObject> {
        return getCourse(id).flatMap { existing: JsonObject ->
            val newType = type ?: existing.getString("type")
            if (type != null) {
                val validTypes = listOf("线上", "线下实操")
                if (type !in validTypes) {
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid type: $type, must be one of $validTypes"))
                }
            }
            val newStatus = status ?: existing.getString("status")
            if (status != null) {
                val validStatuses = listOf("启用", "停用")
                if (status !in validStatuses) {
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid status: $status, must be one of $validStatuses"))
                }
            }

            val newTitle = title ?: existing.getString("title")
            val newCoverImage = coverImage ?: existing.getString("cover_image") ?: ""
            val newTargetPositions = targetPositions ?: existing.getJsonArray("target_positions") ?: JsonArray()
            val newCompletionRules = completionRules ?: existing.getJsonObject("completion_rules") ?: JsonObject()
            val existingMetadata = existing.getJsonObject("metadata") ?: JsonObject()
            val newMetadata = if (metadata != null) {
                // shallow merge: incoming metadata overrides existing keys
                val merged = existingMetadata.copy()
                metadata.forEach { entry ->
                    merged.put(entry.key, entry.value)
                }
                merged
            } else {
                existingMetadata
            }
            val now = OffsetDateTime.now()

            val query = ctx.update(c)
                .set(c.TITLE, newTitle)
                .set(c.TYPE, newType)
                .set(c.COVER_IMAGE, newCoverImage)
                .set(c.TARGET_POSITIONS, newTargetPositions.toList().map { it?.toString() ?: "" }.toTypedArray())
                .set(c.COMPLETION_RULES, JSONB.valueOf(newCompletionRules.encode()))
                .set(org.jooq.impl.DSL.field("metadata", org.jooq.impl.SQLDataType.JSONB), JSONB.valueOf(newMetadata.encode()))
                .set(c.STATUS, newStatus)
                .set(c.UPDATED_AT, now)
                .where(c.ID.eq(id))
                .returning(c.ID, c.TITLE, c.TYPE, c.COVER_IMAGE, c.TARGET_POSITIONS, c.COMPLETION_RULES, org.jooq.impl.DSL.field("metadata", org.jooq.impl.SQLDataType.JSONB), c.STATUS, c.CREATED_BY, c.CREATED_AT, c.UPDATED_AT)

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("course not found"))
                    else Future.succeededFuture(courseToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteCourse(id: String): Future<Void> {
        val checkChapters = ctx.select(count().`as`("cnt")).from(cc).where(cc.COURSE_ID.eq(id))
        val checkAssignments = ctx.select(count().`as`("cnt")).from(ta).where(ta.COURSE_ID.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(checkChapters))
            .execute(DatabaseConfig.tuple(checkChapters))
            .flatMap { chapterRows ->
                val chapterCount = chapterRows.iterator().next().getLong("cnt") ?: 0L
                if (chapterCount > 0) {
                    Future.failedFuture(IllegalArgumentException("cannot delete course with existing chapters"))
                } else {
                    pool.preparedQuery(DatabaseConfig.sql(checkAssignments))
                        .execute(DatabaseConfig.tuple(checkAssignments))
                        .flatMap { assignmentRows ->
                            val assignmentCount = assignmentRows.iterator().next().getLong("cnt") ?: 0L
                            if (assignmentCount > 0) {
                                Future.failedFuture(IllegalArgumentException("cannot delete course with existing assignments"))
                            } else {
                                val query = ctx.deleteFrom(c).where(c.ID.eq(id))
                                pool.preparedQuery(DatabaseConfig.sql(query))
                                    .execute(DatabaseConfig.tuple(query))
                                    .map { null as Void? }
                            }
                        }
                }
            }
    }

    // ==========================================
    // Chapters
    // ==========================================

    fun listChapters(courseId: String): Future<JsonObject> {
        val checkQuery = ctx.select(c.ID).from(c).where(c.ID.eq(courseId))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("course not found"))
                else {
                    val query = ctx.select(cc.ID, cc.COURSE_ID, cc.SORT_ORDER, cc.TITLE, cc.BLOCKS, cc.QUIZ_CONFIG)
                        .from(cc)
                        .where(cc.COURSE_ID.eq(courseId))
                        .orderBy(cc.SORT_ORDER.asc(), cc.CREATED_AT.asc())

                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .map { dataRows ->
                            val records = JsonArray()
                            for (row in dataRows) {
                                records.add(chapterToJson(row))
                            }
                            JsonObject().put("records", records)
                        }
                }
            }
    }

    fun createChapter(
        courseId: String,
        sortOrder: Int = 0,
        title: String,
        blocks: JsonObject = JsonObject(),
        quizConfig: JsonObject = JsonObject()
    ): Future<JsonObject> {
        val checkQuery = ctx.select(c.ID).from(c).where(c.ID.eq(courseId))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("course not found"))
                else {
                    val id = Ulid.generate()
                    val query = ctx.insertInto(cc)
                        .columns(cc.ID, cc.COURSE_ID, cc.SORT_ORDER, cc.TITLE, cc.BLOCKS, cc.QUIZ_CONFIG)
                        .values(id, courseId, sortOrder.toShort(), title, JSONB.valueOf(blocks.encode()), JSONB.valueOf(quizConfig.encode()))
                        .returning(cc.ID, cc.COURSE_ID, cc.SORT_ORDER, cc.TITLE, cc.BLOCKS, cc.QUIZ_CONFIG)
                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .map { insertRows -> chapterToJson(insertRows.iterator().next()) }
                }
            }
    }

    fun getChapter(id: String): Future<JsonObject> {
        val query = ctx.select(cc.ID, cc.COURSE_ID, cc.SORT_ORDER, cc.TITLE, cc.BLOCKS, cc.QUIZ_CONFIG)
            .from(cc)
            .where(cc.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("chapter not found"))
                else Future.succeededFuture(chapterToJson(rows.iterator().next()))
            }
    }

    fun updateChapter(
        id: String,
        title: String? = null,
        sortOrder: Int? = null,
        blocks: JsonObject? = null,
        quizConfig: JsonObject? = null
    ): Future<JsonObject> {
        return getChapter(id).flatMap { existing: JsonObject ->
            val newTitle = title ?: existing.getString("title")
            val newSortOrder = (sortOrder ?: existing.getInteger("sort_order") ?: 0).toShort()
            val newBlocks = blocks ?: existing.getJsonObject("blocks") ?: JsonObject()
            val newQuizConfig = quizConfig ?: existing.getJsonObject("quiz_config") ?: JsonObject()

            val query = ctx.update(cc)
                .set(cc.TITLE, newTitle)
                .set(cc.SORT_ORDER, newSortOrder)
                .set(cc.BLOCKS, JSONB.valueOf(newBlocks.encode()))
                .set(cc.QUIZ_CONFIG, JSONB.valueOf(newQuizConfig.encode()))
                .where(cc.ID.eq(id))
                .returning(cc.ID, cc.COURSE_ID, cc.SORT_ORDER, cc.TITLE, cc.BLOCKS, cc.QUIZ_CONFIG)

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("chapter not found"))
                    else Future.succeededFuture(chapterToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteChapter(id: String): Future<Void> {
        val checkQuery = ctx.select(count().`as`("cnt")).from(lp).where(lp.CHAPTER_ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                val count = rows.iterator().next().getLong("cnt") ?: 0L
                if (count > 0) {
                    Future.failedFuture(IllegalArgumentException("cannot delete chapter with existing learning progress records"))
                } else {
                    val query = ctx.deleteFrom(cc).where(cc.ID.eq(id))
                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .map { null as Void? }
                }
            }
    }

    // ==========================================
    // Assignments
    // ==========================================

    fun createAssignment(
        courseId: String,
        assignType: String,
        triggerRule: JsonObject = JsonObject(),
        deadline: String = "",
        targetType: String = "",
        targetIds: JsonArray = JsonArray(),
        createdBy: String = ""
    ): Future<JsonObject> {
        val validAssignTypes = listOf("手动指派", "自动触发")
        if (assignType !in validAssignTypes) {
            return Future.failedFuture(IllegalArgumentException("invalid assign_type: $assignType, must be one of $validAssignTypes"))
        }
        val validTargetTypes = listOf("用户", "岗位", "部门")
        if (targetType.isNotBlank() && targetType !in validTargetTypes) {
            return Future.failedFuture(IllegalArgumentException("invalid target_type: $targetType, must be one of $validTargetTypes"))
        }
        val checkQuery = ctx.select(c.ID).from(c).where(c.ID.eq(courseId))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("course not found"))
                else {
                    val id = Ulid.generate()
                    val now = OffsetDateTime.now()
                    val deadlineDt = try { OffsetDateTime.parse(deadline) } catch (e: Exception) { null }
                    val query = ctx.insertInto(ta)
                        .columns(ta.ID, ta.COURSE_ID, ta.ASSIGN_TYPE, ta.TRIGGER_RULE, ta.DEADLINE, ta.TARGET_TYPE, ta.TARGET_IDS, ta.CREATED_BY, ta.CREATED_AT)
                        .values(id, courseId, assignType, JSONB.valueOf(triggerRule.encode()), deadlineDt, targetType, targetIds.toList().map { it?.toString() ?: "" }.toTypedArray(), createdBy, now)
                        .returning(ta.ID, ta.COURSE_ID, ta.ASSIGN_TYPE, ta.TRIGGER_RULE, ta.DEADLINE, ta.TARGET_TYPE, ta.TARGET_IDS, ta.CREATED_BY, ta.CREATED_AT)
                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .map { insertRows -> assignmentToJson(insertRows.iterator().next()) }
                }
            }
    }

    fun listAssignments(
        courseId: String? = null,
        employeeId: String? = null,
        targetType: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        if (!courseId.isNullOrBlank()) {
            conditions.add(ta.COURSE_ID.eq(courseId))
        }
        if (!targetType.isNullOrBlank()) {
            conditions.add(ta.TARGET_TYPE.eq(targetType))
        }
        if (!employeeId.isNullOrBlank()) {
            conditions.add(org.jooq.impl.DSL.condition("{0} = ANY({1})", org.jooq.impl.DSL.value(employeeId), ta.TARGET_IDS))
        }

        val countQuery = ctx.select(count().`as`("total")).from(ta).where(conditions)
        val dataQuery = ctx.select(
                ta.ID, ta.COURSE_ID, ta.ASSIGN_TYPE, ta.TRIGGER_RULE, ta.DEADLINE,
                ta.TARGET_TYPE, ta.TARGET_IDS, ta.CREATED_BY, ta.CREATED_AT,
                c.TITLE.`as`("course_title")
            )
            .from(ta)
            .leftJoin(c).on(c.ID.eq(ta.COURSE_ID))
            .where(conditions)
            .orderBy(ta.CREATED_AT.desc())
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
                            records.add(assignmentToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun deleteAssignment(id: String): Future<Void> {
        val delProgress = ctx.deleteFrom(lp).where(lp.ASSIGNMENT_ID.eq(id))
        val delAssignment = ctx.deleteFrom(ta).where(ta.ID.eq(id)).returning(ta.ID)

        return pool.preparedQuery(DatabaseConfig.sql(delProgress))
            .execute(DatabaseConfig.tuple(delProgress))
            .flatMap { pool.preparedQuery(DatabaseConfig.sql(delAssignment)).execute(DatabaseConfig.tuple(delAssignment)) }
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("assignment not found"))
                else Future.succeededFuture(null as Void?)
            }
    }

    // ==========================================
    // Learning Progress
    // ==========================================

    fun getProgress(assignmentId: String, employeeId: String): Future<JsonObject> {
        val checkQuery = ctx.select(ta.ID, ta.COURSE_ID).from(ta).where(ta.ID.eq(assignmentId))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { assignmentRows ->
                if (assignmentRows.size() == 0) Future.failedFuture(NotFoundException("assignment not found"))
                else {
                    val courseId = assignmentRows.iterator().next().getValue("course_id")?.toString() ?: ""
                    // Get all chapters for this course
                    val chaptersQuery = ctx.select(cc.ID, cc.COURSE_ID, cc.SORT_ORDER, cc.TITLE, cc.BLOCKS, cc.QUIZ_CONFIG)
                        .from(cc)
                        .where(cc.COURSE_ID.eq(courseId))
                        .orderBy(cc.SORT_ORDER.asc())

                    pool.preparedQuery(DatabaseConfig.sql(chaptersQuery))
                        .execute(DatabaseConfig.tuple(chaptersQuery))
                        .flatMap { chapterRows ->
                            // Get existing progress records
                            val progressQuery = ctx.select(
                                    lp.ID, lp.ASSIGNMENT_ID, lp.EMPLOYEE_ID, lp.CHAPTER_ID, lp.PROGRESS_PERCENT, lp.STATUS, lp.DETAIL, lp.STARTED_AT, lp.COMPLETED_AT,
                                    cc.SORT_ORDER.`as`("chapter_sort_order"), cc.TITLE.`as`("chapter_title")
                                )
                                .from(lp)
                                .leftJoin(cc).on(cc.ID.eq(lp.CHAPTER_ID))
                                .where(lp.ASSIGNMENT_ID.eq(assignmentId).and(lp.EMPLOYEE_ID.eq(employeeId)))

                            pool.preparedQuery(DatabaseConfig.sql(progressQuery))
                                .execute(DatabaseConfig.tuple(progressQuery))
                                .map { progressRows ->
                                    val progressMap = mutableMapOf<String, JsonObject>()
                                    for (row in progressRows) {
                                        val chapterId = row.getValue("chapter_id")?.toString() ?: ""
                                        progressMap[chapterId] = progressToJson(row)
                                    }

                                    val chapters = JsonArray()
                                    for (chapter in chapterRows) {
                                        val chapterId = chapter.getValue("id")?.toString() ?: ""
                                        val existing = progressMap[chapterId]
                                        if (existing != null) {
                                            chapters.add(existing)
                                        } else {
                                            chapters.add(JsonObject()
                                                .put("chapter_id", chapterId)
                                                .put("chapter_title", chapter.getValue("title")?.toString() ?: "")
                                                .put("chapter_sort_order", chapter.getValue("sort_order") as? Int ?: 0)
                                                .put("progress_percent", 0)
                                                .put("status", "学习中")
                                                .put("detail", JsonObject())
                                                .put("started_at", "")
                                                .put("completed_at", "")
                                            )
                                        }
                                    }

                                    var totalPercent = 0
                                    val chapterCount = chapters.size()
                                    for (i in 0 until chapters.size()) {
                                        totalPercent += chapters.getJsonObject(i).getInteger("progress_percent") ?: 0
                                    }
                                    val overallPercent = if (chapterCount > 0) totalPercent / chapterCount else 0
                                    val allCompleted = chapters.getList().all { (it as? JsonObject)?.getString("status") == "已完成" }

                                    JsonObject()
                                        .put("assignment_id", assignmentId)
                                        .put("employee_id", employeeId)
                                        .put("course_id", courseId)
                                        .put("progress_percent", overallPercent)
                                        .put("status", if (allCompleted) "已完成" else "学习中")
                                        .put("chapters", chapters)
                                }
                        }
                }
            }
    }

    fun updateProgress(
        assignmentId: String,
        employeeId: String,
        chapterId: String,
        progressPercent: Int,
        detail: JsonObject = JsonObject()
    ): Future<JsonObject> {
        if (progressPercent < 0 || progressPercent > 100) {
            return Future.failedFuture(IllegalArgumentException("progress_percent must be between 0 and 100"))
        }
        val checkAssignment = ctx.select(ta.ID).from(ta).where(ta.ID.eq(assignmentId))
        val checkChapter = ctx.select(cc.ID).from(cc).where(cc.ID.eq(chapterId))

        return pool.preparedQuery(DatabaseConfig.sql(checkAssignment))
            .execute(DatabaseConfig.tuple(checkAssignment))
            .flatMap { assignRows ->
                if (assignRows.size() == 0) Future.failedFuture(NotFoundException("assignment not found"))
                else {
                    pool.preparedQuery(DatabaseConfig.sql(checkChapter))
                        .execute(DatabaseConfig.tuple(checkChapter))
                        .flatMap { chapterRows ->
                            if (chapterRows.size() == 0) return@flatMap Future.failedFuture<JsonObject>(NotFoundException("chapter not found"))
                            val id = Ulid.generate()
                            val now = OffsetDateTime.now()
                            val status = if (progressPercent >= 100) "已完成" else "学习中"
                            val startedAt = now
                            val completedAt = if (progressPercent >= 100) now else null

                            val query = ctx.insertInto(lp)
                                .columns(lp.ID, lp.ASSIGNMENT_ID, lp.EMPLOYEE_ID, lp.CHAPTER_ID, lp.PROGRESS_PERCENT, lp.STATUS, lp.DETAIL, lp.STARTED_AT, lp.COMPLETED_AT)
                                .values(id, assignmentId, employeeId, chapterId, java.math.BigDecimal.valueOf(progressPercent.toLong()), status, JSONB.valueOf(detail.encode()), startedAt, completedAt)
                                .onConflict(lp.ASSIGNMENT_ID, lp.EMPLOYEE_ID, lp.CHAPTER_ID)
                                .doUpdate()
                                .set(lp.PROGRESS_PERCENT, java.math.BigDecimal.valueOf(progressPercent.toLong()))
                                .set(lp.STATUS, status)
                                .set(lp.DETAIL, JSONB.valueOf(detail.encode()))
                                .set(lp.STARTED_AT, `when`(lp.STARTED_AT.isNull, startedAt).otherwise(lp.STARTED_AT))
                                .set(lp.COMPLETED_AT, `when`(lp.COMPLETED_AT.isNull, completedAt).otherwise(lp.COMPLETED_AT))
                                .returning(lp.ID, lp.ASSIGNMENT_ID, lp.EMPLOYEE_ID, lp.CHAPTER_ID, lp.PROGRESS_PERCENT, lp.STATUS, lp.DETAIL, lp.STARTED_AT, lp.COMPLETED_AT)
                            pool.preparedQuery(DatabaseConfig.sql(query))
                                .execute(DatabaseConfig.tuple(query))
                                .map { rows -> progressToJson(rows.iterator().next()) }
                        }
                }
            }
    }

    fun completeAllProgress(assignmentId: String, employeeId: String): Future<JsonObject> {
        val checkQuery = ctx.select(ta.ID, ta.COURSE_ID).from(ta).where(ta.ID.eq(assignmentId))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { assignRows ->
                if (assignRows.size() == 0) Future.failedFuture(NotFoundException("assignment not found"))
                else {
                    val courseId = assignRows.iterator().next().getValue("course_id")?.toString() ?: ""
                    val chaptersQuery = ctx.select(cc.ID).from(cc).where(cc.COURSE_ID.eq(courseId))
                    pool.preparedQuery(DatabaseConfig.sql(chaptersQuery))
                        .execute(DatabaseConfig.tuple(chaptersQuery))
                        .flatMap { chapterRows ->
                            val now = OffsetDateTime.now()
                            var chain: Future<*> = Future.succeededFuture<Any?>(null)

                            for (chapter in chapterRows) {
                                val chapterId = chapter.getValue("id")?.toString() ?: ""
                                val id = Ulid.generate()
                                val query = ctx.insertInto(lp)
                                    .columns(lp.ID, lp.ASSIGNMENT_ID, lp.EMPLOYEE_ID, lp.CHAPTER_ID, lp.PROGRESS_PERCENT, lp.STATUS, lp.DETAIL, lp.STARTED_AT, lp.COMPLETED_AT)
                                    .values(id, assignmentId, employeeId, chapterId, java.math.BigDecimal.valueOf(100), "已完成", JSONB.valueOf("{}"), now, now)
                                    .onConflict(lp.ASSIGNMENT_ID, lp.EMPLOYEE_ID, lp.CHAPTER_ID)
                                    .doUpdate()
                                    .set(lp.PROGRESS_PERCENT, java.math.BigDecimal.valueOf(100))
                                    .set(lp.STATUS, "已完成")
                                    .set(lp.DETAIL, `when`(lp.DETAIL.isNull.or(lp.DETAIL.eq(JSONB.valueOf("{}"))), JSONB.valueOf("{}")).otherwise(lp.DETAIL))
                                    .set(lp.COMPLETED_AT, now)
                                    .returning(lp.ID, lp.ASSIGNMENT_ID, lp.EMPLOYEE_ID, lp.CHAPTER_ID, lp.PROGRESS_PERCENT, lp.STATUS, lp.DETAIL, lp.STARTED_AT, lp.COMPLETED_AT)
                                chain = chain.flatMap {
                                    @Suppress("UNCHECKED_CAST")
                                    pool.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query)) as Future<Any?>
                                }
                            }

                            @Suppress("UNCHECKED_CAST")
                            chain.flatMap {
                                getProgress(assignmentId, employeeId)
                            } as Future<JsonObject>
                        }
                }
            }
    }

    // ==========================================
    // JSON Conversions
    // ==========================================

    companion object {
        private fun jsonObj(row: Row, column: String): JsonObject {
            val v = row.getValue(column)
            return when (v) {
                is JsonObject -> v
                is String -> try { JsonObject(v) } catch (e: Exception) { JsonObject() }
                else -> JsonObject()
            }
        }

        private fun jsonArr(row: Row, column: String): JsonArray {
            val v = row.getValue(column)
            return when (v) {
                is JsonArray -> v
                is String -> try { JsonArray(v) } catch (e: Exception) { JsonArray() }
                else -> JsonArray()
            }
        }

        private fun num(row: Row, column: String): Int? {
            val v = row.getValue(column)
            return (v as? Number)?.toInt()
        }

        fun courseToJson(row: Row): JsonObject {
            val meta = jsonObj(row, "metadata")
            val coverImage = row.getValue("cover_image")?.toString() ?: ""
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("title", row.getValue("title")?.toString())
                .put("type", row.getValue("type")?.toString())
                .put("cover_image", coverImage)
                .put("cover_url", meta.getString("cover_url", coverImage))
                .put("description", meta.getString("description", ""))
                .put("category", meta.getString("category", ""))
                .put("difficulty", meta.getString("difficulty", ""))
                .put("duration", meta.getInteger("duration"))
                .put("metadata", meta)
                .put("target_positions", arrayToJsonArray(row.getValue("target_positions")))
                .put("completion_rules", jsonObj(row, "completion_rules"))
                .put("status", row.getValue("status")?.toString())
                .put("created_by", row.getValue("created_by")?.toString() ?: "")
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }

        fun chapterToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("course_id", row.getValue("course_id")?.toString())
                .put("sort_order", num(row, "sort_order") ?: 0)
                .put("title", row.getValue("title")?.toString())
                .put("blocks", jsonObj(row, "blocks"))
                .put("quiz_config", jsonObj(row, "quiz_config"))
        }

        fun assignmentToJson(row: Row): JsonObject {
            val obj = JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("course_id", row.getValue("course_id")?.toString())
                .put("assign_type", row.getValue("assign_type")?.toString())
                .put("trigger_rule", jsonObj(row, "trigger_rule"))
                .put("deadline", row.getValue("deadline")?.toString() ?: "")
                .put("target_type", row.getValue("target_type")?.toString() ?: "")
                .put("target_ids", arrayToJsonArray(row.getValue("target_ids")))
                .put("created_by", row.getValue("created_by")?.toString() ?: "")
                .put("created_at", row.getValue("created_at")?.toString())
            val courseTitle = try { row.getValue("course_title")?.toString() } catch (e: NoSuchElementException) { null }
            if (courseTitle != null) {
                obj.put("course_title", courseTitle)
            }
            return obj
        }

        fun progressToJson(row: Row): JsonObject {
            val obj = JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("assignment_id", row.getValue("assignment_id")?.toString())
                .put("employee_id", row.getValue("employee_id")?.toString())
                .put("chapter_id", row.getValue("chapter_id")?.toString())
                .put("progress_percent", num(row, "progress_percent") ?: 0)
                .put("status", row.getValue("status")?.toString())
                .put("detail", jsonObj(row, "detail"))
                .put("started_at", row.getValue("started_at")?.toString() ?: "")
                .put("completed_at", row.getValue("completed_at")?.toString() ?: "")
            val chapterTitle = try { row.getValue("chapter_title")?.toString() } catch (e: NoSuchElementException) { null }
            if (chapterTitle != null) {
                obj.put("chapter_title", chapterTitle)
            }
            val chapterSortOrder = try { num(row, "chapter_sort_order") } catch (e: NoSuchElementException) { null }
            if (chapterSortOrder != null) {
                obj.put("chapter_sort_order", chapterSortOrder)
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
