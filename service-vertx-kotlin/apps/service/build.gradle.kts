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
    implementation(libs.vertx.config.hocon)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
}

application {
    mainClass.set("com.example.service.MainKt")
}
