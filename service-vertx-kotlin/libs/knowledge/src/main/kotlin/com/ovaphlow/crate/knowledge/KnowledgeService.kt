package com.ovaphlow.crate.knowledge

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.KnowledgeCategories
import com.ovaphlow.crate.database.gen.public_.tables.KnowledgeEntries
import com.ovaphlow.crate.database.gen.public_.tables.KnowledgeFeedbacks
import com.ovaphlow.crate.database.gen.public_.tables.KnowledgeVersions
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

class KnowledgeService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(KnowledgeService::class.java)
    private val kc = KnowledgeCategories.KNOWLEDGE_CATEGORIES
    private val ke = KnowledgeEntries.KNOWLEDGE_ENTRIES
    private val kv = KnowledgeVersions.KNOWLEDGE_VERSIONS
    private val kf = KnowledgeFeedbacks.KNOWLEDGE_FEEDBACKS

    // ==============================
    // Categories
    // ==============================

    fun listCategories(): Future<JsonArray> {
        val query = ctx.select(kc.ID, kc.NAME, kc.PARENT_ID, kc.SORT_ORDER, kc.DESCRIPTION)
            .from(kc)
            .orderBy(kc.SORT_ORDER.asc(), kc.NAME.asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val arr = JsonArray()
                val all = mutableListOf<JsonObject>()
                for (row in rows) {
                    all.add(categoryToJson(row))
                }
                val map = all.associateBy { it.getString("id") }
                val roots = JsonArray()
                for (node in all) {
                    val pid = node.getString("parent_id")
                    if (pid.isNullOrBlank()) {
                        roots.add(node)
                    } else {
                        val parent = map[pid]
                        if (parent != null) {
                            val children = parent.getJsonArray("children")
                            children.add(node)
                        } else {
                            roots.add(node)
                        }
                    }
                }
                roots
            }
    }

    fun createCategory(name: String, parentId: String? = null, sortOrder: Int = 0, description: String? = null): Future<JsonObject> {
        val id = Ulid.generate()
        val sql = """INSERT INTO knowledge_categories (id, name, parent_id, sort_order, description)
                     VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5)
                     RETURNING id, name, parent_id, sort_order, description""".trimIndent()
        val tuple = Tuple.of(id, name, parentId ?: "", sortOrder, description ?: "")
        return pool.preparedQuery(sql)
            .execute(tuple)
            .map { rows: RowSet<Row> -> categoryToJson(rows.iterator().next()) }
    }

    fun updateCategory(id: String, name: String? = null, parentId: String? = null, sortOrder: Int? = null, description: String? = null): Future<JsonObject> {
        return getCategory(id).flatMap { existing: JsonObject ->
            val newName = name ?: existing.getString("name")
            val newParentId = if (parentId != null) parentId else existing.getString("parent_id") ?: ""
            val newSortOrder = (sortOrder ?: existing.getInteger("sort_order") ?: 0).toShort()
            val newDescription = description ?: existing.getString("description") ?: ""

            val query = ctx.update(kc)
                .set(kc.NAME, newName)
                .set(kc.PARENT_ID, newParentId)
                .set(kc.SORT_ORDER, newSortOrder)
                .set(kc.DESCRIPTION, newDescription)
                .where(kc.ID.eq(id))
                .returning(kc.ID, kc.NAME, kc.PARENT_ID, kc.SORT_ORDER, kc.DESCRIPTION)

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("category not found"))
                    else Future.succeededFuture(categoryToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteCategory(id: String): Future<Void> {
        // Check if category has children
        val checkQuery = ctx.select(count().`as`("cnt")).from(kc).where(kc.PARENT_ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                val count = rows.iterator().next().getLong("cnt") ?: 0L
                if (count > 0) {
                    Future.failedFuture(IllegalArgumentException("cannot delete category with children"))
                } else {
                    val query = ctx.deleteFrom(kc).where(kc.ID.eq(id))
                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .map { null as Void? }
                }
            }
    }

    private fun getCategory(id: String): Future<JsonObject> {
        val query = ctx.select(kc.ID, kc.NAME, kc.PARENT_ID, kc.SORT_ORDER, kc.DESCRIPTION)
            .from(kc)
            .where(kc.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("category not found"))
                else Future.succeededFuture(categoryToJson(rows.iterator().next()))
            }
    }

    // ==============================
    // Entries
    // ==============================

    fun listEntries(
        type: String? = null,
        status: String? = null,
        search: String? = null,
        categoryId: String? = null,
        tags: List<String>? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()

        if (!type.isNullOrBlank()) {
            conditions.add(ke.TYPE.eq(type))
        }
        if (!status.isNullOrBlank()) {
            conditions.add(ke.STATUS.eq(status))
        }
        if (!search.isNullOrBlank()) {
            conditions.add(ke.TITLE.likeIgnoreCase("%$search%"))
        }
        if (!categoryId.isNullOrBlank()) {
            conditions.add(org.jooq.impl.DSL.condition("{0} = ANY({1})", org.jooq.impl.DSL.value(categoryId), ke.CATEGORY_IDS))
        }
        if (tags != null && tags.isNotEmpty()) {
            conditions.add(org.jooq.impl.DSL.condition("{0} && {1}", ke.TAGS, org.jooq.impl.DSL.value(tags.toTypedArray())))
        }

        val countQuery = ctx.select(count().`as`("total")).from(ke).where(conditions)
        val dataQuery = ctx.select(
                ke.ID, ke.TITLE, ke.TYPE, ke.STATUS, ke.CURRENT_VERSION_ID,
                ke.CATEGORY_IDS, ke.DEVICE_IDS, ke.POSITION_IDS, ke.TAGS,
                ke.EXTRA, ke.CREATED_BY, ke.UPDATED_BY, ke.CREATED_AT, ke.UPDATED_AT
            )
            .from(ke)
            .where(conditions)
            .orderBy(ke.UPDATED_AT.desc())
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
                            records.add(entryToJson(row))
                        }
                        JsonObject()
                            .put("records", records)
                            .put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createEntry(
        title: String,
        type: String,
        categoryIds: List<String> = emptyList(),
        deviceIds: List<String> = emptyList(),
        positionIds: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        extra: JsonObject = JsonObject(),
        createdBy: String = "",
        updatedBy: String = ""
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val query = ctx.insertInto(ke)
            .columns(ke.ID, ke.TITLE, ke.TYPE, ke.STATUS, ke.CATEGORY_IDS, ke.DEVICE_IDS, ke.POSITION_IDS, ke.TAGS, ke.EXTRA, ke.CREATED_BY, ke.UPDATED_BY, ke.CREATED_AT, ke.UPDATED_AT)
            .values(id, title, type, "草稿", categoryIds.toTypedArray(), deviceIds.toTypedArray(), positionIds.toTypedArray(), tags.toTypedArray(), JSONB.valueOf(extra.encode()), createdBy, updatedBy, now, now)
            .returning(ke.ID, ke.TITLE, ke.TYPE, ke.STATUS, ke.CURRENT_VERSION_ID, ke.CATEGORY_IDS, ke.DEVICE_IDS, ke.POSITION_IDS, ke.TAGS, ke.EXTRA, ke.CREATED_BY, ke.UPDATED_BY, ke.CREATED_AT, ke.UPDATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> entryToJson(rows.iterator().next()) }
    }

    fun getEntry(id: String): Future<JsonObject> {
        val query = ctx.select(
                ke.ID, ke.TITLE, ke.TYPE, ke.STATUS, ke.CURRENT_VERSION_ID,
                ke.CATEGORY_IDS, ke.DEVICE_IDS, ke.POSITION_IDS, ke.TAGS,
                ke.EXTRA, ke.CREATED_BY, ke.UPDATED_BY, ke.CREATED_AT, ke.UPDATED_AT,
                kv.CONTENT, kv.CONTENT_BLOCKS, kv.ATTACHMENT_FILES, kv.VERSION_NUMBER
            )
            .from(ke)
            .leftJoin(kv).on(kv.ID.eq(ke.CURRENT_VERSION_ID))
            .where(ke.ID.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("entry not found"))
                else Future.succeededFuture(entryDetailToJson(rows.iterator().next()))
            }
    }

    fun updateEntry(
        id: String,
        title: String? = null,
        type: String? = null,
        categoryIds: List<String>? = null,
        deviceIds: List<String>? = null,
        positionIds: List<String>? = null,
        tags: List<String>? = null,
        extra: JsonObject? = null,
        updatedBy: String = ""
    ): Future<JsonObject> {
        return getEntryRaw(id).flatMap { existing: JsonObject ->
            val setClauses = mutableListOf<String>()
            val params = mutableListOf<Any?>()
            var idx = 1

            if (title != null) { setClauses.add("title = \${'$'}${idx}"); params.add(title); idx++ }
            if (type != null) { setClauses.add("type = \${'$'}${idx}"); params.add(type); idx++ }
            if (categoryIds != null) { setClauses.add("category_ids = \${'$'}${idx}"); params.add(categoryIds.toTypedArray()); idx++ }
            if (deviceIds != null) { setClauses.add("device_ids = \${'$'}${idx}"); params.add(deviceIds.toTypedArray()); idx++ }
            if (positionIds != null) { setClauses.add("position_ids = \${'$'}${idx}"); params.add(positionIds.toTypedArray()); idx++ }
            if (tags != null) { setClauses.add("tags = \${'$'}${idx}"); params.add(tags.toTypedArray()); idx++ }
            if (extra != null) { setClauses.add("extra = \${'$'}${idx}::jsonb"); params.add(extra.encode()); idx++ }

            if (setClauses.isEmpty()) return@flatMap Future.succeededFuture(existing)

            setClauses.add("updated_by = \${'$'}${idx}"); params.add(updatedBy); idx++
            setClauses.add("updated_at = \${'$'}${idx}"); params.add(OffsetDateTime.now()); idx++

            val sql = """UPDATE knowledge_entries
                         SET ${setClauses.joinToString(", ")}
                         WHERE id = \${'$'}${idx}
                         RETURNING id, title, type, status, current_version_id, category_ids, device_ids, position_ids, tags, extra, created_by, updated_by, created_at, updated_at""".trimIndent()
            params.add(id)

            val tuple = Tuple.tuple()
            @Suppress("UNCHECKED_CAST")
            for (p in params) {
                when (p) {
                    is String -> tuple.addString(p)
                    is Int -> tuple.addInteger(p)
                    is Short -> tuple.addInteger(p.toInt())
                    is OffsetDateTime -> tuple.addString(p.toString())
                    is Array<*> -> tuple.addValue(p)
                    else -> tuple.addValue(p)
                }
            }

            pool.preparedQuery(sql)
                .execute(tuple)
                .flatMap { rows: RowSet<Row> ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("entry not found"))
                    else Future.succeededFuture(entryToJson(rows.iterator().next()))
                }
        }
    }

    fun updateEntryStatus(id: String, status: String): Future<JsonObject> {
        val validStatuses = listOf("草稿", "已发布", "已归档")
        if (status !in validStatuses) {
            return Future.failedFuture(IllegalArgumentException("invalid status: $status"))
        }
        val now = OffsetDateTime.now()
        val query = ctx.update(ke)
            .set(ke.STATUS, status)
            .set(ke.UPDATED_AT, now)
            .where(ke.ID.eq(id))
            .returning(ke.ID, ke.TITLE, ke.TYPE, ke.STATUS, ke.CURRENT_VERSION_ID, ke.CATEGORY_IDS, ke.DEVICE_IDS, ke.POSITION_IDS, ke.TAGS, ke.EXTRA, ke.CREATED_BY, ke.UPDATED_BY, ke.CREATED_AT, ke.UPDATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("entry not found"))
                else Future.succeededFuture(entryToJson(rows.iterator().next()))
            }
    }

    fun deleteEntry(id: String): Future<Void> {
        // Delete versions and feedbacks first
        val delFeedbacks = ctx.deleteFrom(kf).where(kf.ENTRY_ID.eq(id))
        val delVersions = ctx.deleteFrom(kv).where(kv.ENTRY_ID.eq(id))
        val delEntry = ctx.deleteFrom(ke).where(ke.ID.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(delFeedbacks))
            .execute(DatabaseConfig.tuple(delFeedbacks))
            .flatMap { pool.preparedQuery(DatabaseConfig.sql(delVersions)).execute(DatabaseConfig.tuple(delVersions)) }
            .flatMap { pool.preparedQuery(DatabaseConfig.sql(delEntry)).execute(DatabaseConfig.tuple(delEntry)) }
            .map { null as Void? }
    }

    private fun getEntryRaw(id: String): Future<JsonObject> {
        val query = ctx.select(
                ke.ID, ke.TITLE, ke.TYPE, ke.STATUS, ke.CURRENT_VERSION_ID,
                ke.CATEGORY_IDS, ke.DEVICE_IDS, ke.POSITION_IDS, ke.TAGS,
                ke.EXTRA, ke.CREATED_BY, ke.UPDATED_BY, ke.CREATED_AT, ke.UPDATED_AT
            )
            .from(ke)
            .where(ke.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("entry not found"))
                else Future.succeededFuture(entryToJson(rows.iterator().next()))
            }
    }

    // ==============================
    // Versions
    // ==============================

    fun listVersions(entryId: String): Future<JsonObject> {
        val checkQuery = ctx.select(ke.ID).from(ke).where(ke.ID.eq(entryId))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("entry not found"))
                else {
                    val query = ctx.select(
                            kv.ID, kv.ENTRY_ID, kv.VERSION_NUMBER, kv.CONTENT, kv.CONTENT_BLOCKS,
                            kv.ATTACHMENT_FILES, kv.CHANGE_NOTE, kv.STATUS, kv.APPROVED_BY, kv.APPROVED_AT,
                            kv.CREATED_BY, kv.CREATED_AT
                        )
                        .from(kv)
                        .where(kv.ENTRY_ID.eq(entryId))
                        .orderBy(kv.VERSION_NUMBER.desc())

                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .map { dataRows ->
                            val records = JsonArray()
                            for (row in dataRows) {
                                records.add(versionToJson(row))
                            }
                            JsonObject().put("records", records)
                        }
                }
            }
    }

    fun createVersion(
        entryId: String,
        versionNumber: Int? = null,
        content: String = "",
        contentBlocks: JsonObject = JsonObject(),
        attachmentFiles: JsonObject = JsonObject(),
        changeNote: String = "",
        createdBy: String = ""
    ): Future<JsonObject> {
        val checkQuery = ctx.select(ke.ID, ke.CURRENT_VERSION_ID).from(ke).where(ke.ID.eq(entryId))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("entry not found"))
                else {
                    val id = Ulid.generate()
                    val now = OffsetDateTime.now()
                    val vn = versionNumber ?: 1

                    val sql = """INSERT INTO knowledge_versions (id, entry_id, version_number, content, content_blocks, attachment_files, change_note, status, created_by, created_at)
                                 VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5::jsonb, ${'$'}6::jsonb, ${'$'}7, ${'$'}8, ${'$'}9, ${'$'}10)
                                 RETURNING id, entry_id, version_number, content, content_blocks, attachment_files, change_note, status, approved_by, approved_at, created_by, created_at""".trimIndent()
                    val tuple = Tuple.of(id, entryId, vn, content, contentBlocks.encode(), attachmentFiles.encode(), changeNote, "草稿", createdBy, now)

                    pool.preparedQuery(sql)
                        .execute(tuple)
                        .flatMap { insertRows: RowSet<Row> ->
                            val version = versionToJson(insertRows.iterator().next())
                            // Update entry's current_version_id
                            val updateSql = """UPDATE knowledge_entries
                                               SET current_version_id = ${'$'}1,
                                                   updated_at = ${'$'}2
                                               WHERE id = ${'$'}3""".trimIndent()
                            val updateTuple = Tuple.of(id, now, entryId)
                            pool.preparedQuery(updateSql)
                                .execute(updateTuple)
                                .map { version }
                        }
                }
            }
    }

    fun approveVersion(entryId: String, versionId: String, approvedBy: String = ""): Future<JsonObject> {
        return getVersion(versionId).flatMap { version: JsonObject ->
            val versionStatus = version.getString("status")
            if (versionStatus != "审批中" && versionStatus != "草稿") {
                return@flatMap Future.failedFuture(IllegalArgumentException("version status must be '草稿' or '审批中' to approve"))
            }
            val now = OffsetDateTime.now()
            val updateQuery = ctx.update(kv)
                .set(kv.STATUS, "已生效")
                .set(kv.APPROVED_BY, approvedBy)
                .set(kv.APPROVED_AT, now)
                .where(kv.ID.eq(versionId).and(kv.ENTRY_ID.eq(entryId)))
                .returning(kv.ID, kv.ENTRY_ID, kv.VERSION_NUMBER, kv.CONTENT, kv.CONTENT_BLOCKS, kv.ATTACHMENT_FILES, kv.CHANGE_NOTE, kv.STATUS, kv.APPROVED_BY, kv.APPROVED_AT, kv.CREATED_BY, kv.CREATED_AT)

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { vrows ->
                    if (vrows.size() == 0) Future.failedFuture(NotFoundException("version not found"))
                    else {
                        val approvedVersion = versionToJson(vrows.iterator().next())
                        // Update entry status to 已发布 and set current_version
                        val updateEntryQuery = ctx.update(ke)
                            .set(ke.STATUS, "已发布")
                            .set(ke.CURRENT_VERSION_ID, versionId)
                            .set(ke.UPDATED_AT, now)
                            .where(ke.ID.eq(entryId))
                        pool.preparedQuery(DatabaseConfig.sql(updateEntryQuery))
                            .execute(DatabaseConfig.tuple(updateEntryQuery))
                            .map { approvedVersion }
                    }
                }
        }
    }

    fun rejectVersion(entryId: String, versionId: String): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val query = ctx.update(kv)
            .set(kv.STATUS, "已归档")
            .where(kv.ID.eq(versionId).and(kv.ENTRY_ID.eq(entryId)))
            .returning(kv.ID, kv.ENTRY_ID, kv.VERSION_NUMBER, kv.CONTENT, kv.CONTENT_BLOCKS, kv.ATTACHMENT_FILES, kv.CHANGE_NOTE, kv.STATUS, kv.APPROVED_BY, kv.APPROVED_AT, kv.CREATED_BY, kv.CREATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("version not found"))
                else Future.succeededFuture(versionToJson(rows.iterator().next()))
            }
    }

    private fun getVersion(versionId: String): Future<JsonObject> {
        val query = ctx.select(
                kv.ID, kv.ENTRY_ID, kv.VERSION_NUMBER, kv.CONTENT, kv.CONTENT_BLOCKS,
                kv.ATTACHMENT_FILES, kv.CHANGE_NOTE, kv.STATUS, kv.APPROVED_BY, kv.APPROVED_AT,
                kv.CREATED_BY, kv.CREATED_AT
            )
            .from(kv)
            .where(kv.ID.eq(versionId))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("version not found"))
                else Future.succeededFuture(versionToJson(rows.iterator().next()))
            }
    }

    // ==============================
    // Feedbacks
    // ==============================

    fun listFeedbacks(entryId: String): Future<JsonObject> {
        val checkQuery = ctx.select(ke.ID).from(ke).where(ke.ID.eq(entryId))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("entry not found"))
                else {
                    val query = ctx.select(kf.ID, kf.ENTRY_ID, kf.TYPE, kf.CONTENT, kf.REPLY, kf.STATUS, kf.CREATED_BY, kf.CREATED_AT)
                        .from(kf)
                        .where(kf.ENTRY_ID.eq(entryId))
                        .orderBy(kf.CREATED_AT.desc())

                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .map { dataRows ->
                            val records = JsonArray()
                            for (row in dataRows) {
                                records.add(feedbackToJson(row))
                            }
                            JsonObject().put("records", records)
                        }
                }
            }
    }

    fun createFeedback(entryId: String, type: String, content: String, createdBy: String = ""): Future<JsonObject> {
        val validTypes = listOf("提问", "纠错", "建议")
        if (type !in validTypes) {
            return Future.failedFuture(IllegalArgumentException("invalid feedback type: $type"))
        }
        val checkQuery = ctx.select(ke.ID).from(ke).where(ke.ID.eq(entryId))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("entry not found"))
                else {
                    val id = Ulid.generate()
                    val now = OffsetDateTime.now()
                    val insertQuery = ctx.insertInto(kf)
                        .columns(kf.ID, kf.ENTRY_ID, kf.TYPE, kf.CONTENT, kf.STATUS, kf.CREATED_BY, kf.CREATED_AT)
                        .values(id, entryId, type, content, "待处理", createdBy, now)
                        .returning(kf.ID, kf.ENTRY_ID, kf.TYPE, kf.CONTENT, kf.REPLY, kf.STATUS, kf.CREATED_BY, kf.CREATED_AT)

                    pool.preparedQuery(DatabaseConfig.sql(insertQuery))
                        .execute(DatabaseConfig.tuple(insertQuery))
                        .map { insertRows -> feedbackToJson(insertRows.iterator().next()) }
                }
            }
    }

    fun replyFeedback(feedbackId: String, reply: JsonObject): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val query = ctx.update(kf)
            .set(kf.REPLY, JSONB.valueOf(reply.encode()))
            .set(kf.STATUS, "已回复")
            .where(kf.ID.eq(feedbackId))
            .returning(kf.ID, kf.ENTRY_ID, kf.TYPE, kf.CONTENT, kf.REPLY, kf.STATUS, kf.CREATED_BY, kf.CREATED_AT)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("feedback not found"))
                else Future.succeededFuture(feedbackToJson(rows.iterator().next()))
            }
    }

    // ==============================
    // JSON Conversions
    // ==============================

    companion object {
        fun categoryToJson(row: Row): JsonObject {
            val children = JsonArray()
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("name", row.getValue("name")?.toString())
                .put("parent_id", row.getValue("parent_id")?.toString() ?: "")
                .put("sort_order", row.getValue("sort_order") as? Int ?: 0)
                .put("description", row.getValue("description")?.toString() ?: "")
                .put("children", children)
        }

        fun entryToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("title", row.getValue("title")?.toString())
                .put("type", row.getValue("type")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("current_version_id", row.getValue("current_version_id")?.toString() ?: "")
                .put("category_ids", arrayToJsonArray(row.getValue("category_ids")))
                .put("device_ids", arrayToJsonArray(row.getValue("device_ids")))
                .put("position_ids", arrayToJsonArray(row.getValue("position_ids")))
                .put("tags", arrayToJsonArray(row.getValue("tags")))
                .put("extra", row.getValue("extra") as? JsonObject ?: JsonObject())
                .put("created_by", row.getValue("created_by")?.toString() ?: "")
                .put("updated_by", row.getValue("updated_by")?.toString() ?: "")
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }

        fun entryDetailToJson(row: Row): JsonObject {
            val base = entryToJson(row)
            base.put("content", row.getValue("content")?.toString() ?: "")
            base.put("content_blocks", row.getValue("content_blocks") as? JsonObject ?: JsonObject())
            base.put("attachment_files", row.getValue("attachment_files") as? JsonObject ?: JsonObject())
            base.put("version_number", row.getValue("version_number") as? Int ?: 0)
            return base
        }

        fun versionToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("entry_id", row.getValue("entry_id")?.toString())
                .put("version_number", row.getValue("version_number") as? Int ?: 0)
                .put("content", row.getValue("content")?.toString() ?: "")
                .put("content_blocks", row.getValue("content_blocks") as? JsonObject ?: JsonObject())
                .put("attachment_files", row.getValue("attachment_files") as? JsonObject ?: JsonObject())
                .put("change_note", row.getValue("change_note")?.toString() ?: "")
                .put("status", row.getValue("status")?.toString())
                .put("approved_by", row.getValue("approved_by")?.toString() ?: "")
                .put("approved_at", row.getValue("approved_at")?.toString())
                .put("created_by", row.getValue("created_by")?.toString() ?: "")
                .put("created_at", row.getValue("created_at")?.toString())
        }

        fun feedbackToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("entry_id", row.getValue("entry_id")?.toString())
                .put("type", row.getValue("type")?.toString())
                .put("content", row.getValue("content")?.toString() ?: "")
                .put("reply", row.getValue("reply") as? JsonObject ?: JsonObject())
                .put("status", row.getValue("status")?.toString())
                .put("created_by", row.getValue("created_by")?.toString() ?: "")
                .put("created_at", row.getValue("created_at")?.toString())
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
