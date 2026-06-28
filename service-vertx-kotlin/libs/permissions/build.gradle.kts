dependencies {
    implementation(project(":libs:database"))
    implementation(project(":libs:common"))
    implementation(project(":libs:auth"))
    implementation(project(":libs:settings"))
    implementation(libs.vertx.web)
    implementation(libs.vertx.auth.jwt)
    implementation(libs.slf4j.api)
}
