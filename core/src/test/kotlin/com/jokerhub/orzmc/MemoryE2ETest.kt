package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.McaMemoryBuilder.MemChunk
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class MemoryE2ETest {
    @Test
    fun `end-to-end optimize with MemoryFS and MemoryMcaIOFactory`() {
        val fs = MemoryFS()
        val world =
            java.nio.file.Paths
                .get("/mem/world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val data = McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), data)
        val out =
            java.nio.file.Paths
                .get("/mem/out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertEquals(1, report.processedChunks)
        assertEquals(0, report.removedChunks)
        val outFile = out.resolve("region").resolve("r.0.0.mca")
        val realDir = fs.toRealPath(out.resolve("region"))
        assertTrue(Files.exists(realDir))
        val real = fs.toRealPath(outFile)
        assertTrue(Files.size(real) >= 8192)
    }

    @Test
    fun `dry-run mode processes chunks without writing output`() {
        val fs = MemoryFS()
        val world =
            java.nio.file.Paths
                .get("/mem/dryrun-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val data = McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), data)
        val out =
            java.nio.file.Paths
                .get("/mem/dryrun-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                outputOptions = OutputOptions(dryRun = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        // Should report correct chunk counts
        assertEquals(1, report.processedChunks)
        assertEquals(0, report.removedChunks)
        // Should NOT have written output files
        assertFalse(
            fs.exists(out.resolve("region").resolve("r.0.0.mca")),
            "dry-run should not write output files",
        )
    }

    @Test
    fun `all chunks removed does not create empty MCA output`() {
        val fs = MemoryFS()
        val world =
            java.nio.file.Paths
                .get("/mem/empty-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        // MCA with 2 chunks, both with InhabitedTime=0 (unvisited)
        val data =
            McaMemoryBuilder.buildMca(
                listOf(
                    MemChunk(0, 0L, CompressionKind.RAW),
                    MemChunk(1, 0L, CompressionKind.RAW),
                ),
            )
        fs.write(world.resolve("region").resolve("r.0.0.mca"), data)
        val out =
            java.nio.file.Paths
                .get("/mem/empty-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                // threshold > 0 means InhabitedTime == 0 chunks are removed
                filter = FilterOptions(inhabitedThresholdSeconds = 1, removeUnknown = false),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertEquals(2, report.processedChunks)
        assertEquals(2, report.removedChunks)
        // Output MCA file should NOT exist when all chunks are removed
        assertFalse(
            fs.exists(out.resolve("region").resolve("r.0.0.mca")),
            "output MCA should not be created when all chunks are removed",
        )
    }

    @Test
    fun `dry-run mode removes nothing`() {
        val fs = MemoryFS()
        val world =
            java.nio.file.Paths
                .get("/mem/dryrun-remove")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val data = McaMemoryBuilder.buildSingleEntryMca(5, 100, CompressionKind.RAW)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), data)
        val out =
            java.nio.file.Paths
                .get("/mem/dryrun-out2")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 99999),
                outputOptions = OutputOptions(dryRun = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        // Should report removed chunks
        assertTrue(report.removedChunks > 0, "chunks below threshold should be counted as removed")
        // But original world should remain intact
        assertTrue(
            fs.exists(world.resolve("region").resolve("r.0.0.mca")),
            "original world should remain untouched in dry-run",
        )
        // No output MCA file should exist (directory may exist as structural prep)
        assertFalse(
            fs.exists(out.resolve("region").resolve("r.0.0.mca")),
            "no output MCA file should exist in dry-run",
        )
    }

    /**
     * A minimal valid NBT compound: TAG_Compound("") + TAG_End.
     * No InhabitedTime tag — findInhabitedFast returns null for these chunks.
     */
    private fun minimalNbtPayload(): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write(0x0A) // TAG_Compound
        bos.write(byteArrayOf(0x00, 0x00)) // name length = 0
        bos.write(0x00) // TAG_End
        return bos.toByteArray()
    }

    @Test
    fun `removeUnknown true removes chunks without InhabitedTime tag`() {
        val fs = MemoryFS()
        val world =
            java.nio.file.Paths
                .get("/mem/remove-unknown-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))

        // Build MCA where the chunk payload has NO InhabitedTime tag
        val noInhabitedMca = McaMemoryBuilder.buildCustomPayloadMca(0, minimalNbtPayload(), CompressionKind.RAW)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), noInhabitedMca)

        val out =
            java.nio.file.Paths
                .get("/mem/remove-unknown-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                // threshold=0, removeUnknown=true: chunks without InhabitedTime → removed
                filter = FilterOptions(inhabitedThresholdSeconds = 0, removeUnknown = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertEquals(1, report.processedChunks)
        assertEquals(1, report.removedChunks, "chunk without InhabitedTime should be removed when removeUnknown=true")
        // No output MCA — all chunks removed
        assertFalse(
            fs.exists(out.resolve("region").resolve("r.0.0.mca")),
            "no output MCA when all chunks removed",
        )
    }

    @Test
    fun `removeUnknown false keeps chunks without InhabitedTime tag`() {
        val fs = MemoryFS()
        val world =
            java.nio.file.Paths
                .get("/mem/keep-unknown-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))

        val noInhabitedMca = McaMemoryBuilder.buildCustomPayloadMca(0, minimalNbtPayload(), CompressionKind.RAW)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), noInhabitedMca)

        val out =
            java.nio.file.Paths
                .get("/mem/keep-unknown-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                // threshold=0, removeUnknown=false (default): chunks without InhabitedTime → kept
                filter = FilterOptions(inhabitedThresholdSeconds = 0, removeUnknown = false),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertEquals(1, report.processedChunks)
        assertEquals(0, report.removedChunks, "chunk without InhabitedTime should be kept when removeUnknown=false")
        // Output MCA should exist
        assertTrue(
            fs.exists(out.resolve("region").resolve("r.0.0.mca")),
            "output MCA should exist when chunk is kept",
        )
    }

    @Test
    fun `region-level parallelism processes all chunks correctly`() {
        val fs = MemoryFS()
        val world =
            java.nio.file.Paths
                .get("/mem/parallel-region-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))

        // Create 5 MCA files with multiple chunks each
        repeat(5) { regionIdx ->
            val data =
                McaMemoryBuilder.buildMca(
                    listOf(
                        MemChunk(0, 1000, CompressionKind.RAW),
                        MemChunk(1, 2000, CompressionKind.RAW),
                        MemChunk(2, 500, CompressionKind.RAW),
                    ),
                )
            fs.write(world.resolve("region").resolve("r.0.$regionIdx.mca"), data)
        }

        val out =
            java.nio.file.Paths
                .get("/mem/parallel-region-out")
        val request =
            OptimizerRequest(
                input = world,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                // triggers region-level parallel path
                runtime = RuntimeOptions(parallelism = 3),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        // 5 regions × 3 chunks = 15 total
        assertEquals(15, report.processedChunks, "all chunks from all regions should be processed")
        assertEquals(0, report.removedChunks, "all chunks have InhabitedTime > 0 so should be kept with threshold=0")
        assertTrue(report.errors.isEmpty(), "no errors expected")
    }

    @Test
    fun `strict mode with corrupted force-load file records error and continues processing`() {
        // ForceLoad.parse uses java.io.File internally, so this test uses a real temp
        // directory so that the dimensional probe paths exist on the real filesystem.
        val world = Files.createTempDirectory("strict-corrupt-force-world")
        try {
            Files.createDirectories(world.resolve("region"))
            Files.write(
                world.resolve("region").resolve("r.0.0.mca"),
                McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
            )
            // Create a corrupted data/chunks.dat (the fallback probe file)
            Files.createDirectories(world.resolve("data"))
            Files.write(world.resolve("data").resolve("chunks.dat"), "corrupted-not-gzip".toByteArray(Charsets.UTF_8))

            val out = Files.createTempDirectory("strict-corrupt-force-out")
            try {
                val request =
                    OptimizerRequest(
                        input = world,
                        output = out,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0, strict = true),
                    )
                val report = Optimizer.run(request)
                // Should still process chunks despite corrupted force-load file
                assertEquals(
                    1,
                    report.processedChunks,
                    "chunks should be processed even with corrupted force-load file",
                )
                // Error should be recorded
                assertTrue(
                    report.errors.any { it.kind == "ForceLoaded" },
                    "strict mode should record error for corrupted force-load file, got: ${report.errors}",
                )
            } finally {
                out.toFile().deleteRecursively()
            }
        } finally {
            world.toFile().deleteRecursively()
        }
    }
}
