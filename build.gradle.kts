plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.intellij.platform") version "2.18.1" apply false
}

allprojects {
    group = "dev.smoothbrains.prompttemplates"
    version = "0.3.0"

    repositories {
        mavenCentral()
    }
}
