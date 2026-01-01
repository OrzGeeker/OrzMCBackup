plugins {
    kotlin("jvm")
    `maven-publish`
    signing
    id("org.jetbrains.dokka") version "1.9.20"
}


// Use current JDK; no enforced toolchain to ease local builds

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.lz4:lz4-java:1.8.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

tasks.test {
    useJUnitPlatform()
}

// Test resources are expected under src/test/resources/Fixtures committed to VCS

java {
    withSourcesJar()
}

tasks.register<Jar>("javadocJar") {
    dependsOn("dokkaHtml")
    from("$buildDir/dokka/html")
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.jokerhub.orzmc"
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
            url = uri("$buildDir/portal-repo")
        }
    }
}

signing {
    val keyId = findProperty("signing.keyId") as String?
    val key = findProperty("signing.key") as String?
    val password = findProperty("signing.password") as String?
    if (!key.isNullOrBlank()) {
        useInMemoryPgpKeys(keyId, key, password)
        sign(publishing.publications["mavenJava"])
    }
}

tasks.register<Zip>("portalBundle") {
    dependsOn("publishMavenJavaPublicationToPortalRepoRepository")
    from("$buildDir/portal-repo")
    archiveFileName.set("portal-bundle.zip")
    destinationDirectory.set(file("$buildDir"))
}
