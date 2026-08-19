# OrzMCBackup

[![release-lib](https://github.com/OrzMC/OrzMCBackup/actions/workflows/release-lib.yml/badge.svg)](https://github.com/OrzMC/OrzMCBackup/actions/workflows/release-lib.yml)
[![release-app](https://github.com/OrzMC/OrzMCBackup/actions/workflows/release-app.yml/badge.svg)](https://github.com/OrzMC/OrzMCBackup/actions/workflows/release-app.yml)
[![test-matrix](https://github.com/OrzMC/OrzMCBackup/actions/workflows/test-matrix.yml/badge.svg)](https://github.com/OrzMC/OrzMCBackup/actions/workflows/test-matrix.yml)
[![codecov](https://codecov.io/gh/OrzMC/OrzMCBackup/branch/main/graph/badge.svg)](https://codecov.io/gh/OrzMC/OrzMCBackup)
[![license](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![GitHub Pages](https://img.shields.io/badge/GitHub%20Pages-live-238636)](https://orzmc.github.io/OrzMCBackup/)

> **Minecraft Java 世界优化与备份工具**：按玩家活跃时间（InhabitedTime）逐区块裁剪世界体积，
> 让备份从 18.6GB 缩到 2.2GB，并提供优化备份与全量备份的槽位级合并恢复。

CLI 工具 + Kotlin/Java 库双形态，支持 PaperMC 26.1+ 新世界格式，专注**数据安全**（强制加载保护、
entities/poi 锁步、零非预期丢失）。

---

## 目录

- [为什么需要](#为什么需要)
- [核心特性](#核心特性)
- [快速开始](#快速开始)
- [CLI 参考](#cli-参考)
- [作为库使用](#作为库使用)
- [支持的格式与 Minecraft 版本](#支持的格式与-minecraft-版本)
- [工作原理](#工作原理)
- [文档导航](#文档导航)
- [测试与质量](#测试与质量)
- [构建与发布](#构建与发布)
- [Agent 协作开发](#agent-协作开发)
- [贡献 / 安全 / 许可](#贡献--安全--许可)

---

## 为什么需要

Minecraft 区块一旦生成就不会自动消失。玩家探索、跑图、飞行会永久生成大量"路过但从未建设"的区块，
世界体积持续膨胀——备份存储成本随之失控。

**OrzMCBackup 在备份前逐区块裁剪低活跃区块**，实测效果（真实 PaperMC 26.2 恢复合并世界，
18.6GB / 21,688 个 `.mca` / 3,162,906 区块，见 [`docs/threshold-benchmark-report.md`](docs/threshold-benchmark-report.md)）：

| 项目 | 数值 |
|------|------|
| 备份前 | 18,637.6 MB（约 18.6 GB） |
| 优化备份后（`-t 300`） | **2,178.3 MB（-88.3%）** |
| 剔除区块 | **92.7%**（2,930,839 / 3,162,906） |
| 非预期丢失文件 | **0**（6 档阈值逐字节验证） |

> 阈值对比实测（0 / 60 / 120 / 180 / 240 / 300 秒）：**60 秒即取得 91.3% 剔除率，相当于 t300
> （92.7%）的约 98%**（-t 0 剔除 71.3%）；HDD 上各档耗时 15–27 分钟，
> 阈值几乎不影响备份耗时，只影响体积。
>
> 更小的体积 = 更低的存储成本 + 更快的备份/恢复 + 更高频的备份保留策略。
> 另一份真实世界验证见 [`docs/real-world-backup-validation.md`](docs/real-world-backup-validation.md)。

**当仅剩"优化备份 + 更早全量备份"时**，直接同名覆盖会造成地图空洞/地形回退。`merge` 子命令以
**chunk 槽位粒度**合并，恢复"全量最新"地图——真实案例：18.64GB 全量 + 2.18GB 优化 → ~18GB 完整恢复，
见 [`docs/papermc-map-backup-recovery-case.md`](docs/papermc-map-backup-recovery-case.md)。

---

## 核心特性

- **InhabitedTime 阈值裁剪**：按玩家累计活跃时间（严格 `>`）保留区块，`-t 0` 可移除从未被访问的区块
- **强制加载保护**：自动解析并保留出生点/区块加载器所在区块，支持新旧两种格式
  （`chunk_tickets.dat` → `chunks.dat`）
- **三文件锁步**：region / entities / poi 按同一决策同步重写，杜绝"有地形无实体/POI"的数据错位
- **惰性写入**：整区被剔除的 region 不生成空文件；保留区逐字节不变
- **26.1+ 新格式支持**：兼容 `dimensions/` 嵌套结构（PaperMC 26.1+ / Vanilla）
- **多种输出模式**：输出到新目录、原地替换、ZIP 打包、dry-run 预览、杂项文件复制
- **merge 合并恢复**：优化备份 + 全量备份的槽位级合并（生态独有能力）
- **可脚本化**：无头 CLI + JSON/CSV 报告 + 进度 + 并行处理，适合 CRON / 面板 / 托管自动备份
- **双形态**：CLI fat JAR + Maven Central 库（Kotlin DSL / Java 均可调用）

---

## 快速开始

### 构建

```bash
./gradlew :app:shadowJar --no-daemon
```

产物：`app/build/libs/backup.jar`（文件名固定；版本号写入 manifest，`--version` 可查）。
本地默认版本 `0.1.0`；用 `-Pversion=X.Y.Z` 注入（如 `-Pversion=0.2.1`）。

### 优化备份

```bash
# 指定输入与输出目录，按 5 分钟活跃阈值裁剪
java -jar app/build/libs/backup.jar /path/to/world /path/to/out -t 300 --zip-output

# 原地处理（替换输入目录）
java -jar app/build/libs/backup.jar /path/to/world --in-place --progress-mode global

# 预览模式（只统计不写入）
java -jar app/build/libs/backup.jar /path/to/world --dry-run --report

# 报告写入文件（JSON）
java -jar app/build/libs/backup.jar /path/to/world /path/to/out -t 0 \
  --report-file /tmp/report.json --report-format json
```

### merge 合并恢复（优化备份 + 更早全量备份）

```bash
# BASE=更早全量备份，PATCH=优化备份，OUTPUT=恢复结果
java -jar app/build/libs/backup.jar merge E:\recover\world E:\recover\world_backup E:\recover\world_recovered \
  --report --progress-mode Global
```

> **务必先备份**。`-t 0` 会移除所有未被访问区块；优化是不可逆裁剪，建议保留全量备份以防需要 merge 恢复。
> 首次使用建议先 `--dry-run --report` 预览将剔除的区块量。

---

## CLI 参考

### `backup` 命令

```text
java -jar backup.jar WORLD_DIR [OUTPUT_DIR] [options]
```

| 参数 | 默认 | 说明 |
|------|------|------|
| `WORLD_DIR` | — | 世界根目录（必填） |
| `OUTPUT_DIR` | — | 输出目录（可选；非原地模式必须为空，除非 `--force`） |
| `-t`, `--inhabited-time-seconds` | `300` | InhabitedTime 阈值（秒，1 秒 = 20 tick） |
| `--remove-unknown` | `false` | 将未知/外部压缩的区块视为可删除 |
| `--progress-mode` | `Region` | 进度模式：`Off` / `Global` / `Region` |
| `--in-place` | `false` | 原地处理，替换输入目录 |
| `--zip-output` | `false` | 输出目录打包为时间戳 zip 并删除目录 |
| `-f`, `--force` | `false` | 覆盖已存在且非空的输出目录（无交互） |
| `--strict` | `false` | 严格模式：出错时返回非零退出码 |
| `--report` | `false` | 标准输出打印处理统计与错误列表 |
| `--report-file` | `null` | 报告写入文件（JSON/CSV） |
| `--report-format` | `json` | 报告格式：`json` / `csv` |
| `--progress-interval` | `1000` | 进度回调的区块粒度 |
| `--progress-interval-ms` | `0` | 进度回调的时间粒度，>0 时优先 |
| `--parallelism` | `1` | 并行线程数（同时驱动维度级与区域级并行） |
| `--copy-misc` | `true` | 复制非 MCA 杂项文件（`level.dat`、`players/`、`data/` 等；`--no-copy-misc` 关闭） |
| `--dry-run` | `false` | 预览模式，只扫描统计不写入 |

### `merge` 命令

```text
java -jar backup.jar merge BASE PATCH OUTPUT [options]
```

以 chunk 槽位粒度合并（patch 优先、base 填空槽、entities/poi 锁步），恢复"全量最新"地图。

| 参数 | 默认 | 说明 |
|------|------|------|
| `BASE` / `PATCH` / `OUTPUT` | — | 全量备份 / 优化备份 / 输出目录（三目录必须互不重叠） |
| `-f`, `--force` | `false` | 覆盖已存在且非空的输出目录 |
| `--progress-mode` | `Off` | 进度模式：`Off` / `Global` / `Region` |
| `--parallelism` | `1` | 并行合并 region 的线程数（>1 时输出仍逐字节确定） |
| `--progress-interval` | `1000` | 进度回调的文件粒度 |
| `--report` | `false` | 标准输出打印合并统计 |
| `--report-file` / `--report-format` | `null` / `json` | 报告写文件（`json` / `csv`） |

### 退出码

| 命令 | 退出码 0 | 退出码 1 |
|------|----------|----------|
| `backup` | 处理完成且无错误 | `--strict` 且存在错误；或抛出 `OptimizeException` / 其他异常 |
| `merge` | 合并完成且无错误 | 存在任何错误（merge 无 `--strict`，任一错误即失败） |

> ⚠️ 实测要点：`backup` 默认**非 strict**——即使记录到错误（如输出目录已存在、损坏 region），
> 退出码仍为 0，错误只体现在报告 `errors` / `--report` 输出中。**脚本自动化请始终使用 `--strict`，
> 或自行检查报告 `errors` 数组**，否则损坏数据可能被静默跳过而脚本仍"成功"退出。

---

## 作为库使用

发布到 Maven Central（`io.github.wangzhizhou:backup-core`），支持 Kotlin DSL 与 Java。

```kotlin
dependencies {
    implementation("io.github.wangzhizhou:backup-core:<version>")
}
```

**最小示例（Kotlin DSL）**：

```kotlin
import com.jokerhub.orzmc.world.*
import java.nio.file.Paths

fun minimal() {
    val report = Optimizer.run(Paths.get("/path/to/world"), Paths.get("/path/to/out"))
    println(ReportIO.toJson(report))
}
```

**结构化配置（`OptimizerRequest`）**：

```kotlin
val request = OptimizerRequest(
    input = Paths.get("/path/to/world"),
    output = Paths.get("/path/to/out"),
    filter = FilterOptions(inhabitedThresholdSeconds = 600, removeUnknown = false, strict = false),
    outputOptions = OutputOptions(inPlace = false, zipOutput = true, force = true, copyMisc = true, dryRun = false),
    progress = ProgressOptions(interval = 500, intervalMs = 0, sink = CallbackProgressSink { e -> println(e) }),
    runtime = RuntimeOptions(parallelism = 2),
    hooks = Hooks(onError = { e -> println("Error: $e") }, reportSink = FileReportSink(Paths.get("/tmp/report.json"), "json")),
    io = IOOptions(fs = RealFileSystem, ioFactory = DefaultMcaIOFactory()),
)
val report = Optimizer.run(request)
```

**merge 复用（`WorldMerger`）**：`WorldMerger.run(MergeRequest)` 与 CLI `merge` 使用完全相同的
槽位级合并算法；`MergeReportIO.write(report, path, format)` 无损落盘 JSON/CSV。

> 完整配置模型、参数速查与全部示例见 [`docs/FEATURES.md`](docs/FEATURES.md)。Java 用法与旧版
> `OptimizerConfig` 迁移说明亦在其中。

---

## 支持的格式与 Minecraft 版本

### 区块数据压缩格式

| 压缩 | 说明 |
|------|------|
| `RAW` / `ZLIB` / `GZIP` / `LZ4` | 标准区块压缩；LZ4 使用 xxhash（seed `0x9747b28c`，掩码 `0x0FFFFFFF`）校验 |
| `External*`（外部存储） | 由 `--remove-unknown` 决定是否保留 |

### 世界格式

- **标准格式（1.2.1+）**：维度目录直接包含 `region/`、`entities/`、`poi/`
- **26.1+ 格式（PaperMC 26.1+ / Vanilla）**：维度嵌套在 `dimensions/minecraft/<dim>/`，自动递归发现
- **强制加载区块**：按优先级自动探测 `data/minecraft/chunk_tickets.dat`（新版）与 `data/chunks.dat`（旧版）
- **InhabitedTime 语义**：严格大于（`>`）比较；`-t 0` 移除从未被访问的区块

---

## 工作原理

```
CLI (picocli) → OptimizerRequest → DefaultOptimizer.run()
  → 发现维度（递归，兼容 26.1+ 的 dimensions/ 结构）
  → 统计所有 MCA 文件中的总区块数
  → 按维度逐个处理（串行或并行）：
      解析强制加载坐标（chunk_tickets.dat / chunks.dat 自动探测）
      对每个区块评估保留模式（ListPattern 强制加载 + InhabitedTimePattern 阈值）
      匹配 → 重写 region + entities + poi .mca（三文件锁步）
  → 复制杂项文件 / ZIP 打包 / 原地替换
  → 输出 OptimizeReport（JSON / CSV / 文本）
```

**merge 槽位级合并**：对 base/patch 共有的 region，逐槽位 `patch 有该槽 ? patch : base 有该槽 ? base : 空`
决定来源；entities/poi 与 region 锁步；仅 base 存在的 region 原样复制；损坏 patch 回退 base，保证不丢数据。

---

## 文档导航

| 文档 | 内容 |
|------|------|
| [`docs/FEATURES.md`](docs/FEATURES.md) | 功能点全量梳理、配置模型、参数速查（v0.2.1） |
| [`docs/threshold-benchmark-report.md`](docs/threshold-benchmark-report.md) | 真实世界 6 档阈值（0/60/120/180/240/300s）性能与效果对比、逐字节完整性验证 |
| [`docs/papermc-map-backup-recovery-case.md`](docs/papermc-map-backup-recovery-case.md) | merge 真实案例、算法与三重验收方法 |
| [`docs/real-world-backup-validation.md`](docs/real-world-backup-validation.md) | 真实世界备份验证、阈值对比、丢失文件分类 |
| [`docs/paper-26.1-world-migration-report.md`](docs/paper-26.1-world-migration-report.md) | 26.1+ 目录结构迁移分析 |
| [`docs/world-directory-structure-comparison.md`](docs/world-directory-structure-comparison.md) | 旧版/新版/原生目录结构对比 |
| [`docs/market-positioning-analysis.md`](docs/market-positioning-analysis.md) | 市场定位、竞品与产品化分析 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本历史与变更说明 |

> 🚀 产品落地页（GitHub Pages）：[orzmc.github.io/OrzMCBackup](https://orzmc.github.io/OrzMCBackup/)

---

## 测试与质量

- **CI 矩阵**：JDK 17 / 21 / 25 × Ubuntu / macOS / Windows（[`test-matrix.yml`](.github/workflows/test-matrix.yml)）
- **覆盖率门槛**：core ≥ 75%、app ≥ 50%（Kover，CI 强制，报告上传 CodeCov）
- **静态分析**：ktlint + detekt（根 [`detekt.yml`](detekt.yml)）
- **测试策略**：`MemoryFS` + `MemoryMcaIOFactory` 内存端到端 + 真实 MCA 夹具 + merge 真实夹具对
  （`Fixtures/merge/`，可用 `python tools/gen_merge_fixtures.py` 重新生成）

```bash
./gradlew :core:test :app:test --no-daemon   # 全部测试
./gradlew ktlintCheck detekt --no-daemon     # 代码风格与静态分析
```

---

## 构建与发布

- **环境**：Gradle Wrapper `9.7.0`；JDK **17-29**（构建脚本阻止 30+）；产物目标 Java 17
- **版本管理**：根 `build.gradle.kts` + `gradle/libs.versions.toml` 统一注入；`-Pversion=X.Y.Z` 覆盖
- **本地构建 CLI**：`./gradlew :app:shadowJar --no-daemon`
- **库发布**：`./gradlew :core:portalBundle -Pversion=X.Y.Z -Psigning.keyId=... -Psigning.password=... -Psigning.key=...`
  生成 `core/build/portal-bundle.zip`（含 GPG 签名 + sources + javadoc），上传 Maven Central Publisher Portal
- **GitHub Actions**：`release-app.yml` 发布 CLI 到 GitHub Release；`release-lib.yml` 发布库到 Maven Central；
  两者使用 **Temurin 21** 构建（lint/coverage 用 JDK 25）

---

## Agent 协作开发

仓库支持主流 AI Agent 工具（Claude Code、Codex、pi.dev、Grok、Gemini CLI、Cursor 等）开箱协作：

- **[`AGENTS.md`](AGENTS.md)** — 跨工具单一事实源：项目结构、常用命令、设计决策、协作规则、文档同步铁律
- **[`CLAUDE.md`](CLAUDE.md)** / **[`GEMINI.md`](GEMINI.md)** — 各家薄壳入口（引用 AGENTS.md）
- **`.agents/skills/`** — 可移植技能（Agent Skills Standard）：`build-cli`、`test`、`lint-fix`、`update-docs`

---

## 贡献 / 安全 / 许可

- 参与开发：[`CONTRIBUTING.md`](CONTRIBUTING.md)
- 安全问题：[`SECURITY.md`](SECURITY.md)
- 许可：[Apache-2.0](LICENSE)。感谢社区与原实现（[Aternos/Thanos](https://github.com/aternosorg/thanos)）
  的启发与样例支持
