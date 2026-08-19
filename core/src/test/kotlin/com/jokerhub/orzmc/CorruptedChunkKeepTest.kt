package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.FilterOptions
import com.jokerhub.orzmc.world.IOOptions
import com.jokerhub.orzmc.world.MemoryFS
import com.jokerhub.orzmc.world.MemoryMcaIOFactory
import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.OptimizerRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Paths

/**
 * 损坏 chunk 容错（BUG-E2E-002 修复验证）：
 * pattern 匹配遇到无法解析的 chunk（unknown compression 等）→ 安全保留原始字节，
 * 而非移除（可能含玩家数据）；错误仅记录，不中断流程。
 */
class CorruptedChunkKeepTest {
    private fun corruptCompressionByte(
        mca: ByteArray,
        index: Int,
    ): ByteArray {
        val out = mca.copyOf()
        val locVal = ByteBuffer.wrap(out, index * 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val off = (locVal ushr 8) * 4096
        out[off + 4] = 49 // 非法 compression（unknown compression: 49，真实世界损坏样本之一）
        return out
    }

    private fun entryLocation(
        mca: ByteArray,
        index: Int,
    ): Int {
        val locVal = ByteBuffer.wrap(mca, index * 4, 4).order(ByteOrder.BIG_ENDIAN).int
        return locVal
    }

    @Test
    fun `corrupted chunk is kept with original bytes and error recorded`() {
        val fs = MemoryFS()
        val world = Paths.get("/mem/corrupt-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        // idx0: InhabitedTime=0（阈值 0 下移除）；idx1: 损坏（应保留）
        val mca =
            McaMemoryBuilder.buildMca(
                listOf(
                    McaMemoryBuilder.MemChunk(0, 0, CompressionKind.RAW),
                    McaMemoryBuilder.MemChunk(1, 1000, CompressionKind.RAW),
                ),
            )
        val corrupted = corruptCompressionByte(mca, 1)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), corrupted)

        val out = Paths.get("/mem/corrupt-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)

        // 流程完整：两个 chunk 都被处理
        assertEquals(2, report.processedChunks)
        // 损坏 chunk 的错误被记录（不中断）
        assertTrue(
            report.errors.any { it.kind == "Pattern" && it.message.contains("unknown compression") },
            "损坏 chunk 应记录 Pattern 错误，实际: ${report.errors}",
        )

        // 输出 MCA：损坏 chunk（idx1）保留，正常但阈值外（idx0）被移除
        val outFile = fs.toRealPath(out.resolve("region").resolve("r.0.0.mca"))
        assertTrue(
            java.nio.file.Files
                .exists(outFile),
            "输出 MCA 应存在",
        )
        val outBytes =
            java.nio.file.Files
                .readAllBytes(outFile)
        assertEquals(0, entryLocation(outBytes, 0), "idx0（阈值外）应被移除")
        assertTrue(entryLocation(outBytes, 1) != 0, "损坏 chunk（idx1）应安全保留")
    }

    @Test
    fun `corrupted chunk with strict mode still does not interrupt pipeline`() {
        val fs = MemoryFS()
        val world = Paths.get("/mem/strict-corrupt-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val mca =
            McaMemoryBuilder.buildMca(
                listOf(
                    McaMemoryBuilder.MemChunk(0, 1000, CompressionKind.ZLIB),
                    McaMemoryBuilder.MemChunk(1, 1000, CompressionKind.ZLIB),
                ),
            )
        val corrupted = corruptCompressionByte(mca, 1)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), corrupted)

        val out = Paths.get("/mem/strict-corrupt-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0, strict = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)

        // strict 模式下 chunk 级解析错误仍不中断（仅记录）；正常 chunk 照常处理
        assertEquals(2, report.processedChunks)
        assertTrue(report.errors.any { it.kind == "Pattern" })
        val outFile = fs.toRealPath(out.resolve("region").resolve("r.0.0.mca"))
        assertTrue(
            java.nio.file.Files
                .exists(outFile),
        )
        val outBytes =
            java.nio.file.Files
                .readAllBytes(outFile)
        assertTrue(entryLocation(outBytes, 0) != 0, "正常 chunk 应保留")
        assertTrue(entryLocation(outBytes, 1) != 0, "损坏 chunk 应保留（安全策略）")
    }

    @Test
    fun `corrupted chunk with absurd length field is skipped without stalling`() {
        val fs = MemoryFS()
        val world = Paths.get("/mem/absurd-len-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val mca =
            McaMemoryBuilder.buildMca(
                listOf(
                    McaMemoryBuilder.MemChunk(0, 1000, CompressionKind.RAW),
                    McaMemoryBuilder.MemChunk(1, 1000, CompressionKind.RAW),
                ),
            )
        // 把 idx1 的长度字段改成荒谬值（≈180MB，真实损坏样本），compression 改非法
        val data = corruptCompressionByte(mca, 1)
        val locVal = ByteBuffer.wrap(data, 1 * 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val off = (locVal ushr 8) * 4096
        data[off] = 0x0a.toByte()
        data[off + 1] = 0xc9.toByte()
        data[off + 2] = 0xfb.toByte()
        data[off + 3] = 0xd1.toByte()
        fs.write(world.resolve("region").resolve("r.0.0.mca"), data)

        val out = Paths.get("/mem/absurd-len-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)

        // 流程快速完成不卡死；正常 chunk 保留；荒谬长度 chunk 被记录并跳过
        assertEquals(2, report.processedChunks)
        assertTrue(
            report.errors.any { it.kind == "Pattern" || it.kind == "Write" },
            "荒谬长度 chunk 应记录错误，实际: ${report.errors}",
        )
        val outFile = fs.toRealPath(out.resolve("region").resolve("r.0.0.mca"))
        assertTrue(
            java.nio.file.Files
                .exists(outFile),
        )
        val outBytes =
            java.nio.file.Files
                .readAllBytes(outFile)
        assertTrue(entryLocation(outBytes, 0) != 0, "正常 chunk 应保留")
    }
}
