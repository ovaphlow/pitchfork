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

val legacyAppMigrations = setOf(
    "apps/trainova/src/main/resources/db/migration/V5__factory_training_tables.sql",
    "apps/trainova/src/main/resources/db/migration/V6__add_course_metadata.sql",
    "apps/trainova/src/main/resources/db/migration/V7__rename_knowledge_entries_extra_to_metadata.sql",
)

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
