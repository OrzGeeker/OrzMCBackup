---
name: build-cli
description: 构建 OrzMCBackup 的 CLI fat JAR（shadowJar）。产物位于 app/build/libs/backup-<version>.jar。当需要生成可执行 JAR、验证 CLI 可运行、或发布前构建时使用。
---

# 构建 CLI fat JAR

构建命令（Gradle Wrapper，无守护进程）：

```bash
./gradlew :app:shadowJar --no-daemon
```

产物：`app/build/libs/backup-<version>.jar`

## 版本说明

- 本地默认版本为 `0.1.0`（根 `build.gradle.kts` 的 fallback），除非显式传入 `-Pversion`。
- CI 发布时按 tag 注入版本（如 `-Pversion=0.2.0` 对应 `backup-0.2.0.jar`）。
- 需要指定版本时：

```bash
./gradlew :app:shadowJar -Pversion=0.2.0 --no-daemon
```

## 运行验证

```bash
java -jar app/build/libs/backup-<version>.jar --help
```

帮助输出应显示 `backup` 命令与全部选项（`-t/--inhabited-time-seconds`、`--in-place`、`--copy-misc`、
`--parallelism`、`--dry-run` 等），以及 `merge` 子命令。
