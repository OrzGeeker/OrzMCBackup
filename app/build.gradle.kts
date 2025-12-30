plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

group = "com.example.thanos"
version = "0.1.0"

// Use current JDK; no enforced toolchain to ease local builds

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.6")
}

application {
    mainClass.set("com.example.thanos.cli.Main")
}

// Apply Shadow plugin conditionally to allow Gradle 9 to run 'gradle wrapper'
if (gradle.gradleVersion.startsWith("8.")) {
    pluginManager.apply("com.github.johnrengelman.shadow")
}

tasks.matching { it.name == "shadowJar" }.configureEach {
    (this as com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar).apply {
        archiveBaseName.set("backup")
        archiveClassifier.set("")
        manifest {
            attributes(mapOf("Main-Class" to "com.example.thanos.cli.Main"))
        }
    }
}
