package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Simple timing tests for the core optimization loop.
 *
 * These are NOT benchmarks — they measure wall-clock time to guard against
 * major performance regressions (e.g., from I/O or parallelism changes).
 * Thresholds are deliberately generous to avoid flakiness on CI.
 */
class IoTimingTest {
    @Test
    fun `multi-region processing completes within time budget`() {
        val fs = MemoryFS()
        val world =
            java.nio.file.Paths
                .get("/mem/timing-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))

        // Build 4 region files with 16 chunks each (total 64 chunks)
        val regions = listOf("r.0.0.mca", "r.0.1.mca", "r.1.0.mca", "r.1.1.mca")
        for ((i, name) in regions.withIndex()) {
            val data =
                McaMemoryBuilder.buildMca(
                    listOf(
                        McaMemoryBuilder.MemChunk(
                            index = i * 4,
                            inhabited = (i * 1000).toLong(),
                            kind = CompressionKind.RAW,
                        ),
                        McaMemoryBuilder.MemChunk(
                            index = i * 4 + 1,
                            inhabited = ((i + 1) * 1000).toLong(),
                            kind = CompressionKind.ZLIB,
                        ),
                        McaMemoryBuilder.MemChunk(
                            index = i * 4 + 2,
                            inhabited = ((i + 2) * 1000).toLong(),
                            kind = CompressionKind.LZ4,
                        ),
                    ),
                )
            fs.write(world.resolve("region").resolve(name), data)
        }

        val out =
            java.nio.file.Paths
                .get("/mem/timing-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )

        val start = System.nanoTime()
        val report = Optimizer.run(request)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // Generous limit: in-memory processing should easily finish within 30s
        val maxMs = 30_000L
        assertTrue(
            elapsedMs < maxMs,
            "multi-region processing took ${elapsedMs}ms, exceeded ${maxMs}ms budget",
        )
        assertTrue(report.processedChunks > 0, "should have processed chunks")
    }

    @Test
    fun `parallel processing is not slower than serial baseline`() {
        val fs = MemoryFS()
        val world =
            java.nio.file.Paths
                .get("/mem/timing-parallel")
        val dim1 =
            java.nio.file.Paths
                .get("/mem/timing-parallel/DIM1")
        val dim2 =
            java.nio.file.Paths
                .get("/mem/timing-parallel/DIM2")

        for (root in listOf(world, dim1, dim2)) {
            fs.createDirectories(root)
            fs.createDirectories(root.resolve("region"))
            val data = McaMemoryBuilder.buildSingleEntryMca(0, 5000, CompressionKind.RAW)
            fs.write(root.resolve("region").resolve("r.0.0.mca"), data)
        }

        val out =
            java.nio.file.Paths
                .get("/mem/timing-parallel-out")

        // Serial run
        val serialRequest =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                runtime = RuntimeOptions(parallelism = 1),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val serialStart = System.nanoTime()
        Optimizer.run(serialRequest)
        val serialMs = (System.nanoTime() - serialStart) / 1_000_000

        // Parallel run (fresh MemoryFS)
        val fs2 = MemoryFS()
        for (root in listOf(world, dim1, dim2)) {
            val newRoot =
                java.nio.file.Paths.get(
                    root.toString().replace("/mem/timing-parallel", "/mem/timing-parallel2"),
                )
            fs2.createDirectories(newRoot)
            fs2.createDirectories(newRoot.resolve("region"))
            val data = McaMemoryBuilder.buildSingleEntryMca(0, 5000, CompressionKind.RAW)
            fs2.write(newRoot.resolve("region").resolve("r.0.0.mca"), data)
        }
        val out2 =
            java.nio.file.Paths
                .get("/mem/timing-parallel-out2")
        val world2 =
            java.nio.file.Paths
                .get("/mem/timing-parallel2")

        val parallelRequest =
            OptimizerRequest(
                input = world2,
                output = out2,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                runtime = RuntimeOptions(parallelism = 3),
                io = IOOptions(fs = fs2, ioFactory = MemoryMcaIOFactory()),
            )
        val parallelStart = System.nanoTime()
        Optimizer.run(parallelRequest)
        val parallelMs = (System.nanoTime() - parallelStart) / 1_000_000

        // Allow generous slack — parallel overhead on CI runners can
        // significantly inflate wall-clock time for tiny in-memory workloads
        val maxSlack = 5.0
        if (serialMs > 0 && parallelMs > serialMs * maxSlack) {
            throw AssertionError(
                "Parallel (${parallelMs}ms) is more than ${maxSlack}x slower than serial (${serialMs}ms)",
            )
        }
    }
}
