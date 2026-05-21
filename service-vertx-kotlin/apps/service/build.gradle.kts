plugins {
    application
}

dependencies {
    implementation(project(":libs:auth"))
    implementation(project(":libs:settings"))
    implementation(project(":libs:files"))
    implementation(project(":libs:database"))
    implementation(libs.vertx.web)
    implementation(libs.vertx.config)
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
