import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

plugins {
    kotlin("jvm")
    `maven-publish`
    signing
    id("org.jetbrains.dokka") version "1.9.20"
}


// Use current JDK; no enforced toolchain to ease local builds

dependencies {
    implementation(kotlin("stdlib"))
    implementation("at.yawk.lz4:lz4-java:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

tasks.test {
    useJUnitPlatform()
}

// Test resources are expected under src/test/resources/Fixtures committed to VCS

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.register<Jar>("javadocJar") {
    dependsOn("dokkaHtml")
    from(layout.buildDirectory.dir("dokka/html"))
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "io.github.wangzhizhou"
            artifactId = "backup-core"
            artifact(tasks.named("javadocJar"))

            pom {
                name.set("OrzMC Backup Core")
                description.set("Core library for optimizing Minecraft Java worlds")
                url.set("https://github.com/OrzGeeker/OrzMCBackup")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("orzmc")
                        name.set("wangzhizhou")
                        email.set("824219521@qq.com")
                    }
                }
                scm {
                    url.set("https://github.com/OrzGeeker/OrzMCBackup")
                    connection.set("scm:git:https://github.com/OrzGeeker/OrzMCBackup.git")
                    developerConnection.set("scm:git:ssh://git@github.com/OrzGeeker/OrzMCBackup.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "portalRepo"
            url = uri(layout.buildDirectory.dir("portal-repo").map { it.asFile.toURI().toString() }.get())
        }
    }
}

signing {
    val keyId = (findProperty("signing.keyId") as String?)
    val password = (findProperty("signing.password") as String?)
    val key = (findProperty("signing.key") as String?)
    val normalizedKeyId = keyId?.let {
        val s = it.removePrefix("0x")
        val hex40 = Regex("^[0-9A-Fa-f]{40}$")
        val hex8 = Regex("^[0-9A-Fa-f]{8}$")
        when {
            hex40.matches(s) -> s.takeLast(8).uppercase()
            hex8.matches(s) -> s.uppercase()
            else -> it
        }
    }
    if (!key.isNullOrBlank()) {
        val decoded = runCatching {
            String(Base64.getDecoder().decode(key), Charsets.UTF_8)
        }.getOrElse { key }
        useInMemoryPgpKeys(normalizedKeyId, decoded, password)
        sign(publishing.publications["mavenJava"])
    }
}

tasks.register<Zip>("portalBundle") {
    dependsOn("publishMavenJavaPublicationToPortalRepoRepository")
    from(layout.buildDirectory.dir("portal-repo"))
    archiveFileName.set("portal-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}
