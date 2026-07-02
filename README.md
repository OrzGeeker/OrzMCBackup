# OrzMCBackup

[![release-lib](https://github.com/OrzMC/OrzMCBackup/actions/workflows/release-lib.yml/badge.svg)](https://github.com/OrzMC/OrzMCBackup/actions/workflows/release-lib.yml)
[![release-app](https://github.com/OrzMC/OrzMCBackup/actions/workflows/release-app.yml/badge.svg)](https://github.com/OrzMC/OrzMCBackup/actions/workflows/release-app.yml)
[![test-matrix](https://github.com/OrzMC/OrzMCBackup/actions/workflows/test-matrix.yml/badge.svg)](https://github.com/OrzMC/OrzMCBackup/actions/workflows/test-matrix.yml)
[![codecov](https://codecov.io/gh/OrzMC/OrzMCBackup/branch/main/graph/badge.svg)](https://codecov.io/gh/OrzMC/OrzMCBackup)
[![license](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Kotlin/Gradle 独立工程，提供 Minecraft Java 世界优化功能：扫描各维度的 region/entities/poi MCA 文件，根据 InhabitedTime
阈值、强制加载列表（支持新旧格式）或矩形范围保留区块并重写输出，同时支持进度与可选压缩输出。

## 快速开始（CLI）
1. 构建可执行 JAR：
  ```bash
  ./gradlew :app:shadowJar --no-daemon
  ```
2. 产物位置：app/build/libs/backup-<version>.jar（版本由根项目统一注入）
3. 运行示例：
  ```bash
  # 指定输入与输出目录
  java -jar app/build/libs/backup-0.1.0.jar /path/to/world /path/to/out -t 600 --zip-output
  # 原地处理（覆盖输入目录）
  java -jar app/build/libs/backup-0.1.0.jar /path/to/world --in-place --progress-mode global
  # 写入报告文件（JSON 或 CSV）
  java -jar app/build/libs/backup-0.1.0.jar /path/to/world /path/to/out -t 0 --report-file /tmp/report.json --report-format json
  ```

### CLI 参数

- WORLD_DIR：世界根目录
- OUTPUT_DIR：输出目录（可选；非原地模式时必须为空，除非使用 --force 清空覆盖）
- -t, --inhabited-time-seconds：InhabitedTime 阈值（秒，1 秒=20 tick，默认 300）
- --remove-unknown：将未知/外部压缩的区块视为可删除
- --progress-mode：Off | Global | Region（默认 Region）
- --in-place：原地处理，忽略输出目录并替换输入目录
- --zip-output：将输出目录打包为时间戳 zip 并删除目录
- -f, --force：覆盖已存在且非空的输出目录（无交互）
- --strict：严格模式，遇到损坏的 MCA 或解析失败时返回非零退出码
- --report：在标准输出打印处理统计与错误列表
- --report-file：将报告写入文件（支持 JSON/CSV）
- --report-format：json | csv（默认 json）
- --progress-interval：进度回调的区块粒度（默认 1000）
- --progress-interval-ms：进度回调的时间粒度，>0 时优先使用（默认 0）
- --parallelism：并行处理维度的线程数（默认 1）
- --copy-misc：非原地模式下，复制维度目录及 world 级目录中除 region/entities/poi 以外的文件与文件夹（默认 true；支持 --copy-misc=false 或 --no-copy-misc）。26.1+ 结构下，无论输入是服务器根目录还是 world 目录，都会正确复制 world 级别的杂项文件（`level.dat`、`data/`、`players/` 等）
- --dry-run：预览模式，只扫描统计不写入任何输出（默认 false）

## 作为库使用
- [公共Maven仓库发布地址](https://repo1.maven.org/maven2/io/github/wangzhizhou/backup-core/)
- 发布到本地 Maven 仓库：
  ```bash
  ./gradlew :core:publishToMavenLocal --no-daemon
  ```
- 依赖声明：
  ```kotlin
  dependencies {
      implementation("io.github.wangzhizhou:backup-core:<version>")
  }
  ```

### Kotlin DSL（推荐）
最小化示例（默认值）：
```kotlin
import com.jokerhub.orzmc.world.*
import java.nio.file.Paths

fun minimal() {
    val report = Optimizer.run(Paths.get("/path/to/world"), Paths.get("/path/to/out"))
    println(ReportIO.toJson(report))
}
```

进阶示例（更短 DSL + 自动补全扩展名）：
```kotlin
import com.jokerhub.orzmc.world.*
import java.nio.file.Paths

fun advanced() {
    val report = Optimizer.run(Paths.get("/path/to/world"), Paths.get("/path/to/out")) {
        inhabitedThresholdSeconds = 0
        strict = true
        inPlace = false
        zipOutput = true
        progressSink = { e -> println(e) }
        reportFormat = "csv"
        reportSink = "/path/to/report"
    }
    println(ReportIO.toJson(report))
}
```

### Kotlin 结构化配置
```kotlin
import com.jokerhub.orzmc.world.*
import java.nio.file.Paths

fun structured() {
    val request = OptimizerRequest(
        input = Paths.get("/path/to/world"),
        output = Paths.get("/path/to/out"),
        filter = FilterOptions(
            inhabitedThresholdSeconds = 600,
            removeUnknown = true,
            strict = false
        ),
        outputOptions = OutputOptions(
            inPlace = false,
            zipOutput = true,
            force = true,
            copyMisc = true
        ),
        progress = ProgressOptions(
            interval = 500,
            intervalMs = 0,
            sink = CallbackProgressSink { e -> println(e) }
        ),
        runtime = RuntimeOptions(parallelism = 2),
        hooks = Hooks(
            onError = { e -> println("Error: $e") },
            reportSink = FileReportSink(Paths.get("/path/to/report.json"), "json")
        ),
        io = IOOptions(
            fs = RealFileSystem,
            ioFactory = DefaultMcaIOFactory()
        )
    )
    val report = Optimizer.run(request)
    println(ReportIO.toJson(report))
}
```

### Java 调用（不使用 DSL）
完整示例：
```java
import com.jokerhub.orzmc.world.*;
import java.nio.file.Paths;

public class JavaExample {
    public static void main(String[] args) {
        OptimizerRequest request = new OptimizerRequest(
            Paths.get("/path/to/world"),
            Paths.get("/path/to/out"),
            new FilterOptions(600, false, false),
            new OutputOptions(false, true, true, true),
            new ProgressOptions(1000L, 0L, new CallbackProgressSink(e -> System.out.println(e))),
            new RuntimeOptions(2),
            new Hooks(
                e -> System.out.println("Error: " + e),
                new FileReportSink(Paths.get("/path/to/report.json"), "json"),
                null
            ),
            new IOOptions(RealFileSystem.INSTANCE, new DefaultMcaIOFactory())
        );
        OptimizeReport report = Optimizer.run(request);
        System.out.println(ReportIO.toJson(report));
    }
}
```

最小化示例：
```java
import com.jokerhub.orzmc.world.*;
import java.nio.file.Paths;

public class JavaMinimal {
    public static void main(String[] args) {
        OptimizerRequest request = new OptimizerRequest(
            Paths.get("/path/to/world"),
            Paths.get("/path/to/out"),
            new FilterOptions(),
            new OutputOptions(),
            new ProgressOptions(),
            new RuntimeOptions(),
            new Hooks(),
            new IOOptions()
        );
        OptimizeReport report = Optimizer.run(request);
        System.out.println(ReportIO.toJson(report));
    }
}
```

reportSink 自动补全示例：
```java
import com.jokerhub.orzmc.world.*;
import java.nio.file.Paths;

public class JavaReportSinkAuto {
    public static void main(String[] args) {
        OptimizerRequestBuilder builder = new OptimizerRequestBuilder(
            Paths.get("/path/to/world"),
            Paths.get("/path/to/out")
        );
        builder.reportFormat = "csv";
        builder.reportSink = "/path/to/report";
        OptimizeReport report = Optimizer.run(builder.build());
        System.out.println(ReportIO.toJson(report));
    }
}
```

### 迁移旧版 OptimizerConfig 到新 DSL
- input/output → Optimizer.run(input, output) 或 OptimizerRequest(input/output)
- inhabitedThresholdSeconds → inhabitedThresholdSeconds 或 filter.inhabitedThresholdSeconds
- removeUnknown → removeUnknown 或 filter.removeUnknown
- zipOutput → zipOutput 或 outputOptions.zipOutput
- inPlace → inPlace 或 outputOptions.inPlace
- force → force 或 outputOptions.force
- strict → strict 或 filter.strict
- progressInterval → progressInterval 或 progress.interval
- progressIntervalMs → progressIntervalMs 或 progress.intervalMs
- onError → onError { } 或 hooks.onError
- onProgress → progressSink = { } / onProgress { } 或 progress.sink
- parallelism → parallelism 或 runtime.parallelism
- copyMisc → copyMisc 或 outputOptions.copyMisc
- progressSink → progressSink 或 progress.sink
- reportSink → reportSink/reportFormat 或 hooks.reportSink
- fs → fs 或 io.fs
- ioFactory → ioFactory 或 io.ioFactory
- metricsSink → metricsSink 或 hooks.metricsSink

### 参数速查（新 DSL）
- input/output：输入与输出路径；原地模式下 output 可空
- inhabitedThresholdSeconds/removeUnknown/strict：过滤规则
- zipOutput/inPlace/force/copyMisc：输出策略与杂项复制
- progressInterval/progressIntervalMs：进度回调节流
- progressSink/onProgress：进度回调入口
- parallelism：并行维度处理的线程数
- reportSink/reportFormat：报告输出位置与格式（reportSink 允许 Path/String，省略扩展名时自动补齐）
- fs/ioFactory/metricsSink/onError：IO、指标与错误回调
- 分组配置：filter/outputOptions/progress/runtime/hooks/io

## 测试
```bash
./gradlew :core:test --no-daemon
```

- 测试数据：建议将 Fixtures 目录纳入版本控制（位置：core/src/test/resources/Fixtures），示例文件：
    - Fixtures/world/region/r.0.0.mca
    - Fixtures/world/data/chunks.dat
    - （可选）entities/poi 同名 MCA 文件

## 支持的压缩格式与 Minecraft 版本

### 区块数据压缩格式
- 区块数据压缩：RAW、ZLIB、GZIP、LZ4（LZ4Block）
- LZ4 校验：使用 xxhash seed 0x9747b28c 并按 0x0FFFFFFF 掩码比较，校验失败会抛出错误
- 外部压缩（External*）条目：根据 --remove-unknown 决定是否保留

### 世界格式支持
- **标准格式（Minecraft 1.2.1+）：** 维度目录直接包含 `region/`、`entities/`、`poi/`
- **26.1+ 格式（Minecraft 26w+）：** 维度嵌套在 `dimensions/minecraft/<dimension>/` 下，自动递归发现
- **强制加载区块：** 同时支持 `data/minecraft/chunk_tickets.dat`（新格式）和 `data/chunks.dat`（旧格式），自动按优先级尝试
- **InhabitedTime 阈值：** 使用严格大于（`>`）比较；设置 `-t 0` 可移除未曾访问（`InhabitedTime == 0`）的区块

## 项目结构

```text
OrzMCBackup/
├─ app/                       # CLI 模块
│  ├─ build.gradle.kts
│  └─ src/main/kotlin/com/jokerhub/orzmc/cli/Main.kt
├─ core/                      # 核心库模块
│  ├─ build.gradle.kts
│  └─ src/
│     ├─ main/kotlin/com/jokerhub/orzmc/
│     │  ├─ mca/Reader/Writer/Entry
│     │  ├─ patterns/ChunkPattern/InhabitedTime/List
│     │  └─ world/Optimizer/NbtForceLoader
│     └─ test/resources/Fixtures/   # 建议提交的测试样本
├─ .github/workflows/         # CI 工作流
│  ├─ test-matrix.yml         # 测试矩阵（Java 17/21/25 × 3 平台）
│  ├─ release-lib.yml         # 发布库到 Maven Central
│  └─ release-app.yml         # 发布 CLI 可执行 JAR
├─ gradle/wrapper/
│  └─ gradle-wrapper.properties
├─ gradlew / gradlew.bat
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
└─ README.md
```

## 构建环境与配置
- Gradle Wrapper：**9.6.1**（Kotlin 2.4.0 / Shadow 9.4.3 兼容）
- Wrapper 配置位置：[gradle/wrapper/gradle-wrapper.properties](gradle/wrapper/gradle-wrapper.properties)
- Wrapper 缓存：使用 GRADLE_USER_HOME（用户主目录）
- 插件版本与仓源统一在根项目声明：[build.gradle.kts](build.gradle.kts)

- 所有模块的 group 与 version 由根项目统一注入（支持 CI 通过 -Pversion 传入）
- **JDK 要求**：Java 17+（JUnit 6.1.1 最低要求）
- CI 测试矩阵：Java 17 / 21 / 25 × ubuntu / macos / windows
- CI lint/coverage：JDK 25

## 发布到 Maven Central（Publisher Portal 原生）
- 本地生成可上传 bundle：
  ```bash
  ./gradlew :core:portalBundle --no-daemon -Pversion=0.1.0 \
    -Psigning.keyId=<KEY_ID> -Psigning.password=<PASSWORD> -Psigning.key=<KEY_BASE64_OR_ASCII>
  # 产物：core/build/portal-bundle.zip
  ```
- GitHub Actions 工作流：[release-lib.yml](.github/workflows/release-lib.yml)
  - 触发：push 标签 vX.Y.Z 或手动 workflow_dispatch
  - 版本：VERSION=${GITHUB_REF_NAME#v} 或 inputs.version
  - JDK：Temurin 25（构建用，产物目标 Java 17）
  - 流程：运行 :core:test/:app:test → :core:portalBundle → 生成 sha256 → 校验签名与包 → 上传 Portal
  - 上传：Bearer Token（Authorization: Bearer $CENTRAL_TOKEN）
- 仓库 Secrets：
  - CENTRAL_TOKEN：Central Portal 用户令牌
  - CENTRAL_PORTAL_UPLOAD_URL：Portal 上传端点 URL
  - SIGNING_KEY_ID / SIGNING_KEY / SIGNING_PASSWORD：GPG 签名机密（SIGNING_KEY 可为 base64 或 ASCII 装甲）
- SIGNING_KEY 生成示例：
  ```bash
  gpg --armor --export-secret-keys <KEY_ID_OR_FINGERPRINT> | base64
  ```
- 签名参数说明：
  - signing.key：支持 base64 或原始 ASCII 装甲私钥，构建时自动解码
  - signing.keyId：支持 8 位短 ID 或 40 位指纹（会归一化为 8 位大写）
  - signing.password：若私钥有口令则必填

## POM 元数据与签名
- 配置位置：[core/build.gradle.kts](core/build.gradle.kts)
- POM 信息：
  - name：OrzMC Backup Core
  - description：Core library for optimizing Minecraft Java worlds
  - url：https://github.com/OrzMC/OrzMCBackup
  - license：Apache License 2.0（https://www.apache.org/licenses/LICENSE-2.0）
  - developer：id=orzmc，name=wangzhizhou，email=824219521@qq.com
  - scm：
    - url：https://github.com/OrzMC/OrzMCBackup
    - connection：scm:git:https://github.com/OrzMC/OrzMCBackup.git
    - developerConnection：scm:git:ssh://git@github.com/OrzMC/OrzMCBackup.git
- 产物与签名：
  - sourcesJar：withSourcesJar
  - javadocJar：Dokka 生成后打包
  - signing：使用 signing.keyId/signing.key/signing.password（key 支持 base64 或原始 ASCII 装甲）
- 工作流：
  - 发布库：[release-lib.yml](.github/workflows/release-lib.yml)
  - 发布 App：[release-app.yml](.github/workflows/release-app.yml)

## 许可与致谢

- Apache-2.0；感谢社区与原实现的启发与样例支持
