rootProject.name = "service-vertx-kotlin"

include(
    "libs:auth",
    "libs:settings",
    "libs:files",
    "apps:service"
)

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin/")
        mavenCentral()
        gradlePluginPortal()
    }
}
