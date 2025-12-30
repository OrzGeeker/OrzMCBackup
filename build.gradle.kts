plugins {
    kotlin("jvm") version "1.9.22" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

val releasedVersion = (findProperty("version") as String?) ?: "0.1.0"

allprojects {
    repositories {
        mavenCentral()
    }
    group = "io.github.wangzhizhou"
    version = releasedVersion
}
