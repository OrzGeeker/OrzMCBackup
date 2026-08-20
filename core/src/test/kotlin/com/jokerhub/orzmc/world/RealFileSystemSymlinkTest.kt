package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.util.TestTmp
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * RealFileSystem.walk 符号链接跟随验证。
 *
 * 背景（2026-08-20 真实环境验收发现）：Folia 测试服 world 目录是符号链接
 * （world -> 外部真实目录，共享地图省磁盘）。修复前 `Files.walk` 不跟随链接，
 * 链接目标内的 dimensions/<dim>/region 全部不可见 → 备份 0 chunk 空跑假完成
 * （zip 22 字节，进度 2/2 无任何 chunk）。
 *
 * 修复后 walk 带 followLinks 参数：维度发现/计数/复制显式传 true 进入链接目标；
 * 删除路径默认不跟随，防止 deleteIfExists 穿链接删掉目标目录内的文件。
 */
class RealFileSystemSymlinkTest {
    /** 尝试创建符号链接；平台不支持（含 Windows 无管理员/开发者模式抛 IOException）时返回 true。 */
    private fun linkUnsupported(
        link: Path,
        target: Path,
    ): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            false
        } catch (_: UnsupportedOperationException) {
            true
        } catch (_: java.io.IOException) {
            true
        }

    @Test
    fun `walk follows symlinked directory into target when followLinks is true`() {
        val root = TestTmp.createTempDirectory("symlink-walk-")
        try {
            // 真实目标目录（模拟共享世界）：
            //   target/world/dimensions/minecraft/overworld/region/r.0.0.mca
            val realWorld = Files.createDirectories(root.resolve("target").resolve("world"))
            val dimDir =
                Files.createDirectories(
                    realWorld
                        .resolve("dimensions")
                        .resolve("minecraft")
                        .resolve("overworld")
                        .resolve("region"),
                )
            val mca = dimDir.resolve("r.0.0.mca")
            Files.write(mca, ByteArray(1024))

            // worldContainer 模拟：container/world -> target/world（符号链接）
            val container = Files.createDirectories(root.resolve("container"))
            val link = container.resolve("world")
            if (linkUnsupported(link, realWorld)) return

            val walked = RealFileSystem.walk(container, followLinks = true)
            val regionDir =
                link
                    .resolve("dimensions")
                    .resolve("minecraft")
                    .resolve("overworld")
                    .resolve("region")
            assertTrue(
                walked.any { it == regionDir },
                "walk(followLinks=true) 应包含经符号链接可见的维度 region 目录（修复前不可见）",
            )
            assertTrue(
                walked.any { p -> p.fileName.toString() == "r.0.0.mca" },
                "walk(followLinks=true) 应包含链接目标内的 region 文件（修复前不可见）",
            )
        } finally {
            RealFileSystem.deleteTreeWithRetry(root, 3, 50L)
        }
    }

    @Test
    fun `walk without followLinks does not enter symlink target`() {
        val root = TestTmp.createTempDirectory("symlink-nofollow-")
        try {
            val realWorld = Files.createDirectories(root.resolve("target").resolve("world"))
            val dimDir =
                Files.createDirectories(
                    realWorld
                        .resolve("dimensions")
                        .resolve("minecraft")
                        .resolve("overworld")
                        .resolve("region"),
                )
            val mca = dimDir.resolve("r.0.0.mca")
            Files.write(mca, ByteArray(1024))

            val container = Files.createDirectories(root.resolve("container"))
            val link = container.resolve("world")
            if (linkUnsupported(link, realWorld)) return

            // 删除路径 = walk(default) + sortedByDescending + deleteIfExists：必须不穿链接，
            // 否则会删掉链接目标内的文件（源世界）。
            val walked = RealFileSystem.walk(container)
            assertTrue(
                walked.any { it == link },
                "walk 默认应列出链接本身",
            )
            assertTrue(
                walked.none { p -> p.fileName.toString() == "r.0.0.mca" },
                "walk 默认不应进入链接目标（删除路径安全性）",
            )
        } finally {
            RealFileSystem.deleteTreeWithRetry(root, 3, 50L)
        }
    }

    @Test
    fun `walk with followLinks skips symlink loops instead of throwing`() {
        val root = TestTmp.createTempDirectory("symlink-loop-")
        try {
            val container = Files.createDirectories(root.resolve("container"))
            val a = Files.createDirectories(container.resolve("a"))
            val b = Files.createDirectories(container.resolve("b"))
            Files.write(container.resolve("keep.txt"), byteArrayOf(1, 2, 3))
            // 环路 a/loop-b → b/loop-a → a/...：必须跳过该子树而不抛 FileSystemLoopException
            if (linkUnsupported(a.resolve("loop-b"), b)) return
            if (linkUnsupported(b.resolve("loop-a"), a)) return

            val walked = RealFileSystem.walk(container, followLinks = true)
            assertTrue(
                walked.any { p -> p.fileName.toString() == "keep.txt" },
                "有环路时 walk(followLinks=true) 不应中断，其余文件照常列出",
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
