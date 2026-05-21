package com.example.database

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

object DatabaseConfig {

    private val log = LoggerFactory.getLogger(DatabaseConfig::class.java)

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
}
