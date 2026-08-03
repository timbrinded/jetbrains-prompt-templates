import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
    implementation(project(":core"))

    testImplementation(kotlin("test"))

    intellijPlatform {
        intellijIdea("2025.3.6")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.timbrinded.prompttemplates"
        name = "Prompt Templates"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "253"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("prompt-templates")
}
