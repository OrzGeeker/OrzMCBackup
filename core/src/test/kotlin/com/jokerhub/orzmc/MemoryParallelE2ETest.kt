package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MemoryParallelE2ETest {
    @Test
    fun `parallel optimize across dimensions and regions`() {
        val fs = MemoryFS()
        val world = java.nio.file.Paths.get("/mem/world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val dim1 = world.resolve("DIM1")
        fs.createDirectories(dim1)
        fs.createDirectories(dim1.resolve("region"))
        val r00 = McaMemoryBuilder.buildMca(
            0, 0, listOf(
                McaMemoryBuilder.MemChunk(index = 0, inhabited = 500, kind = CompressionKind.RAW),
                McaMemoryBuilder.MemChunk(index = 1, inhabited = 3000, kind = CompressionKind.ZLIB)
            )
        )
        val r10 = McaMemoryBuilder.buildMca(
            1, 0, listOf(
                McaMemoryBuilder.MemChunk(index = 10, inhabited = 1000, kind = CompressionKind.LZ4),
                McaMemoryBuilder.MemChunk(index = 11, inhabited = 100, kind = CompressionKind.RAW)
            )
        )
        fs.write(world.resolve("region").resolve("r.0.0.mca"), r00)
        fs.write(world.resolve("region").resolve("r.1.0.mca"), r10)
        val d1r00 = McaMemoryBuilder.buildMca(
            0, 0, listOf(
                McaMemoryBuilder.MemChunk(index = 2, inhabited = 4000, kind = CompressionKind.ZLIB),
                McaMemoryBuilder.MemChunk(index = 3, inhabited = 50, kind = CompressionKind.RAW)
            )
        )
        fs.write(dim1.resolve("region").resolve("r.0.0.mca"), d1r00)
        val out = java.nio.file.Paths.get("/mem/out")
        val cfg = OptimizerConfig(
            input = world,
            output = out,
            inhabitedThresholdSeconds = 100,
            removeUnknown = true,
            fs = fs,
            ioFactory = MemoryMcaIOFactory(),
            parallelism = 3,
            progressSink = CallbackProgressSink(null)
        )
        val report = Optimizer.run(cfg)
        val totalEntries = 6L
        val ticks = cfg.inhabitedThresholdSeconds * 20
        val removedExpected = listOf(500L, 3000L, 1000L, 100L, 4000L, 50L).count { it < ticks }.toLong()
        assertEquals(totalEntries, report.processedChunks)
        assertEquals(removedExpected, report.removedChunks)
        val realOut = fs.toRealPath(out.resolve("region"))
        assertTrue(Files.exists(realOut))
    }
}
