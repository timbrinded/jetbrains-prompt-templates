import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.gradle.api.tasks.bundling.Zip

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
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    compilerOptions.jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.timbrinded.prompttemplates"
        name = "Prompt Templates"
        version = project.version.toString()
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.RustRover, "2026.2")
            create(IntelliJPlatformType.WebStorm, "2026.2")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("prompt-templates")
}
