plugins {
    `java-library`
    `java-test-fixtures`
}

val jooqCodegen by configurations.registering

dependencies {
    api(libs.vertx.pg.client)
    api(libs.jooq)
    api(libs.postgresql)
    implementation(libs.slf4j.api)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.scram.client)
    // 集成测试公共入口（IntegrationDb）：仅测试可见，不进生产 jar
    testFixturesImplementation(libs.vertx.pg.client)
    testFixturesImplementation(libs.jooq)
    jooqCodegen(libs.jooq.codegen)
    jooqCodegen(libs.jooq.meta)
    jooqCodegen(libs.postgresql)
}

val generateJooq by tasks.registering(JavaExec::class) {
    classpath = jooqCodegen.get()
    mainClass = "org.jooq.codegen.GenerationTool"
    args("jooq-config.xml")
    workingDir = projectDir
}
