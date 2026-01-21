# OrzMCBackup

[![release-lib](https://github.com/OrzGeeker/OrzMCBackup/actions/workflows/release-lib.yml/badge.svg)](https://github.com/OrzGeeker/OrzMCBackup/actions/workflows/release-lib.yml)
[![release-app](https://github.com/OrzGeeker/OrzMCBackup/actions/workflows/release-app.yml/badge.svg)](https://github.com/OrzGeeker/OrzMCBackup/actions/workflows/release-app.yml)
[![test-matrix](https://github.com/OrzGeeker/OrzMCBackup/actions/workflows/test-matrix.yml/badge.svg)](https://github.com/OrzGeeker/OrzMCBackup/actions/workflows/test-matrix.yml)

Kotlin/Gradle 独立工程，提供 Minecraft Java 世界优化功能：扫描各维度的 region/entities/poi MCA 文件，根据 InhabitedTime
阈值与强制加载列表（chunks.dat）保留区块并重写输出，同时支持进度与可选压缩输出。

## 快速开始

- 构建可执行 JAR：

  ```bash
  ./gradlew :app:shadowJar --no-daemon
  ```

- 产物位置：app/build/libs/backup-<version>.jar（版本由根项目统一注入）
- 运行示例：

  ```bash
  # 指定输入与输出目录
  java -jar app/build/libs/backup-0.1.0.jar /path/to/world /path/to/out -t 600 --zip-output

  # 原地处理（覆盖输入目录）
  java -jar app/build/libs/backup-0.1.0.jar /path/to/world --in-place --progress-mode global

  # 写入报告文件（JSON 或 CSV）
  java -jar app/build/libs/backup-0.1.0.jar /path/to/world /path/to/out -t 0 --report-file /tmp/report.json --report-format json
  ```

## CLI 选项

- WORLD_DIR：世界根目录
- OUTPUT_DIR：输出目录（可选；非原地模式时必须为空）
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
- --copy-misc：非原地模式下，将每个维度目录中除 region/entities/poi 以外的文件与文件夹复制到输出对应维度目录（默认 true；支持否定形式：--copy-misc=false 或 --no-copy-misc）

## 作为库使用

- [公共Maven仓库发布地址](https://repo1.maven.org/maven2/io/github/wangzhizhou/backup-core/)


- 发布到本地 Maven 仓库：

  ```bash
  ./gradlew :core:publishToMavenLocal --no-daemon
  ```

- 其他工程依赖：

  ```kotlin
  dependencies {
      implementation("io.github.wangzhizhou:backup-core:<version>")
  }
  ```

- 核心 API 调用示例（run(config)）：

  ```kotlin
  import com.jokerhub.orzmc.world.*
  import java.nio.file.Paths

  fun optimize() {
      val cfg = OptimizerConfig(
          input = Paths.get("/path/to/world"),
          output = Paths.get("/path/to/out"),
          inhabitedThresholdSeconds = 600,
          removeUnknown = false,
          progressMode = ProgressMode.Global,
          zipOutput = false,
          inPlace = false,
          force = true,
          strict = false,
          progressInterval = 1000,
          progressIntervalMs = 0,
          onError = { e -> println("Error: $e") },
          onProgress = { e ->
              if (e.stage == ProgressStage.ChunkProgress) {
                  val cur = e.current ?: 0
                  val tot = e.total ?: 0
                  val pct = if (tot > 0) (cur * 100) / tot else 0
                  println("进度：$pct%（$cur/$tot）")
              }
          },
          parallelism = 2
      )
      val report = Optimizer.run(cfg)
      println(ReportIO.toJson(report))
  }
  ```

- 原地处理与压缩输出示例：

  ```kotlin
  import com.jokerhub.orzmc.world.*
  import java.nio.file.Paths

  fun inplaceAndZip() {
      val config = OptimizerConfig(
          input = Paths.get("/path/to/world"),
          output = Paths.get("/path/to/out"),
          inhabitedThresholdSeconds = 300,
          removeUnknown = false,
          progressMode = ProgressMode.Global,
          zipOutput = true,
          inPlace = false,
          force = false,
          strict = false,
          progressInterval = 500,
          progressIntervalMs = 0,
          onError = { e -> println("Error: $e") },
          onProgress = { e -> println(e) },
          parallelism = 1,
          reportSink = FileReportSink(Paths.get("/path/to/report.json"), "json")
      )
      val report = Optimizer.run(config)
      println(ReportIO.toJson(report))
  }
  ```

- OptimizerConfig 参数说明（常用）：
    - input/output：输入与输出路径；原地模式下 output 可空
    - inhabitedThresholdSeconds：InhabitedTime 阈值（秒）
    - removeUnknown：是否删除未知/外部压缩区块
    - progressMode：进度粒度（Off/Global/Region）
    - zipOutput/inPlace/force/strict：输出压缩、原地处理、强制覆盖、严格模式
    - progressInterval/progressIntervalMs：进度回调按区块或按时间的节流
    - parallelism：并行维度处理的线程数
    - onError/onProgress：回调（可选）
    - progressSink/reportSink：进度与报告输出的抽象（可选；默认复用回调与文件写入逻辑）

## 测试

- 测试
  ```bash
  ./gradlew :core:test --no-daemon
  ```
- CI 多版本测试矩阵：JDK 8/11/17/21 在推送与 PR 自动运行
  -
  工作流：[test-matrix.yml](file:///Users/bytedance/Documents/OrzMC/tools/OrzMCBackup/.github/workflows/test-matrix.yml)

- 测试数据：建议将 Fixtures 目录纳入版本控制（位置：core/src/test/resources/Fixtures），示例文件：
    - Fixtures/world/region/r.0.0.mca
    - Fixtures/world/data/chunks.dat
    - （可选）entities/poi 同名 MCA 文件

## 支持的压缩格式与校验

- 区块数据压缩：RAW、ZLIB、GZIP、LZ4（LZ4Block）
- LZ4 校验：使用 xxhash seed 0x9747b28c 并按 0x0FFFFFFF 掩码比较，校验失败会抛出错误
- 外部压缩（External*）条目：根据 --remove-unknown 决定是否保留

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
├─ gradle/gradle-wrapper.properties
├─ gradlew / gradlew.bat
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
└─ README.md
```

## 构建环境与配置

- Gradle Wrapper：固定 8.7（与 Shadow 插件及 Kotlin 1.9.22 兼容）
- 仓库级独立配置：Wrapper 分发与缓存存储在仓库目录，避免依赖用户主目录
  -
  配置位置：[gradle-wrapper.properties](file:///Users/bytedance/Documents/OrzMC/tools/OrzMCBackup/gradle/wrapper/gradle-wrapper.properties)
  - distributionBase=PROJECT
  - zipStoreBase=PROJECT
-

插件版本与仓源统一在根项目声明：[build.gradle.kts](file:///Users/bytedance/Documents/OrzMC/tools/OrzMCBackup/build.gradle.kts)

- 所有模块的 group 与 version 由根项目统一注入（支持 CI 通过 -Pversion 传入）
- JDK：默认使用当前环境的 JDK（无需强制 toolchain），已在 macOS aarch64 上验证

## 发布到 Maven Central（Publisher Portal 原生）

- 核心库生成可上传 bundle：

  ```bash
  ./gradlew :core:portalBundle --no-daemon -Pversion=0.1.0
  # 产物：core/build/portal-bundle.zip
  ```

- GitHub Actions
  工作流（lib）：[release-lib.yml](file:///Users/bytedance/Documents/OrzMC/tools/OrzMCBackup/.github/workflows/release-lib.yml)
    - 触发：push 标签 vX.Y.Z 或手动 workflow_dispatch
    - 版本：VERSION=${GITHUB_REF_NAME#v} 或 inputs.version
    - JDK：Temurin 21
    - 构建签名与 bundle：Gradle 接收签名参数并生成 core/build/portal-bundle.zip
    - 上传：仅使用 Bearer Token（Authorization: Bearer $CENTRAL_TOKEN），并记录响应头与体
    - 上传前校验：对 portal-bundle.zip 做完整性校验（unzip -t 与 sha256sum）

- 仓库 Secrets：
    - CENTRAL_TOKEN：Central Portal 用户令牌（仅 Bearer 认证，已移除基本认证）
    - CENTRAL_PORTAL_UPLOAD_URL：Portal 上传端点 URL
    - SIGNING_KEY_ID / SIGNING_KEY / SIGNING_PASSWORD：GPG 签名机密（SIGNING_KEY 需为 base64 编码的 ASCII 装甲私钥）

生成 SIGNING_KEY 示例：

```bash
gpg --armor --export-secret-keys BCCC75CA1FC0B44D4C5B4DD7474C495699A70EC6 | base64
# 将输出的 base64 值配置为 GitHub Secrets: SIGNING_KEY
```

签名参数说明：

- Gradle 会在构建脚本中自动对 -Psigning.key 的 base64 解码，然后使用 useInMemoryPgpKeys 进行签名
- SIGNING_PASSWORD：若私钥受口令保护，则需要提供；未保护可留空
- SIGNING_KEY_ID：支持 8 位短 Key ID（如 00B5050F 或 0x00B5050F）
- 也可填写 40 位指纹（如 BCCC75CA...），构建脚本会自动归一化为最后 8 位并转换为大写

## POM 元数据与签名

- POM
  与签名位置：[core/build.gradle.kts](file:///Users/bytedance/Documents/OrzMC/tools/OrzMCBackup/core/build.gradle.kts)
- 许可证：Apache License 2.0
    - 开发者：id=orzmc，name=wangzhizhou，email=824219521@qq.com
    - SCM：GitHub 仓库链接与连接串
    - 生成 sourcesJar 与 javadocJar（Dokka）并在提供密钥时自动签名

工作流与签名实现位置：

-

库发布工作流：[release-lib.yml](file:///Users/bytedance/Documents/OrzMC/tools/OrzMCBackup/.github/workflows/release-lib.yml)

- App
  发布工作流：[release-app.yml](file:///Users/bytedance/Documents/OrzMC/tools/OrzMCBackup/.github/workflows/release-app.yml)
-

签名与发布配置：[core/build.gradle.kts](file:///Users/bytedance/Documents/OrzMC/tools/OrzMCBackup/core/build.gradle.kts)

## 许可与致谢

- Apache-2.0；感谢社区与原实现的启发与样例支持
