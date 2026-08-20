package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.TestTmp
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OptimizerInputValidationTest {
    private fun createWorldWithEntry(): Path {
        val world = TestTmp.createTempDirectory("optimizer-world-input-")
        Files.createDirectories(world.resolve("region"))
        val data = McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW)
        Files.write(world.resolve("region").resolve("r.0.0.mca"), data)
        return world
    }

    @Test
    fun `input is not directory returns input error`() {
        val input = Files.createTempFile("optimizer-input-file-", ".tmp")
        val out = TestTmp.createTempDirectory("optimizer-out-input-")
        val report =
            Optimizer.run(
                OptimizerRequest(
                    input = input,
                    output = out,
                    filter = FilterOptions(inhabitedThresholdSeconds = 0),
                ),
            )
        assertEquals(0, report.processedChunks)
        assertTrue(report.errors.any { it.kind == "Input" })
        Files.deleteIfExists(input)
        Cleaner.deleteTreeWithRetry(out, 5, 10)
    }

    @Test
    fun `output missing without inPlace returns output error`() {
        val input = createWorldWithEntry()
        val report =
            Optimizer.run(
                OptimizerRequest(
                    input = input,
                    output = null,
                    filter = FilterOptions(inhabitedThresholdSeconds = 0),
                ),
            )
        assertEquals(0, report.processedChunks)
        assertTrue(report.errors.any { it.kind == "Output" })
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `output equal to input is rejected before any write`() {
        val world = createWorldWithEntry()
        try {
            val report =
                Optimizer.run(
                    OptimizerRequest(
                        input = world,
                        output = world,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                    ),
                )
            assertEquals(0, report.processedChunks, "overlapping output must not process any chunk")
            assertTrue(report.errors.any { it.kind == "Input" && it.message.contains("non-overlapping") })
            // The guard must run before resolveOutputDir, so the source world is untouched.
            assertTrue(Files.exists(world.resolve("region").resolve("r.0.0.mca")), "source world must be intact")
        } finally {
            Cleaner.deleteTreeWithRetry(world, 5, 10)
        }
    }

    @Test
    fun `output nested inside input is rejected`() {
        val world = createWorldWithEntry()
        val out = world.resolve("backup")
        try {
            val report =
                Optimizer.run(
                    OptimizerRequest(
                        input = world,
                        output = out,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                    ),
                )
            assertEquals(0, report.processedChunks)
            assertTrue(report.errors.any { it.kind == "Input" })
            assertTrue(Files.exists(world.resolve("region").resolve("r.0.0.mca")), "source world must be intact")
        } finally {
            Cleaner.deleteTreeWithRetry(world, 5, 10)
        }
    }

    @Test
    fun `output being an ancestor of input is rejected`() {
        val world = createWorldWithEntry()
        try {
            val report =
                Optimizer.run(
                    OptimizerRequest(
                        input = world,
                        output = world.parent,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                    ),
                )
            assertEquals(0, report.processedChunks)
            assertTrue(report.errors.any { it.kind == "Input" })
            assertTrue(Files.exists(world.resolve("region").resolve("r.0.0.mca")), "source world must be intact")
        } finally {
            Cleaner.deleteTreeWithRetry(world, 5, 10)
        }
    }

    @Test
    fun `distinct output with force still succeeds`() {
        val world = createWorldWithEntry()
        val out = TestTmp.createTempDirectory("optimizer-out-ok-")
        try {
            val report =
                Optimizer.run(
                    OptimizerRequest(
                        input = world,
                        output = out,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                        outputOptions = OutputOptions(force = true),
                    ),
                )
            assertTrue(report.errors.none { it.kind == "Input" }, "distinct output must not trigger the alias guard")
            assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")), "optimized output should exist")
        } finally {
            Cleaner.deleteTreeWithRetry(world, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }
}
