plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.jokerhub.orzmc"
version = "0.1.0"

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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.jokerhub.orzmc"
            artifactId = "backup-core"
            version = "0.1.0"
        }
    }
}
