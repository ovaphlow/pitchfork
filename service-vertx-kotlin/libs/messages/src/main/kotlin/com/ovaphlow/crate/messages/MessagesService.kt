package com.ovaphlow.crate.messages

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.Messages
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

class MessagesService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(MessagesService::class.java)
    private val m = Messages.MESSAGES

    fun create(messageType: String, senderId: String, senderType: String, receiverId: String, receiverType: String, payload: JsonObject = JsonObject()): Future<JsonObject> {
        val id = Ulid.generate()
        val query = ctx.insertInto(m, m.ID, m.MESSAGE_TYPE, m.SENDER_ID, m.SENDER_TYPE, m.RECEIVER_ID, m.RECEIVER_TYPE, m.PAYLOAD)
            .values(id, messageType, senderId, senderType, receiverId, receiverType, JSONB.valueOf(payload.encode()))
            .returning(m)
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { toMessageJson(it.iterator().next()) }
    }

    fun list(messageType: String? = null, senderId: String? = null, senderType: String? = null, receiverId: String? = null, receiverType: String? = null, status: String? = null, limit: Int = 50, offset: Int = 0): Future<JsonArray> {
        val conditions = mutableListOf<Condition>()
        messageType?.let { conditions.add(m.MESSAGE_TYPE.eq(it)) }
        senderId?.let { conditions.add(m.SENDER_ID.eq(it)) }
        senderType?.let { conditions.add(m.SENDER_TYPE.eq(it)) }
        receiverId?.let { conditions.add(m.RECEIVER_ID.eq(it)) }
        receiverType?.let { conditions.add(m.RECEIVER_TYPE.eq(it)) }
        status?.let { conditions.add(m.STATUS.eq(it)) }

        val query = ctx.selectFrom(m)
            .where(conditions)
            .orderBy(m.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toMessageJson(it)) }; a }
    }

    fun get(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(m).where(m.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("message not found"))
                else Future.succeededFuture(toMessageJson(rows.iterator().next()))
            }
    }

    fun update(id: String, status: String? = null, payload: JsonObject? = null): Future<JsonObject> {
        if (status == null && payload == null) return get(id)

        val q1 = ctx.update(m).set(m.UPDATED_AT, OffsetDateTime.now())
        val q2 = if (status != null) q1.set(m.STATUS, status) else q1
        val q3 = if (payload != null) q2.set(m.PAYLOAD, JSONB.valueOf(payload.encode())) else q2
        val query = q3.where(m.ID.eq(id)).returning(m)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("message not found"))
                else Future.succeededFuture(toMessageJson(rows.iterator().next()))
            }
    }

    fun updateStatus(id: String, status: String): Future<JsonObject> {
        val query = ctx.update(m)
            .set(m.STATUS, status)
            .set(m.UPDATED_AT, OffsetDateTime.now())
            .where(m.ID.eq(id))
            .returning(m)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("message not found"))
                else Future.succeededFuture(toMessageJson(rows.iterator().next()))
            }
    }

    fun delete(id: String): Future<Void> {
        val query = ctx.deleteFrom(m).where(m.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null }
    }

    companion object {
        fun toMessageJson(row: Row) = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("message_type", row.getValue("message_type")?.toString())
            .put("sender_id", row.getValue("sender_id")?.toString())
            .put("sender_type", row.getValue("sender_type")?.toString())
            .put("receiver_id", row.getValue("receiver_id")?.toString())
            .put("receiver_type", row.getValue("receiver_type")?.toString())
            .put("status", row.getValue("status")?.toString())
            .put("payload", row.getValue("payload") as? JsonObject ?: JsonObject())
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
    }
}

class NotFoundException(message: String) : Exception(message)
