import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
}

// Use current JDK; no enforced toolchain to ease local builds

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.jokerhub.orzmc.cli.Main")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}

// Threshold set to ~50% to stay safely below the measured 57% instruction
// coverage while still blocking meaningful regressions (Main.kt dispatch drags
// the aggregate down).
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 50
                }
            }
        }
    }
}

// 质量门禁：coverage 阈值不达标时 `./gradlew check` 直接失败（P1 质量门禁）。
tasks.check {
    dependsOn("koverVerify")
}

tasks.withType<Jar>().named("shadowJar") {
    archiveBaseName.set("backup")
    archiveClassifier.set("")
    // 固定文件名 backup.jar（版本只写入 manifest 的 Implementation-Version），
    // 避免 Gradle 把 project.version 拼进文件名（未设版本时又省略后缀）导致脚本引用漂移。
    archiveVersion.set("")
    manifest {
        attributes(
            mapOf(
                "Main-Class" to "com.jokerhub.orzmc.cli.Main",
                "Implementation-Version" to project.version.toString(),
            ),
        )
    }
}
