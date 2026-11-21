import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec

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

    tasks.withType<JavaExec>().matching { it.name == "generateJooq" }.configureEach {
        doFirst {
            val password = System.getenv("PITCHFORK_DB_PASSWORD")?.takeIf(String::isNotBlank)
                ?: throw GradleException("PITCHFORK_DB_PASSWORD must be set before running generateJooq")
            val template = project.file("jooq-config.xml")
            val rendered = project.layout.buildDirectory.file("tmp/generateJooq/jooq-config.xml").get().asFile
            val escapedPassword = password
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

            rendered.parentFile.mkdirs()
            rendered.writeText(template.readText().replace("__PITCHFORK_DB_PASSWORD__", escapedPassword))
            setArgs(listOf(rendered.absolutePath))
        }
    }

    tasks.matching { it.name == "run" || it.name == "installDist" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyMigrationOwnership"))
    }
}
