plugins {
    application
}

dependencies {
    implementation(project(":libs:auth"))
    implementation(project(":libs:settings"))
    implementation(project(":libs:files"))
    implementation(project(":libs:permission"))
    implementation(project(":libs:messages"))
    implementation(project(":libs:users"))
    implementation(project(":libs:knowledge"))
    implementation(project(":libs:skills"))
    implementation(project(":libs:training"))
    implementation(project(":libs:exam"))
    implementation(project(":libs:onsite"))
    implementation(project(":libs:ai-assistant"))
    implementation(project(":libs:analytics"))
    implementation(project(":libs:database"))
    implementation(libs.vertx.web)
    implementation(libs.vertx.config)
    implementation(libs.vertx.auth.jwt)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
}

application {
    mainClass.set("com.ovaphlow.crate.service.MainKt")
}

tasks.withType<JavaExec> {
    systemProperty("LOG_DIR", rootProject.projectDir.resolve("logs").absolutePath)
}
