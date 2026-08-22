plugins {
    application
}

dependencies {
    implementation(project(":libs:healthcare"))
    implementation(project(":libs:inventories"))
    implementation(project(":libs:pharmacy"))
    implementation(project(":libs:nursing"))
    implementation(project(":libs:dining"))
    implementation(project(":libs:logging"))
    implementation(project(":libs:database"))
    implementation(project(":libs:common"))
    implementation(libs.vertx.web)
    implementation(libs.vertx.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.vertx.junit5)
}

application {
    mainClass.set("com.ovaphlow.crate.aceso.MainKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("integration.db.") }
        .forEach { key -> systemProperty(key, System.getProperty(key)) }
    environment("PITCHFORK_DB_PASSWORD", System.getenv("PITCHFORK_DB_PASSWORD") ?: "")
}

tasks.withType<JavaExec> {
    systemProperty("LOG_DIR", rootProject.projectDir.resolve("logs").absolutePath)
}
