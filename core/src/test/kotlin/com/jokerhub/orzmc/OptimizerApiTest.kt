package com.jokerhub.orzmc

import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.ProgressEvent
import com.jokerhub.orzmc.world.ProgressStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import com.jokerhub.orzmc.world.Cleaner
import com.jokerhub.orzmc.util.TestPaths

class OptimizerApiTest {
    private fun copyDir(src: Path, dst: Path) {
        Files.createDirectories(dst)
        Files.walk(src).forEach { p ->
            val rel = src.relativize(p)
            val target = dst.resolve(rel.toString())
            if (Files.isDirectory(p)) {
                Files.createDirectories(target)
            } else {
                Files.copy(p, target)
            }
        }
    }

    @Test
    fun `run with empty output directory returns report with progress`() {
        val input = TestPaths.world()
        val tmpOut = Files.createTempDirectory("optimizer-out-")
        val report = Optimizer.run(
            com.jokerhub.orzmc.world.OptimizerConfig(
                input = input,
                output = tmpOut,
                inhabitedThresholdSeconds = 0,
                removeUnknown = false,
                progressMode = com.jokerhub.orzmc.world.ProgressMode.Off,
                zipOutput = false,
                inPlace = false,
                force = true,
                strict = false,
                progressInterval = 10,
                onProgress = { _ -> }
            )
        )
        assertTrue(report.processedChunks > 0)
        assertTrue(report.errors.isEmpty())
        Cleaner.deleteTreeWithRetry(tmpOut, 5, 10)
    }


    @Test
    fun `strict mode collects errors for damaged mca`() {
        val fixture = TestPaths.world()
        val tmpWorld = Files.createTempDirectory("optimizer-world-bad-")
        copyDir(fixture, tmpWorld)
        val bad = tmpWorld.resolve("region").resolve("r.bad.mca")
        Files.write(bad, "x".toByteArray(Charsets.UTF_8))
        val tmpOut = Files.createTempDirectory("optimizer-out-bad-")
        val report = Optimizer.run(
            com.jokerhub.orzmc.world.OptimizerConfig(
                input = tmpWorld,
                output = tmpOut,
                inhabitedThresholdSeconds = 0,
                removeUnknown = false,
                progressMode = com.jokerhub.orzmc.world.ProgressMode.Off,
                zipOutput = false,
                inPlace = false,
                force = false,
                strict = true,
                progressInterval = 10,
                onProgress = { _ -> }
            )
        )
        assertTrue(report.errors.any { it.kind == "MCA" })
        Cleaner.deleteTreeWithRetry(tmpWorld, 5, 10)
        Cleaner.deleteTreeWithRetry(tmpOut, 5, 10)
    }

    @Test
    fun `non-empty output without force returns report with output error`() {
        val input = TestPaths.world()
        val tmpOut = Files.createTempDirectory("optimizer-out-nonempty-")
        Files.write(tmpOut.resolve("dummy.txt"), "a".toByteArray(Charsets.UTF_8))
        val report = Optimizer.run(
            com.jokerhub.orzmc.world.OptimizerConfig(
                input = input,
                output = tmpOut,
                inhabitedThresholdSeconds = 0,
                removeUnknown = false,
                progressMode = com.jokerhub.orzmc.world.ProgressMode.Off,
                zipOutput = false,
                inPlace = false,
                force = false,
                strict = false
            )
        )
        assertEquals(0, report.processedChunks)
        assertTrue(report.errors.any { it.kind == "Output" })
        Cleaner.deleteTreeWithRetry(tmpOut, 5, 10)
    }

    @Test
    fun `progress callback by chunks is emitted`() {
        val input = TestPaths.world()
        val events = mutableListOf<ProgressEvent>()
        val report = Optimizer.run(
            com.jokerhub.orzmc.world.OptimizerConfig(
                input = input,
                output = Files.createTempDirectory("optimizer-out-progress-"),
                inhabitedThresholdSeconds = 0,
                removeUnknown = false,
                progressMode = com.jokerhub.orzmc.world.ProgressMode.Off,
                zipOutput = false,
                inPlace = false,
                force = false,
                strict = false,
                progressInterval = 100,
                onProgress = { e -> events.add(e) }
            )
        )
        assertTrue(events.any { it.stage == ProgressStage.Done })
        assertTrue(events.any { it.stage == ProgressStage.ChunkProgress })
        assertTrue(report.processedChunks > 0)
    }

    @Test
    fun `progress callback by time is emitted`() {
        val input = TestPaths.world()
        val events = mutableListOf<ProgressEvent>()
        val report = Optimizer.run(
            com.jokerhub.orzmc.world.OptimizerConfig(
                input = input,
                output = Files.createTempDirectory("optimizer-out-progress-ms-"),
                inhabitedThresholdSeconds = 0,
                removeUnknown = false,
                progressMode = com.jokerhub.orzmc.world.ProgressMode.Off,
                zipOutput = false,
                inPlace = false,
                force = false,
                strict = false,
                progressInterval = 100000, // ensure chunk-based won't fire
                progressIntervalMs = 5,
                onProgress = { e: ProgressEvent -> events.add(e) }
            )
        )
        assertTrue(events.any { it.stage == ProgressStage.Done })
        assertTrue(events.any { it.stage == ProgressStage.ChunkProgress })
        assertTrue(report.processedChunks > 0)
    }
}
