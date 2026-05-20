plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "kotlin")

    kotlin {
        jvmToolchain(21)
    }
}
