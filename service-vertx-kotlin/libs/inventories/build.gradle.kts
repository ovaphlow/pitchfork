plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":libs:database"))
    api(project(":libs:common"))
    api(libs.vertx.web)
    implementation(libs.slf4j.api)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.vertx.junit5)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
