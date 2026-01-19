plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Use current JDK; no enforced toolchain to ease local builds

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.6")
}

application {
    mainClass.set("com.jokerhub.orzmc.cli.Main")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}



tasks.matching { it.name == "shadowJar" }.configureEach {
    (this as com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar).apply {
        archiveBaseName.set("backup")
        archiveClassifier.set("")
        manifest {
            attributes(mapOf("Main-Class" to "com.jokerhub.orzmc.cli.Main"))
        }
    }
}
