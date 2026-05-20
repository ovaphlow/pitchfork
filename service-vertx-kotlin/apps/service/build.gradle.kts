plugins {
    application
}

dependencies {
    implementation(project(":libs:auth"))
    implementation(project(":libs:settings"))
    implementation(project(":libs:files"))
    implementation(libs.vertx.web)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
}

application {
    mainClass.set("com.example.service.MainKt")
}
