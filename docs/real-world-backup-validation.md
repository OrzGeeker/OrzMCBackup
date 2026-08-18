# 真实 PaperMC 世界备份验证报告

> 记录时间：2026-08-17
>
> 验证对象：`E:\test\world`（真实 PaperMC 26.1+ 世界，~14 GB / 29,046 文件 / 21,692 `.mca`）
>
> 备份工具：OrzBackup（`app/build/libs/backup.jar`，含缺陷 A/B 修复及两轮代码审查后的最终代码）
>
> 分析工作区：`E:\test\_backup_analysis\`（snapshot.ps1 / compare.ps1 / 各 CSV 清单）

---

## 1. 验证目标

1. 用 OrzBackup 对真实 PaperMC 世界做备份，量化前后磁盘占用对比。
2. 逐文件判定备份后丢失的文件：哪些符合预期（会话锁、被优化剔除的区块数据），哪些不符合预期。
3. 用真实世界对照测试夹具，补齐夹具与回归测试，保障备份功能质量。
4. 以 1/2/3/4/5 分钟为 InhabitedTime 阈值各跑一次备份，对比不同阈值下的优化效果。

## 2. 输入世界概览（备份前快照）

| 项目 | 数值 |
|------|------|
| 总文件数 | 29,046 |
| 总大小 | 14,318.10 MB（约 14.0 GB） |
| `.mca` 文件 | 21,692 |
| 根目录零字节 `level<数字>.dat` | 232 |
| 每维度 region/entities/poi | overworld 6382/5124/4033、the_end 3851/1181/215、the_nether 482/364/60 |
| region 内非 `.mca` 备份文件 | `r.0.-4.mca.2060040953768669224.backup`(8.7MB)、`r.0.2.mca.bak`(8.3MB) |

来源：`E:\test\_backup_analysis\before_manifest.csv`。

## 3. 发现并修复的两个缺陷

### 缺陷 A：`--copy-misc` 命令行开关反了（picocli 4.7.7）

`app/src/main/kotlin/com/jokerhub/orzmc/cli/Main.kt` 中 `--copy-misc` 被声明为 `negatable = true` 的可取反布尔选项。实测发现 **裸写 `--copy-misc` 会被解析成 `false`**（即"不复制杂项文件"），而 `--no-copy-misc` 反而生效为 true —— 整个开关完全反转。

- 后果：用户按文档写法 `java -jar backup.jar <world> <out> --copy-misc` 执行时，**杂项文件全部不会备份**。
- 修复：为该选项增加 `fallbackValue = "true"`，使裸写 `--copy-misc` 解析为 true。
- 回归测试：`MainCliCopyMiscTest.bare copy-misc flag enables misc copying`。
- 证据：基线（旧 jar + `--copy-misc`）输出中**只有 `.mca`，全部 7,355 个杂项文件丢失**；修复后杂项全部保留。

### 缺陷 B：region/entities/poi 内非 `.mca` 文件被静默丢弃

`core/src/main/kotlin/com/jokerhub/orzmc/world/Optimizer.kt` 的 `copyMiscFiles` / `countMiscFiles` 原本用 `reserved = {region, entities, poi}` **整棵跳过**这三个子树。真实世界 overworld/region/ 下的两个 region 备份文件（`.backup` 8.7MB、`.bak` 8.3MB）会被静默丢弃 —— 备份工具不应丢失真实数据。

- 修复：改为只跳过会被维度处理器重写的**顶层 `.mca`**，region/entities/poi 内的 `.bak`/`.backup` 等非 `.mca` 文件照常复制。
- 回归测试：`RealWorldPatternTest.non mca files inside region entities poi are preserved`。
- 磁盘夹具：`Fixtures/world-26-1/.../region/r.0.0.mca.bak` + `FixtureCompatibilityTest` 新增断言。

## 4. 两轮代码审查（审查问题已修复）

针对本次改动做两轮代码审查，发现问题并修复后全部通过 `:core:test :app:test ktlintCheck detekt`。

### 第 1 轮：`countMiscFiles` / `copyMiscFiles` 逻辑去重与修正

两函数各自维护一份 walk 跳过逻辑，导致：
1. **session.lock 计数不对称**：`countMiscFiles` 数了 `session.lock`，`copyMiscFiles` 却跳过它 → 进度总数虚高。
2. **`.mca` 跳过过宽**：`endsWith(".mca")` 会把 `region/` 下**任意深度**的 `.mca` 都跳过，而维度处理器只重写 `region/entities/poi` 下**顶层** `.mca`（`fs.list(regionDir)` 不递归）；`region/sub/x.mca` 这类嵌套文件会被误丢弃。
3. **reserved 目录节点处理不一致**：修复后目录节点也被计数/创建，需要保证 count 与 copy 行为一致。
4. **逻辑重复**：两处重复 = 分叉风险。

**修复**：抽取出共享辅助函数 `miscRel(dir, p, excludePaths, reserved, matchers): Path?`（`null`=跳过，否则返回相对路径）与 `skipMatchers()`，`countMiscFiles` 与 `copyMiscFiles` 共用。`miscRel` 只跳过 `session.lock`（glob 匹配裸文件名）与 `region/entities/poi` 下 `rel.nameCount == 2` 的顶层 `.mca`。

### 第 2 轮：死参数与假设核实

1. **`miscRel` 未使用的 `fs` 参数**：签名带 `fs` 但函数体未用 → 移除。
2. **`copyMiscFiles` 未使用的 `miscTotal` 参数**：函数体内只用 `ctx.progressTotal` 发进度，`miscTotal` 是死参数 → 从签名与调用点移除。
3. **核实 `nameCount == 2` 假设**：`DimensionProcessor.process` 用 `fs.list(regionDir).filter { endsWith(".mca") }` 发现 region 文件、用 `entitiesDir.resolve(name)`/`poiDir.resolve(name)` 关联 entities/poi —— 均只处理顶层文件，与 `miscRel` 的跳过规则一致。

## 5. 备份结果（默认阈值 300s）

```bash
java -jar backup.jar E:\test\world E:\test\world_backup --copy-misc --parallelism 4 \
  --report --report-file report_afterfix.json --report-format json
```

| 项目 | 备份前 | 备份后 |
|------|--------|--------|
| 文件数 | 29,046 | 10,770 |
| 总大小 | 14,318.10 MB | 2,092.01 MB |
| 压缩率 | — | **-85.4%（节省 12,226 MB）** |

### 按维度字节对比（MB）

| 维度 | 备份前 | 备份后 | 节省 |
|------|--------|--------|------|
| overworld | 7,812.89 | 1,766.52 | 6,046.37 |
| the_nether | 351.51 | 235.74 | 115.77 |
| the_end | 6,118.01 | 54.06 | 6,063.95 |

优化报告：`processedChunks=2,699,339`、`removedChunks=2,466,318`（剔除约 91.4% 区块）、`errors=[]`。

## 6. 丢失文件分类（默认阈值 300s）

| 分类 | 文件数 | 大小 | 说明 |
|------|--------|------|------|
| preserved | 10,770 | 2,092.01 MB | 杂项文件原样复制 + 重写后的 `.mca` |
| dropped-expected | 18,276 | 12,226.09 MB | 18,275 个被剔除区块的 `.mca` + 1 个 `session.lock` |
| **dropped-UNEXPECTED** | **0** | **0** | 修复后为空 |

- **符合预期**：`session.lock`（glob 跳过）；被剔除区块的 `.mca`。
- **不符合预期**：**0 个**。基线版本曾丢失的 `r.0.-4.mca.<长数字>.backup`(8.7MB)、`r.0.2.mca.bak`(8.3MB)、根级/维度级 `death-chests.yml`、232 个零字节 `level<数字>.dat` 在修复后全部 `preserved`。

## 7. 阈值对比（1/2/3/4/5 分钟）

对同一真实世界 `E:\test\world` 以 1/2/3/4/5 分钟（InhabitedTime 60/120/180/240/300 秒）为阈值各跑一次完整备份（其余参数一致：`--copy-misc --parallelism 4`）。输入共 29,046 文件 / 14,318.1 MB / 2,699,339 区块。

| 阈值 | 输出文件数 | 输出大小 | 保留区块 | 剔除区块 | 剔除率 | 节省 MB | 压缩率 |
|------|-----------|----------|---------|---------|--------|---------|--------|
| 1 分钟 (60s) | 11,679 | 2,211.2 MB | 247,474 | 2,451,865 | 90.8% | 12,106.9 | 84.6% |
| 2 分钟 (120s) | 11,173 | 2,129.7 MB | 237,426 | 2,461,913 | 91.2% | 12,188.4 | 85.1% |
| 3 分钟 (180s) | 10,950 | 2,105.5 MB | 234,572 | 2,464,767 | 91.3% | 12,212.6 | 85.3% |
| 4 分钟 (240s) | 10,842 | 2,095.3 MB | 233,443 | 2,465,896 | 91.4% | 12,222.8 | 85.4% |
| 5 分钟 (300s) | 10,770 | 2,092.0 MB | 233,021 | 2,466,318 | 91.4% | 12,226.1 | 85.4% |

### 结论

1. **阈值越高（越激进），剔除区块越多**：保留区块从 247,474（60s）单调下降到 233,021（300s），但 5 档间差异很小（<6%）。
2. **体积差异有限**：输出从 2,211.2 MB（60s）到 2,092.0 MB（300s），**极端之间仅差 ~119 MB（5.4%）**。对本世界而言，1 分钟阈值已能拿到绝大部分压缩收益（84.6%），继续提高阈值边际收益很小。
3. **五种阈值下 dropped-UNEXPECTED 均为 0**：无论阈值高低，都不存在非预期数据丢失；杂项文件（含 `region/` 内 `.bak`/`.backup`、`death-chests.yml`、零字节 `level*.dat`）全部保留，仅丢失 `session.lock` 与被剔除区块数据。
4. **幸存 `.mca` 与输入逐字节一致**（`cmp` 验证）：本世界中各 region 的区块 InhabitedTime 分布呈"整区一致"特征——某 region 要么全部区块高于阈值（原样保留，输出与输入字节相同），要么全部低于（整区消失）。因此没有任何"部分重写"导致的 `.mca`，`rewritten=0`。

## 8. 测试与夹具补充

1. **`RealWorldPatternTest`**（新增，MemoryFS）：region 内 `.bak`/`.backup` 保留、零字节 `level*.dat` 保留、根级与维度级 `death-chests.yml` 保留、全剔除 region 文件消失（惰性写入器）。
2. **`Fixtures/world-26-1`** 磁盘夹具扩展：`region/r.0.0.mca.bak`、根级 `level12345678901234567890.dat`（0 字节）、根级与每维度 `death-chests.yml`。
3. **`FixtureCompatibilityTest`** 新增：上述 4 类文件在输出中存在性断言。
4. **`MainCliCopyMiscTest`** 新增：裸 `--copy-misc` 正确启用杂项复制（缺陷 A 回归）。

校验命令：
```bash
./gradlew :core:test :app:test ktlintCheck detekt --no-daemon
```
（全部通过）

## 9. 结论

- OrzBackup 对真实 14GB PaperMC 世界备份可将体积降至 ~2.1GB（-85.4%），丢失文件全部符合预期（会话锁 + 被剔除区块），**无任何非预期数据丢失**。
- 两个真实缺陷（picocli 开关反转、region/entities/poi 非 `.mca` 文件被丢弃）均已修复并有回归测试。
- 真实世界特征已沉淀为夹具与测试，防止回归。
