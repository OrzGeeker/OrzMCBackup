package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.DefaultMcaIOFactory
import com.jokerhub.orzmc.world.FilterOptions
import com.jokerhub.orzmc.world.IOOptions
import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.OptimizerRequest
import com.jokerhub.orzmc.world.RealFileSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * 真实 IO 路径的损坏 chunk 容错（覆盖 RealMcaWriterAdapter → McaWriter 的空数据防护）：
 * MemoryFS 测试走 MemoryMcaWriter，真实 McaWriter 的新增检查需真实 IO 测试触发。
 */
class RealCorruptedChunkTest {
    private fun corruptCompressionByte(
        mca: ByteArray,
        index: Int,
    ): ByteArray {
        val out = mca.copyOf()
        val locVal = ByteBuffer.wrap(out, index * 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val off = (locVal ushr 8) * 4096
        out[off + 4] = 49 // 非法 compression
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
    fun `real io skips corrupted chunk without stalling and records error`() {
        val fs = RealFileSystem
        val world = Files.createTempDirectory("real-corrupt-world")
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"))
        val regionDir = world.resolve("dimensions/minecraft/overworld/region")
        val mca =
            McaMemoryBuilder.buildMca(
                listOf(
                    McaMemoryBuilder.MemChunk(0, 1000, CompressionKind.ZLIB),
                    McaMemoryBuilder.MemChunk(1, 1000, CompressionKind.ZLIB),
                ),
            )
        // idx1：compression 非法 + 长度荒谬（≈180MB）
        val data = corruptCompressionByte(mca, 1)
        val locVal = ByteBuffer.wrap(data, 1 * 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val off = (locVal ushr 8) * 4096
        data[off] = 0x0a.toByte()
        data[off + 1] = 0xc9.toByte()
        data[off + 2] = 0xfb.toByte()
        data[off + 3] = 0xd1.toByte()
        Files.write(regionDir.resolve("r.0.0.mca"), data)

        val out = Files.createTempDirectory("real-corrupt-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                io = IOOptions(fs = fs, ioFactory = DefaultMcaIOFactory()),
            )
        val report = Optimizer.run(request)

        assertEquals(2, report.processedChunks)
        assertTrue(
            report.errors.any { it.kind == "Pattern" || it.kind == "Write" },
            "损坏 chunk 应记录错误，实际: ${report.errors}",
        )
        val outFile = out.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")
        assertTrue(Files.exists(outFile), "输出 MCA 应存在")
        val outBytes = Files.readAllBytes(outFile)
        assertTrue(entryLocation(outBytes, 0) != 0, "正常 chunk 应保留")
    }
}
