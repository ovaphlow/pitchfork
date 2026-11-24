plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        mavenCentral()
    }
}

// 无例外：所有迁移必须归属 libs/<module>。2026-11-21 删除 Trainova 后，
// 曾经的 apps/trainova V5–V7 基线随之删除，这里不再有白名单条目。
val legacyAppMigrations = emptySet<String>()

val verifyMigrationOwnership by tasks.registering {
    group = "verification"
    description = "Rejects new Flyway migrations under application composition roots."

    doLast {
        val unexpected = fileTree("apps") {
            include("*/src/main/resources/db/migration/*.sql")
        }.files
            .map { project.relativePath(it) }
            .filterNot(legacyAppMigrations::contains)

        check(unexpected.isEmpty()) {
            "Application migrations are not allowed. Move these to their owning lib: ${unexpected.joinToString()}"
        }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.matching { it.name == "run" || it.name == "installDist" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyMigrationOwnership"))
    }
}
