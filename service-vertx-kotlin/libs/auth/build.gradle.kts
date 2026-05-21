dependencies {
    implementation(project(":libs:database"))
    implementation(project(":libs:common"))
    implementation(libs.vertx.web)
    implementation(libs.vertx.auth.jwt)
    implementation(libs.bcrypt)
    implementation(libs.slf4j.api)
}
