import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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

sourceSets.create("integrationTest")

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

val integrationTestRuntimeOnly by configurations.getting

dependencies {
    implementation(project(":core")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    testImplementation(kotlin("test"))

    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
    integrationTestRuntimeOnly("org.jetbrains.intellij.deps:teamcity-service-messages:2019.1.4-alpha")

    intellijPlatform {
        intellijIdea("2026.2.0.1")
        testFramework(
            TestFrameworkType.Starter,
            configurationName = "integrationTestImplementation",
        )
    }
}

kotlin {
    jvmToolchain(25)
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

val integrationTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        val staleOutput = listOf(
            rootProject.layout.projectDirectory.dir("out/ide-tests/tests").asFile,
            layout.buildDirectory.dir("ui-test").get().asFile,
            layout.buildDirectory.dir("allure-results").get().asFile,
        )

        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        workingDir = rootProject.layout.projectDirectory.asFile

        systemProperty(
            "path.to.build.plugin",
            tasks.prepareSandbox.get().pluginDirectory.get().asFile,
        )
        systemProperty(
            "prompt.templates.ui.test.output",
            layout.buildDirectory.dir("ui-test").get().asFile,
        )
        systemProperty(
            "allure.results.directory",
            layout.buildDirectory.dir("allure-results").get().asFile,
        )
        systemProperty("prompt.templates.explore", providers.gradleProperty("explore").orElse("false").get())

        useJUnitPlatform {
            excludeEngines("junit-vintage")
        }
        dependsOn(tasks.prepareSandbox)

        doFirst {
            check(staleOutput.all { it.deleteRecursively() }) {
                "Unable to clean stale IDE integration-test output."
            }
        }

        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
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
