plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":libs:database"))
    api(project(":libs:common"))
    api(project(":libs:nursing"))
    api(libs.vertx.web)
    implementation(libs.slf4j.api)
    testImplementation(testFixtures(project(":libs:database")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.vertx.junit5)
}

tasks.withType<Test> {
    useJUnitPlatform()
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("integration.db.") }
        .forEach { key -> systemProperty(key, System.getProperty(key)) }
    environment("PITCHFORK_DB_PASSWORD", System.getenv("PITCHFORK_DB_PASSWORD") ?: "")
}
