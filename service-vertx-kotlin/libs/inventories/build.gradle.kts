plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":libs:database"))
    api(project(":libs:common"))
    api(libs.vertx.web)
    api(libs.jooq)
    implementation(libs.slf4j.api)
}
