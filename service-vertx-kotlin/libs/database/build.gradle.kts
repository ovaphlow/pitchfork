plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.vertx.pg.client)
    api(libs.postgresql)
    implementation(libs.slf4j.api)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.scram.client)
    // 集成测试公共入口（IntegrationDb）：仅测试可见，不进生产 jar
    testFixturesImplementation(libs.vertx.pg.client)
}
