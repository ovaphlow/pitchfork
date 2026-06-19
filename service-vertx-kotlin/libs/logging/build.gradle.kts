plugins {
    `java-library`
}

dependencies {
    api(libs.slf4j.api)
    api(libs.logback.classic)
    api(libs.logstash.logback.encoder)
}
