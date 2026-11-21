package com.ovaphlow.crate.database.testing

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.json.JsonObject

/**
 * 数据库集成测试的**唯一**连接入口（test fixtures，仅测试可见）。
 *
 * ## 为什么存在
 *
 * 2026-11-21 之前每个集成测试类自己 `const val TEST_DB = "aceso_test"`，
 * 其中一批还在 `@BeforeAll` 里连 `postgres` 维护库执行
 * `DROP DATABASE aceso_test; CREATE DATABASE aceso_test`。这套写法有三个后果：
 *
 * 1. **库名硬编码**：`-Dintegration.db.database` 传了也没人读，测试无法指向别的库；
 * 2. **不可逆**：一旦有人把常量改成业务库名，跑一次测试就抹掉整库数据；
 * 3. **测试库概念固化**：必须额外准备一个专用实例，才能跑本来只需「有 schema 的库」的用例。
 *
 * 现在统一为：**库名由 `-Dintegration.db.database` 显式给出**，测试只读不建不删；
 * 迁移由 `-Dintegration.db.migrate` 控制（默认 `true`，可关闭以便对既有库只读）。
 *
 * ## 参数
 *
 * | 系统属性 / 环境变量 | 缺省 | 含义 |
 * | --- | --- | --- |
 * | `-Dintegration.db.host` | 无（缺则整个集成类 skip） | PostgreSQL 主机 |
 * | `-Dintegration.db.database` | 无（缺则整个集成类 skip） | **目标库名，不预设、不硬编码** |
 * | `-Dintegration.db.port` | `5432` | 端口 |
 * | `-Dintegration.db.user` | `ovaphlow` | 用户 |
 * | `-Dintegration.db.migrate` | `true` | 是否执行 Flyway migrate；`false` = 只复用既有 schema |
 * | `PITCHFORK_DB_PASSWORD` | 无（缺则 skip） | 数据库密码（只从环境读取） |
 *
 * ## 使用约定
 *
 * - 集成测试**不得** `DROP DATABASE` / `CREATE DATABASE`：数据隔离一律靠 fixture 前缀
 *   + `@BeforeEach`/`@AfterEach` 清理 + `assertNoResidual` 残差断言；
 * - 集成测试**不得**假设库是空的：所有断言必须按 fixture id / 前缀收敛，
 *   否则在重复运行或指向既有库时必然误报；
 * - 想跑在一次性实例上（例如 tmpfs 容器）是运行者的选择，不是测试的前提。
 */
object IntegrationDb {

    const val HOST_PROPERTY = "integration.db.host"
    const val PORT_PROPERTY = "integration.db.port"
    const val DATABASE_PROPERTY = "integration.db.database"
    const val USER_PROPERTY = "integration.db.user"
    const val MIGRATE_PROPERTY = "integration.db.migrate"

    /** 目标库名；未配置时抛出可读错误（调用方应先用 [isConfigured] 门控）。 */
    fun database(): String =
        System.getProperty(DATABASE_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error(
                "缺少 -D$DATABASE_PROPERTY=<库名>：集成测试不预设库名（不再硬编码 aceso_test）。" +
                    "例：-D$HOST_PROPERTY=localhost -D$DATABASE_PROPERTY=aceso -D$PORT_PROPERTY=5432",
            )

    fun host(): String = System.getProperty(HOST_PROPERTY, "").trim()

    /** 端口（字符串形态，便于直接拼 JDBC URL 与断言）。 */
    fun portString(): String = System.getProperty(PORT_PROPERTY, "5432").trim()

    fun port(): Int = portString().toIntOrNull()
        ?: error("-$PORT_PROPERTY 必须是 1-65535 的整数：${portString()}")

    fun user(): String = System.getProperty(USER_PROPERTY, "ovaphlow").trim()

    fun password(): String = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

    /** 是否执行迁移；默认 true。指向既有库并希望「只读」时传 `-Dintegration.db.migrate=false`。 */
    fun migrateEnabled(): Boolean = System.getProperty(MIGRATE_PROPERTY, "true").trim().toBoolean()

    /** 门控判据：host、库名、密码都给全才跑（与 JUnit 类注解同口径）。 */
    fun isConfigured(): Boolean =
        host().isNotEmpty() &&
            !System.getProperty(DATABASE_PROPERTY).isNullOrBlank() &&
            password().isNotBlank()

    fun jdbcUrl(database: String = database()): String = "jdbc:postgresql://${host()}:${portString()}/$database"

    /** 交给 `DatabaseConfig.migrate` / `createPool` 的配置对象。 */
    fun config(): JsonObject = JsonObject()
        .put("host", host())
        .put("port", port())
        .put("database", database())
        .put("user", user())

    /** 按开关决定是否迁移；关闭时等价于「复用既有 schema」。 */
    fun migrateIfRequested(config: JsonObject = config()) {
        if (migrateEnabled()) DatabaseConfig.migrate(config)
    }
}
