package com.ovaphlow.crate.database

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object DatabaseConfig {

    private val log = LoggerFactory.getLogger(DatabaseConfig::class.java)

    private fun requiredConfigString(config: JsonObject, key: String): String {
        return config.getString(key)?.takeIf(String::isNotBlank)
            ?: error("database.$key must be configured")
    }

    private fun requiredDatabasePassword(): String {
        System.getenv("PITCHFORK_DB_PASSWORD")?.takeIf(String::isNotBlank)?.let { return it }
        val envFile = java.io.File(".env")
        if (envFile.isFile) {
            var password: String? = null
            envFile.forEachLine { line ->
                val trimmed = line.trim()
                if (password == null && trimmed.startsWith("PITCHFORK_DB_PASSWORD=")) {
                    password = trimmed.removePrefix("PITCHFORK_DB_PASSWORD=").trim('"', '\'')
                }
            }
            if (!password.isNullOrBlank()) return password
        }
        error("PITCHFORK_DB_PASSWORD must be set as environment variable or in .env file")
    }

    private fun buildJdbcUrl(config: JsonObject): String {
        val host = requiredConfigString(config, "host")
        val port = config.getInteger("port")
            ?: error("database.port must be configured")
        val database = requiredConfigString(config, "database")
        return "jdbc:postgresql://$host:$port/$database"
    }

    fun migrate(config: JsonObject = JsonObject()) {
        val url = buildJdbcUrl(config)
        val user = requiredConfigString(config, "user")
        val password = requiredDatabasePassword()

        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .ignoreMigrationPatterns("*:missing", "*:ignored", "*:future")
            // 各模块按独立号段（inventories V2xx、pharmacy V3xx、nursing V4xx、
            // healthcare V5xx、dining V6xx）共享一个 schema history。只加载部分模块时，
            // 已应用但本地缺失的迁移可能是 missing（低于本地最高版本），也可能是 future
            // （高于本地最高版本）；全局 current 由任一模块推进后还必须允许乱序执行。
            .outOfOrder(true)
            .load()
            .migrate()

        log.info("Flyway migration completed")
    }

    fun createPool(vertx: Vertx, config: JsonObject = JsonObject()): Pool {
        val connectOptions = PgConnectOptions()
            .setPort(config.getInteger("port") ?: error("database.port must be configured"))
            .setHost(requiredConfigString(config, "host"))
            .setDatabase(requiredConfigString(config, "database"))
            .setUser(requiredConfigString(config, "user"))
            .setPassword(requiredDatabasePassword())

        val poolOptions = PoolOptions().setMaxSize(
            config.getInteger("pool-size", 10)
        )

        val pool = Pool.pool(vertx, connectOptions, poolOptions)
        log.info("PostgreSQL pool created: {}:{}/{}", connectOptions.host, connectOptions.port, connectOptions.database)
        return pool
    }
}
