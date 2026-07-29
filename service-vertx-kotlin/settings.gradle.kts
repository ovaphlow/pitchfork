rootProject.name = "service-vertx-kotlin"

include(
    "libs:permissions",
    "libs:database",
    "libs:knowledge",
    "libs:skills",
    "libs:common",
    "libs:trainings",
    "libs:exams",
    "libs:onsite",
    "libs:inventories",
    "libs:logging",
    "libs:pharmacy",
    "libs:analytics",
    "libs:healthcare",
    "libs:nursing",
    "apps:aceso",
    "apps:trainova",
)

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin/")
        mavenCentral()
        gradlePluginPortal()
    }
}
