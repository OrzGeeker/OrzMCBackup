package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path

class McaMemoryParamTest {
    @ParameterizedTest
    @CsvSource(
        "RAW,0,0",
        "RAW,1000,1",
        "RAW,1001,1",
        "ZLIB,0,0",
        "ZLIB,1000,1",
        "ZLIB,1001,1",
        "LZ4,0,0",
        "LZ4,1000,1",
        "LZ4,1001,1"
    )
    fun `memory mca with inhabited threshold and compression`(comp: String, threshold: Long, removedExpected: Long) {
        val fs = MemoryFS()
        val world = java.nio.file.Paths.get("/mem/world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val method = CompressionKind.valueOf(comp)
        val bytes = McaMemoryBuilder.buildSingleEntryMca(0, 0, 0, 1000L, method)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), bytes)
        val out = java.nio.file.Paths.get("/mem/out")
        val cfg = OptimizerConfig(
            input = world,
            output = out,
            inhabitedThresholdSeconds = threshold,
            removeUnknown = false,
            fs = fs,
            ioFactory = MemoryMcaIOFactory(),
            progressSink = CallbackProgressSink(null),
            reportSink = null
        )
        val report = Optimizer.run(cfg)
        assertTrue(report.processedChunks >= 1)
        assertEquals(removedExpected, report.removedChunks)
    }
}
