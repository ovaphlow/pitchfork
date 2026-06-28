plugins {
    `java-library`
    kotlin("jvm")
}

val jooqCodegen by configurations.registering

dependencies {
    api(project(":libs:database"))
    api(project(":libs:common"))
    api(libs.vertx.web)
    api(libs.jooq)
    implementation(libs.slf4j.api)
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
