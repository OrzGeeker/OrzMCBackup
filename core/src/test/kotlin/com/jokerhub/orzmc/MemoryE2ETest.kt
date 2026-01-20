package com.jokerhub.orzmc

import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MemoryE2ETest {
    @Test
    fun `end-to-end optimize with MemoryFS and MemoryMcaIOFactory`() {
        val fs = MemoryFS()
        val world = java.nio.file.Paths.get("/mem/world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        // minimal MCA placeholder (size >= 8192)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), ByteArray(8192))
        val out = java.nio.file.Paths.get("/mem/out")
        val cfg = OptimizerConfig(
            input = world,
            output = out,
            inhabitedThresholdSeconds = 0,
            removeUnknown = false,
            fs = fs,
            ioFactory = MemoryMcaIOFactory(),
            progressSink = CallbackProgressSink(null),
            reportSink = null
        )
        val report = Optimizer.run(cfg)
        assertTrue(report.processedChunks >= 0)
        val outFile = out.resolve("region").resolve("r.0.0.mca")
        val realDir = fs.toRealPath(out.resolve("region"))
        assertTrue(Files.exists(realDir))
        val real = fs.toRealPath(outFile)
        assertTrue(Files.size(real) >= 8192)
    }
}
