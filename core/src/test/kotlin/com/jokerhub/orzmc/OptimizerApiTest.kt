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
        val url = this::class.java.classLoader.getResource("Fixtures/world")
        assertTrue(url != null)
        val input = Paths.get(url!!.toURI())
        val tmpOut = Files.createTempDirectory("optimizer-out-")
        val report = Optimizer.run(
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
        assertTrue(report.processedChunks > 0)
        assertTrue(report.errors.isEmpty())
        Files.walk(tmpOut).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }


    @Test
    fun `strict mode collects errors for damaged mca`() {
        val url = this::class.java.classLoader.getResource("Fixtures/world")
        assertTrue(url != null)
        val fixture = Paths.get(url!!.toURI())
        val tmpWorld = Files.createTempDirectory("optimizer-world-bad-")
        copyDir(fixture, tmpWorld)
        val bad = tmpWorld.resolve("region").resolve("r.bad.mca")
        Files.writeString(bad, "x")
        val tmpOut = Files.createTempDirectory("optimizer-out-bad-")
        val report = Optimizer.runWithReport(
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
        assertTrue(report.errors.any { it.kind == "MCA" })
        Files.walk(tmpWorld).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        Files.walk(tmpOut).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `non-empty output without force returns report with output error`() {
        val url = this::class.java.classLoader.getResource("Fixtures/world")
        assertTrue(url != null)
        val input = Paths.get(url!!.toURI())
        val tmpOut = Files.createTempDirectory("optimizer-out-nonempty-")
        Files.writeString(tmpOut.resolve("dummy.txt"), "a")
        val report = Optimizer.runWithReport(
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
        assertEquals(0, report.processedChunks)
        assertTrue(report.errors.any { it.kind == "Output" })
        Files.walk(tmpOut).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `progress callback by chunks is emitted`() {
        val url = this::class.java.classLoader.getResource("Fixtures/world")
        assertTrue(url != null)
        val input = Paths.get(url!!.toURI())
        val events = mutableListOf<ProgressEvent>()
        val report = Optimizer.runWithReport(
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
        assertTrue(events.any { it.stage == ProgressStage.Done })
        assertTrue(events.any { it.stage == ProgressStage.ChunkProgress })
        assertTrue(report.processedChunks > 0)
    }

    @Test
    fun `progress callback by time is emitted`() {
        val url = this::class.java.classLoader.getResource("Fixtures/world")
        assertTrue(url != null)
        val input = Paths.get(url!!.toURI())
        val events = mutableListOf<ProgressEvent>()
        val report = Optimizer.runWithReport(
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
            onProgress = { e -> events.add(e) }
        )
        assertTrue(events.any { it.stage == ProgressStage.Done })
        assertTrue(events.any { it.stage == ProgressStage.ChunkProgress })
        assertTrue(report.processedChunks > 0)
    }
}
