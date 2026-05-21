plugins {
    `java-library`
}

val jooqCodegen by configurations.registering

dependencies {
    api(libs.vertx.pg.client)
    api(libs.jooq)
    api(libs.postgresql)
    implementation(libs.slf4j.api)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
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
