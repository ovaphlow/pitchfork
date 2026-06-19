rootProject.name = "service-vertx-kotlin"

include(
    "libs:auth",
    "libs:settings",
    "libs:files",
    "libs:permissions",
    "libs:database",
    "libs:knowledge",
    "libs:skills",
    "libs:common",
    "libs:messages",
    "libs:users",
    "libs:trainings",
    "libs:exams",
    "libs:onsite",
    "libs:inventories",
    "libs:logging",
    "libs:analytics",
    "apps:aceso",
    "apps:trainova"
)

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin/")
        mavenCentral()
        gradlePluginPortal()
    }
}
