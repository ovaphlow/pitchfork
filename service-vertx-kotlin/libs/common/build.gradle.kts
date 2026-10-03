plugins {
    `java-library`
}

dependencies {
    // 042 L-POLICY：本模块承载不依赖 vertx 的纯策略（OperatorPolicy），
    // 因此需要自带测试依赖——此前只有 java-library，没有 test 源集依赖。
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
