plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
}


// Use current JDK; no enforced toolchain to ease local builds

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.6")
}

application {
    mainClass.set("com.jokerhub.orzmc.cli.Main")
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
