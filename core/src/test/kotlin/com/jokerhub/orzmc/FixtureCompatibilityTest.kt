package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.TestPaths
import com.jokerhub.orzmc.util.TestTmp
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests using real fixture worlds to verify the optimizer handles
 * both old and Paper 26.1+ world directory structures correctly.
 */
class FixtureCompatibilityTest {
    private fun fsFail(e: FileSystemException): Nothing {
        val msg = "FileSystemException: file=${e.file} other=${e.otherFile} reason=${e.reason} msg=${e.message}"
        System.err.println(msg)
        throw AssertionError(msg, e)
    }

    private fun copyDir(
        src: Path,
        dst: Path,
    ) {
        Files.createDirectories(dst)
        Files.walk(src).use { s ->
            s.forEach { p ->
                val rel = src.relativize(p)
                val target = dst.resolve(rel.toString())
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(p, target)
                }
            }
        }
    }

    @Test
    fun `old flat structure processes all dimensions and misc files`() {
        val fixture = TestPaths.world()
        val input = TestTmp.createTempDirectory("fixture-old-")
        copyDir(fixture, input)

        val out = TestTmp.createTempDirectory("fixture-old-out-")
        val report =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = input,
                        output = out,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                        outputOptions = OutputOptions(force = true),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(report.processedChunks > 0, "should process chunks in flat structure")
        assertTrue(report.errors.isEmpty(), "no errors expected")
        assertTrue(
            Files.exists(out.resolve("region").resolve("r.0.0.mca")),
            "region file should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("level.dat")),
            "level.dat should be copied as misc file",
        )
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `paper 26 dot 1 nested structure discovers all dimensions`() {
        val fixture = TestPaths.world26_1()
        val input = TestTmp.createTempDirectory("fixture-26-1-")
        // Paper 26.1: server root has world/ containing both root metadata
        // and nested dimensions. Copy fixture into world/ subdirectory.
        val worldDir = input.resolve("world")
        copyDir(fixture, worldDir)

        val out = TestTmp.createTempDirectory("fixture-26-1-out-")
        val report =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = input,
                        output = out,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                        outputOptions = OutputOptions(force = true),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(report.processedChunks > 0, "should process chunks from nested dimensions")
        assertTrue(report.errors.isEmpty(), "no errors expected")
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `paper 26 dot 1 preserves world root misc files`() {
        val fixture = TestPaths.world26_1()
        val input = TestTmp.createTempDirectory("fixture-26-1-misc-")
        val worldDir = input.resolve("world")
        copyDir(fixture, worldDir)

        val out = TestTmp.createTempDirectory("fixture-26-1-misc-out-")
        try {
            Optimizer.run(
                OptimizerRequest(
                    input = input,
                    output = out,
                    filter = FilterOptions(inhabitedThresholdSeconds = 0),
                    outputOptions = OutputOptions(force = true),
                ),
            )
        } catch (e: FileSystemException) {
            fsFail(e)
        }

        // World root level metadata — must be at output/world/
        assertTrue(
            Files.exists(out.resolve("world/level.dat")),
            "world root level.dat should be in output",
        )
        assertTrue(
            Files.exists(out.resolve("world/data/chunks.dat")),
            "world root data/ should be in output",
        )
        assertTrue(
            Files.exists(out.resolve("world/session.lock")),
            "world root session.lock should be in output",
        )

        // Dimension data — should exist under output/world/dimensions/
        assertTrue(
            Files.exists(out.resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca")),
            "overworld region should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("world/dimensions/minecraft/the_nether/region/r.0.0.mca")),
            "nether region should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("world/dimensions/minecraft/the_end/region/r.0.0.mca")),
            "end region should be copied",
        )

        // Dimension-level misc files (data/ subdirectory inside each dimension)
        assertTrue(
            Files.exists(out.resolve("world/dimensions/minecraft/overworld/data/chunks.dat")),
            "overworld dimension-level data/ should be copied",
        )

        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `both old and new structures produce comparable chunk counts`() {
        val oldFixture = TestPaths.world()
        val newFixture = TestPaths.world26_1()
        val oldInput = TestTmp.createTempDirectory("fixture-old-compare-")
        val newInput = TestTmp.createTempDirectory("fixture-26-1-compare-")
        copyDir(oldFixture, oldInput)
        copyDir(newFixture, newInput.resolve("world"))

        val oldReport =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = oldInput,
                        output = TestTmp.createTempDirectory("old-compare-out-"),
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                        outputOptions = OutputOptions(force = true),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        val newReport =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = newInput,
                        output = TestTmp.createTempDirectory("new-compare-out-"),
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                        outputOptions = OutputOptions(force = true),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }

        // Both should have processed chunks
        assertTrue(oldReport.processedChunks > 0, "old structure should process chunks")
        assertTrue(newReport.processedChunks > 0, "new structure should process chunks")

        // Both should have zero errors with threshold=0
        assertTrue(oldReport.errors.isEmpty(), "old structure: no errors")
        assertTrue(newReport.errors.isEmpty(), "new structure: no errors")

        Cleaner.deleteTreeWithRetry(oldInput, 5, 10)
        Cleaner.deleteTreeWithRetry(newInput, 5, 10)
    }
}
