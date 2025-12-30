# backup

Kotlin/Gradle 独立工程，提供与原 Rust 项目相同的 Minecraft Java 世界优化功能：扫描各维度的 region/entities/poi MCA 文件，根据 InhabitedTime 阈值与强制加载列表（chunks.dat）保留区块并重写输出，同时支持进度与可选压缩输出。

## 快速开始
- 构建可执行 JAR：
  - 在 backup 目录执行：

    ```bash
    ./gradlew :app:shadowJar
    ```

  - 产物位置：backup/app/build/libs/backup-0.1.0.jar
- 运行示例：

  ```bash
  # 指定输入与输出目录
  java -jar backup/app/build/libs/backup-0.1.0.jar /path/to/world /path/to/out -t 600 --zip-output

  # 原地处理（覆盖输入目录）
  java -jar backup/app/build/libs/backup-0.1.0.jar /path/to/world --in-place --progress-mode global
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

## 作为库使用
- 发布到本地 Maven 仓库：

  ```bash
  ./gradlew :core:publishToMavenLocal
  ```

- 其他工程依赖：

  ```kotlin
  dependencies {
      implementation("com.jokerhub.orzmc:backup-core:0.1.0")
  }
  ```

- 核心 API 调用示例：

  ```kotlin
  import com.jokerhub.orzmc.world.Optimizer
  import com.jokerhub.orzmc.world.ProgressMode
  import java.nio.file.Path

  fun optimize() {
      val input = Path.of("/path/to/world")
      val output = Path.of("/path/to/out")
      Optimizer.run(
          input = input,
          output = output,           // 原地处理时传 null
          inhabitedThresholdSeconds = 600,
          removeUnknown = false,
          progressMode = ProgressMode.Global,
      )
  }
  ```

## 测试
- 运行测试：

  ```bash
  ./gradlew :core:test
  ```

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
backup/
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

## 兼容性与构建
- Gradle Wrapper 固定版本：8.7（与 Shadow 插件及 Kotlin 1.9.22 兼容）
- JDK：默认使用当前环境的 JDK（无需强制 toolchain），已在 macOS aarch64 上验证

## 许可与致谢
- 本工程复用原项目的测试数据与行为定义，感谢原项目的实现思路与样例支持
