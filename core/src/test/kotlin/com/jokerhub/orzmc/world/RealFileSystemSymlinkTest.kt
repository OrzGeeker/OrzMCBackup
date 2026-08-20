package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.util.TestTmp
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RealFileSystem.walk 符号链接跟随验证。
 *
 * 背景（2026-08-20 真实环境验收发现）：Folia 测试服 world 目录是符号链接
 * （world -> 外部真实目录，共享地图省磁盘）。修复前 `Files.walk` 不跟随链接，
 * 链接目标内的 dimensions/<dim>/region 全部不可见 → 备份 0 chunk 空跑假完成
 * （zip 22 字节，进度 2/2 无任何 chunk）。修复后 walk 带 FOLLOW_LINKS。
 */
class RealFileSystemSymlinkTest {

    @Test
    fun `walk follows symlinked directory into target`() {
        val root = TestTmp.createTempDirectory("symlink-walk-")
        try {
            // 真实目标目录（模拟共享世界）：
            //   target/world/dimensions/minecraft:overworld/region/r.0.0.mca
            val realWorld = Files.createDirectories(root.resolve("target").resolve("world"))
            val dimDir = Files.createDirectories(
                realWorld.resolve("dimensions").resolve("minecraft:overworld").resolve("region"),
            )
            val mca = dimDir.resolve("r.0.0.mca")
            Files.write(mca, ByteArray(1024))

            // worldContainer 模拟：container/world -> target/world（符号链接）
            val container = Files.createDirectories(root.resolve("container"))
            val link = container.resolve("world")
            try {
                Files.createSymbolicLink(link, realWorld)
            } catch (e: UnsupportedOperationException) {
                // 平台不支持符号链接 → 跳过
                return
            } catch (e: java.io.IOException) {
                throw AssertionError("创建符号链接失败: $e")
            }

            val walked = RealFileSystem.walk(container)
            val regionDir = link.resolve("dimensions").resolve("minecraft:overworld").resolve("region")
            assertTrue(
                walked.any { it == regionDir },
                "walk 应包含经符号链接可见的维度 region 目录（修复前不可见），实际: " +
                    walked.filter { it.toString().contains("region") },
            )
            assertTrue(
                walked.any { p -> p.fileName.toString() == "r.0.0.mca" },
                "walk 应包含链接目标内的 region 文件（修复前不可见）",
            )
        } finally {
            RealFileSystem.deleteTreeWithRetry(root, 3, 50L)
        }
    }

    @Test
    fun `walk includes regular descendants without symlinks`() {
        val root = TestTmp.createTempDirectory("walk-plain-")
        try {
            val nested = Files.createDirectories(root.resolve("a").resolve("b"))
            Files.write(nested.resolve("x.txt"), byteArrayOf(1, 2, 3))
            val walked = RealFileSystem.walk(root)
            assertTrue(walked.any { it.fileName.toString() == "x.txt" }, "普通目录 walk 应含深层文件")
        } finally {
            RealFileSystem.deleteTreeWithRetry(root, 3, 50L)
        }
    }
}
