plugins {
    `java-library`
}

dependencies {
    api(libs.vertx.pg.client)
    api(libs.jooq)
    api(libs.postgresql)
    implementation(libs.slf4j.api)
}
