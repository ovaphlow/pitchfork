rootProject.name = "service-vertx-kotlin"

include(
    "libs:database",
    "libs:common",
    "libs:inventories",
    "libs:logging",
    "libs:pharmacy",
    "libs:healthcare",
    "libs:nursing",
    "libs:dining",
    "apps:aceso",
)

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin/")
        mavenCentral()
        gradlePluginPortal()
    }
}
