val jooqCodegen by configurations.registering

dependencies {
    implementation(project(":libs:database"))
    implementation(libs.vertx.web)
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
