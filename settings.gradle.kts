pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "OrzMCBackup"
include("core", "app")

// C5: 构建缓存。本地缓存目录为 Gradle 默认位置 ~/.gradle/caches/build-cache-1，
// 由 gradle/actions/setup-gradle 通过 GitHub Actions 缓存持久化，实现跨 job / 跨 PR 复用。
// 远端 HTTP 缓存服务（Develocity 等）未接入；需要时在此加 remote(HttpBuildCache { ... })。
buildCache {
    local {
        isEnabled = true
        // 未使用条目清理周期沿用 Gradle 默认（7 天），无需显式配置
    }
}
