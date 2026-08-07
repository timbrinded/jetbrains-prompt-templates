import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

base {
    archivesName.set("prompt-templates")
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    testImplementation(kotlin("test"))

    intellijPlatform {
        intellijIdea("2026.2.0.1")
    }
}

kotlin {
    compilerOptions.jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.smoothbrains.prompttemplates"
        name = "Prompt Templates"
        version = project.version.toString()
    }

    pluginVerification {
        ides {
            // Explicit verification matrix, not a product-compatibility whitelist.
            create(IntelliJPlatformType.RustRover, "2026.2")
            create(IntelliJPlatformType.WebStorm, "2026.2")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
    from(rootProject.file("THIRD-PARTY-NOTICES")) {
        into("META-INF")
    }
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("prompt-templates")
}
