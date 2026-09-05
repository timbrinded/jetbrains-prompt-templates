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

tasks.test {
    useJUnitPlatform()
    systemProperty("test.runtime.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}
