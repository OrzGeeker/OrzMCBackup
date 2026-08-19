plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// Ensure JDK 17+ for Kotlin compiler compatibility
val jvmVersion = System.getProperty("java.version")?.substringBefore(".")?.toIntOrNull() ?: 0
if (jvmVersion >= 30) {
    throw GradleException(
        "JDK 30+ is not yet supported by the embedded Kotlin compiler. " +
        "Please use JDK 17-29. Current JDK: ${System.getProperty("java.version")}"
    )
}

// Gradle 内置的 project.version 属性默认是 "unspecified"（非 null），
// 直接 `?: "0.1.0"` 兜底不会生效，导致未传 -Pversion 时版本号变成 "unspecified"。
// 把 "unspecified" / 空串视为"未设置"，再回落默认版本。
val releasedVersion =
    (findProperty("version") as String?)
        ?.takeIf { it.isNotBlank() && it != "unspecified" }
        ?: "0.1.0"

allprojects {
    repositories {
        mavenCentral()
    }
    group = "io.github.wangzhizhou"
    version = releasedVersion
}

// detekt config is per-module (app and core both have detekt plugin)
