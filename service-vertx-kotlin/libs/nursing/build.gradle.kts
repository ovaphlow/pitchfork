plugins {
    `java-library`
    kotlin("jvm")
}

val jooqCodegen by configurations.registering

dependencies {
    api(project(":libs:database"))
    api(project(":libs:common"))
    api(project(":libs:inventories"))
    api(libs.vertx.web)
    api(libs.jooq)
    implementation(libs.slf4j.api)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.vertx.junit5)
    jooqCodegen(libs.jooq.codegen)
    jooqCodegen(libs.jooq.meta)
    jooqCodegen(libs.postgresql)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 传递 integration.db.* 系统属性到测试 JVM，支持集成测试
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("integration.db.") }
        .forEach { key -> systemProperty(key, System.getProperty(key)) }
    // 传递数据库密码环境变量
    environment("PITCHFORK_DB_PASSWORD", System.getenv("PITCHFORK_DB_PASSWORD") ?: "")
}

val generateJooq by tasks.registering(JavaExec::class) {
    classpath = jooqCodegen.get()
    mainClass = "org.jooq.codegen.GenerationTool"
    args("jooq-config.xml")
    workingDir = projectDir
}
