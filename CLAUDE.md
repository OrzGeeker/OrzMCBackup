# CLAUDE.md

此文件指导 Claude Code (claude.ai/code) 在此仓库中操作。

## 常用命令

```bash
# 构建 CLI fat JAR（产物位于 app/build/libs/backup-<version>.jar）
./gradlew :app:shadowJar --no-daemon

# 运行代码风格和静态分析检查
./gradlew ktlintCheck detekt --no-daemon

# 运行所有测试
./gradlew :core:test :app:test --no-daemon

# 运行单个测试类
./gradlew :core:test --tests "com.jokerhub.orzmc.MemoryE2ETest" --no-daemon

# 运行单个测试方法
./gradlew :core:test --tests "com.jokerhub.orzmc.MemoryE2ETest.end-to-end optimize with MemoryFS and MemoryMcaIOFactory" --no-daemon

# 生成覆盖率报告
./gradlew :core:koverXmlReport --no-daemon

# 发布库到本地 Maven
./gradlew :core:publishToMavenLocal --no-daemon

# 生成 Maven Central 上传 bundle
./gradlew :core:portalBundle --no-daemon -Pversion=X.Y.Z \
  -Psigning.keyId=<KEY_ID> -Psigning.password=<PASSWORD> -Psigning.key=<KEY_BASE64>

# 打印测试资源路径（调试 CI 夹具问题）
./gradlew :core:printTestPaths --no-daemon
```

**JDK 要求：** Java 17+（构建脚本明确阻止 JDK 30+）。CI 使用 Temurin 25（lint/coverage），本地开发可用任意 JDK 17-29。

## 项目概览

Kotlin/Gradle 多模块工程，用于优化 Minecraft Java 世界。扫描 region/entities/poi 的 MCA 文件，根据 InhabitedTime 阈值或强制加载列表移除区块，并重写输出。双用途：CLI 工具（基于 picocli 的 shadow JAR）+ 库（发布到 Maven Central `io.github.wangzhizhou:backup-core`）。

## 架构

两个模块：`core`（库）和 `app`（CLI 入口）。所有版本/组信息由根 `build.gradle.kts` 统一注入。

### 数据流

```
CLI (picocli) → OptimizerRequest → DefaultOptimizer.run()
  → 发现维度（包含 region/ 目录的子目录，递归支持 26.1+ 的 dimensions/ 结构）
  → 统计所有 MCA 文件中的总区块数
  → 按维度逐个处理（支持串行或线程池并行）：
      解析 data/minecraft/chunk_tickets.dat 或 data/chunks.dat 获取强制加载坐标（自动探测）
      对每个入口评估 ChunkPattern（ListPattern + InhabitedTimePattern 等）
      匹配 → 重写 region + entities + POI .mca
  → [可选] 复制杂项文件、ZIP 打包输出、或原地替换
  → 输出 OptimizeReport
```

### 包结构

- **`world/`** — 优化管道引擎
  - `Optimizer` / `DefaultOptimizer` — 管道编排器
  - `OptimizerEngine` — 接口，支持注入/模拟
  - `DimensionProcessor` — 按维度循环处理 region .mca 文件
  - `OptimizerConfig.kt` — 所有配置数据类（`OptimizerRequest`、`FilterOptions`、`OutputOptions`、`ProgressOptions`、`RuntimeOptions`、`Hooks`、`IOOptions`）+ Builder 模式
  - `FileSystem` — 抽象，提供 `RealFileSystem`（java.nio）和 `MemoryFS`（基于 ConcurrentHashMap，用于测试）
  - `McaIOFactory` — MCA 读写器工厂，提供 `DefaultMcaIOFactory`（真实文件）和 `MemoryMcaIOFactory`（内存）
  - `ForceLoad` / `NbtForceLoader` — 强制加载区块解析器，按优先级探测 `data/minecraft/chunk_tickets.dat`（26.1+）→ `data/chunks.dat`（旧版）；GZip NBT 解析，支持旧版 `Forced` LongArray 和新版 `tickets[].chunk_pos`
  - `Compressor` — ZIP 压缩输出
  - `Cleaner` — 文件清理，含 Windows DOS 属性处理
  - `ProgressSink` / `ProgressEvent` — 进度报告管道路
  - `ReportSink` / `ReportIO` / `OptimizeReport` — 报告输出（JSON/CSV/纯文本）
  - `MetricsSink` — 指标收集接口
  - `LoggerSink` — 日志抽象（CLI 使用）
  - `Errors.kt` — 异常层级

- **`mca/`** — Minecraft Anvil 区域文件格式
  - `McaReader` — 解析 8 KiB 头部（位置表 + 时间戳表），1024 个扇区槽位
  - `McaWriter` — 写入头部及扇区对齐的区块数据
  - `McaEntry` — 单个区块入口，含解压逻辑（RAW/ZLIB/GZIP/LZ4 + xxhash 校验）
  - `RandomAccess` — 抽象，提供 `RafAccess`、`BufferedRafAccess`（8K 缓冲）、`MemoryAccess`

- **`patterns/`** — 实现 `ChunkPattern` 接口的区块保留策略
  - `InhabitedTimePattern` — 字节级扫描 `InhabitedTime` NBT long 字段（无需完整 NBT 解析）；使用 `>` 语义，`threshold=0` 时移除未曾访问的区块
  - `ListPattern` — 基于坐标的保留列表（强制加载区块）
  - `RangePattern` — 矩形区域保留（自动坐标归一化）

- **`app`（CLI）** — 基于 picocli 的单一 `Main` 类，将所有 CLI 选项映射到 `OptimizerRequest`

### 关键设计决策

1. **通过抽象实现可测试性：** `FileSystem` + `McaIOFactory` 使得整个管道可在测试中基于 `MemoryFS` 运行，无需触碰磁盘。`McaMemoryBuilder`（位于 testFixtures）在内存中构建合成 MCA 文件。
2. **错误容错：** 非致命错误收集在 `OptimizeReport.errors` 中。`strict` 模式将错误提升为退出码 1。
3. **进度报告：** 两种限流模式——按区块数（`progressInterval`）或按墙上时钟时间（`progressIntervalMs`）。
4. **可扩展性：** `parallelism` 控制维度级线程池。`DimensionProcessor` 也支持区域级并行。
5. **快速 InhabitedTime 检查：** 使用字节级扫描查找 NBT 标签，而非完整的 NBT 反序列化。
6. **惰性 MCA 写入：** 仅当至少有一个区块被保留时才创建输出 writer，避免写入空 MCA 文件。
7. **26.1+ 世界格式兼容：** ForceLoad 按优先级探测 `chunk_tickets.dat` → `chunks.dat`；维度发现递归支持 `dimensions/` 嵌套结构。

### 测试

- 端到端测试使用 `MemoryFS` + `MemoryMcaIOFactory`，快速且无需磁盘
- `McaMemoryBuilder`（testFixtures）通过编程方式构建合成 MCA 数据
- 真实 MCA 夹具文件位于 `core/src/test/resources/Fixtures/`
- `TestPaths` 工具定位基于磁盘测试的夹具目录
- CI 矩阵：Java 17/21/25 × Ubuntu/macOS/Windows
- Lint/Coverage：JDK 25
- 通过 Kover 生成覆盖率报告（`.github/workflows/test-matrix.yml`）
- detekt 2.0.0-alpha.3 + ktlint 14.2.0 进行代码质量和风格检查

### CI/CD 工作流

- **test-matrix.yml** — push/PR：3 个 JDK × 3 个 OS 测试 + Kover 覆盖率报告
- **release-lib.yml** — 标签推送 `vX.Y.Z` 或手动触发：通过 Maven Central Portal 发布库
- **release-app.yml** — 标签推送或手动触发：发布 CLI shadow JAR
