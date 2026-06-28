plugins {
    application
}

dependencies {
    implementation(project(":libs:inventories"))
    implementation(project(":libs:pharmacy"))
    implementation(project(":libs:nursing"))
    implementation(project(":libs:users"))
    implementation(project(":libs:settings"))
    implementation(project(":libs:logging"))
    implementation(project(":libs:database"))
    implementation(project(":libs:common"))
    implementation(libs.vertx.web)
    implementation(libs.vertx.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
}

application {
    mainClass.set("com.ovaphlow.crate.aceso.MainKt")
}

tasks.withType<JavaExec> {
    systemProperty("LOG_DIR", rootProject.projectDir.resolve("logs").absolutePath)
}
