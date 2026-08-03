plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.intellij.platform") version "2.18.1" apply false
}

allprojects {
    group = "dev.timbrinded.prompttemplates"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}
