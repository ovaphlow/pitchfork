rootProject.name = "service-vertx-kotlin"

include(
    "libs:auth",
    "libs:settings",
    "libs:files",
    "libs:permission",
    "libs:database",
    "libs:common",
    "libs:messages",
    "libs:users",
    "libs:knowledge",
    "libs:skills",
    "libs:training",
    "libs:exam",
    "libs:onsite",
    "libs:ai-assistant",
    "libs:analytics",
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
