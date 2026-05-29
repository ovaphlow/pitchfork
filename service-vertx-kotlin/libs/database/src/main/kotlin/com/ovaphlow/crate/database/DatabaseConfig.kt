package com.ovaphlow.crate.database

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Tuple
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object DatabaseConfig {

    private val log = LoggerFactory.getLogger(DatabaseConfig::class.java)

    private fun buildJdbcUrl(config: JsonObject): String {
        val host = config.getString("host", "localhost")
        val port = config.getInteger("port", 5432)
        val database = config.getString("database", "ovaphlow")
        return "jdbc:postgresql://$host:$port/$database"
    }

    fun migrate(config: JsonObject = JsonObject()) {
        val url = buildJdbcUrl(config)
        val user = config.getString("user", "ovaphlow")
        val password = config.getString("password", "dsdfJk#1123")

        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()

        log.info("Flyway migration completed")
    }

    fun createPool(vertx: Vertx, config: JsonObject = JsonObject()): Pool {
        val connectOptions = PgConnectOptions()
            .setPort(config.getInteger("port", 5432))
            .setHost(config.getString("host", "localhost"))
            .setDatabase(config.getString("database", "ovaphlow"))
            .setUser(config.getString("user", "ovaphlow"))
            .setPassword(config.getString("password", "dsdfJk#1123"))

        val poolOptions = PoolOptions().setMaxSize(
            config.getInteger("pool-size", 10)
        )

        val pool = Pool.pool(vertx, connectOptions, poolOptions)
        log.info("PostgreSQL pool created: {}:{}/{}", connectOptions.host, connectOptions.port, connectOptions.database)
        return pool
    }

    fun createDSL(): DSLContext {
        return DSL.using(SQLDialect.POSTGRES)
    }

    fun sql(query: org.jooq.Query): String {
        return query.getSQL(ParamType.NAMED).replace(Regex(":(\\d+)")) { "\$${it.groupValues[1]}" }
    }

    fun tuple(query: org.jooq.Query): Tuple {
        val t = Tuple.tuple()
        query.getBindValues().forEach { v ->
            when (v) {
                is JSONB -> t.addValue(JsonObject(v.data()))
                else -> t.addValue(v)
            }
        }
        return t
    }
}
