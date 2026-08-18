---
name: test
description: 运行 OrzMCBackup 的测试：全量、模块级、单个测试类或单个测试方法。当需要验证改动、定位回归、或写完代码后跑测试时使用。
---

# 运行测试

## 全量测试

```bash
./gradlew :core:test :app:test --no-daemon
```

## 单模块

```bash
./gradlew :core:test --no-daemon    # 库模块
./gradlew :app:test --no-daemon     # CLI 模块
```

## 单个测试类

```bash
./gradlew :core:test --tests "com.jokerhub.orzmc.MemoryE2ETest" --no-daemon
./gradlew :core:test --tests "com.jokerhub.orzmc.WorldMergerTest" --no-daemon
./gradlew :app:test --tests "com.jokerhub.orzmc.cli.MainCliMergeTest" --no-daemon
```

## 单个测试方法

```bash
./gradlew :core:test --tests "com.jokerhub.orzmc.MemoryE2ETest.end-to-end optimize with MemoryFS and MemoryMcaIOFactory" --no-daemon
```

## 关键测试类索引

- `Paper26StructureTest` — 26.1+ 世界目录结构（18 个测试）
- `FixtureCompatibilityTest` — 真实 MCA 夹具新旧格式兼容
- `WorldMergerTest` / `RealMcaMergeTest` / `MergeReportIOTest` — merge 合并
- `MainCliMergeTest` / `MainDispatchTest` / `MainCliCopyMiscTest` — CLI 端到端
- `MemoryE2ETest` / `MemoryParallelE2ETest` — 内存端到端

## 测试基建

- 端到端测试用 `MemoryFS` + `MemoryMcaIOFactory`（无需磁盘）；真实夹具在
  `core/src/test/resources/Fixtures/`
- 覆盖门槛：`./gradlew :core:koverVerify :app:koverVerify --no-daemon`（core ≥75%、app ≥50%）
