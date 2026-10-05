import org.gradle.api.tasks.bundling.Jar

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
}

kotlin {
    jvmToolchain(25)
}

// Resolved at execution time: resolving the classpath while configuring locks testImplementation.
class TestRuntimeClasspathArgument(@get:Classpath val classpath: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dtest.runtime.classpath=${classpath.asPath}")
}

tasks.test {
    useJUnitPlatform()
    jvmArgumentProviders += TestRuntimeClasspathArgument(sourceSets.test.get().runtimeClasspath)
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}
