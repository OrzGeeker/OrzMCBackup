package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Tests derived from patterns found in a real PaperMC 26.1+ world
 * (E:\test\world): region .bak/.backup files, zero-byte level<number>.dat
 * temp files, per-dimension death-chests.yml, and the lazy-writer dropping of
 * regions whose chunks are all removed.
 */
class RealWorldPatternTest {
    // MemoryFS.createDirectories registers only the exact node, so every
    // intermediate directory must be created explicitly (see Paper26StructureTest).
    private fun MemoryFS.mkdirs(path: Path) {
        val chain = mutableListOf<Path>()
        var cur: Path? = path
        while (cur != null) {
            chain.add(cur)
            cur = cur.parent
        }
        chain.asReversed().forEach { createDirectories(it) }
    }

    private fun request(
        fs: MemoryFS,
        input: Path,
        output: Path,
    ) = OptimizerRequest(
        input = input,
        output = output,
        filter = FilterOptions(inhabitedThresholdSeconds = 0),
        outputOptions = OutputOptions(force = true),
        io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
    )

    @Test
    fun `non mca files inside region entities poi are preserved`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/non-mca-in-region")
        val overworld = input.resolve("dimensions/minecraft/overworld")
        fs.mkdirs(overworld.resolve("region"))
        fs.write(
            overworld.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.write(overworld.resolve("region").resolve("r.0.0.mca.bak"), "region-backup".toByteArray())
        fs.write(
            overworld.resolve("region").resolve("r.0.-4.mca.2060040953768669224.backup"),
            "region-backup-2".toByteArray(),
        )
        fs.mkdirs(overworld.resolve("entities"))
        fs.write(overworld.resolve("entities").resolve("note.txt"), "note".toByteArray())
        fs.mkdirs(overworld.resolve("poi"))
        fs.write(overworld.resolve("poi").resolve("x.txt"), "x".toByteArray())

        val output = Paths.get("/mem/non-mca-in-region-out")
        val report = Optimizer.run(request(fs, input, output))
        assertTrue(report.processedChunks > 0, "should process chunks")
        assertTrue(report.errors.isEmpty(), "no errors expected, got: ${report.errors}")

        assertTrue(
            fs.exists(output.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")),
            "rewritten region mca should be in output",
        )
        assertTrue(
            fs.exists(output.resolve("dimensions/minecraft/overworld/region/r.0.0.mca.bak")),
            ".mca.bak inside region should be preserved",
        )
        assertTrue(
            fs.exists(
                output.resolve("dimensions/minecraft/overworld/region/r.0.-4.mca.2060040953768669224.backup"),
            ),
            ".mca.<id>.backup inside region should be preserved",
        )
        assertTrue(
            fs.exists(output.resolve("dimensions/minecraft/overworld/entities/note.txt")),
            "non-mca file inside entities should be preserved",
        )
        assertTrue(
            fs.exists(output.resolve("dimensions/minecraft/overworld/poi/x.txt")),
            "non-mca file inside poi should be preserved",
        )
    }

    @Test
    fun `zero byte level temp dat files at world root are preserved`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/zero-byte-level")
        fs.mkdirs(input)
        fs.write(input.resolve("level.dat"), "level-data".toByteArray())
        fs.write(input.resolve("level12345678901234567890.dat"), ByteArray(0))
        fs.write(input.resolve("level98765432109876543210.dat"), ByteArray(0))
        fs.mkdirs(input.resolve("players"))
        fs.write(input.resolve("players").resolve("p.dat"), "p".toByteArray())
        fs.mkdirs(input.resolve("dimensions/minecraft/overworld/region"))
        fs.write(
            input.resolve("dimensions/minecraft/overworld/region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )

        val output = Paths.get("/mem/zero-byte-level-out")
        val report = Optimizer.run(request(fs, input, output))
        assertTrue(report.processedChunks > 0, "should process chunks")
        assertTrue(report.errors.isEmpty(), "no errors expected, got: ${report.errors}")

        assertTrue(fs.exists(output.resolve("level.dat")), "level.dat should be preserved")
        assertTrue(
            fs.exists(output.resolve("level12345678901234567890.dat")),
            "zero-byte level temp file should be preserved",
        )
        assertTrue(
            fs.exists(output.resolve("level98765432109876543210.dat")),
            "zero-byte level temp file should be preserved",
        )
    }

    @Test
    fun `death chests yml at world root and per dimension are preserved`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/death-chests")
        fs.mkdirs(input)
        fs.write(input.resolve("level.dat"), "level-data".toByteArray())
        fs.write(input.resolve("death-chests.yml"), "root-chests".toByteArray())
        val overworld = input.resolve("dimensions/minecraft/overworld")
        fs.mkdirs(overworld)
        fs.write(overworld.resolve("death-chests.yml"), "overworld-chests".toByteArray())
        fs.mkdirs(overworld.resolve("region"))
        fs.write(
            overworld.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )

        val output = Paths.get("/mem/death-chests-out")
        val report = Optimizer.run(request(fs, input, output))
        assertTrue(report.processedChunks > 0, "should process chunks")
        assertTrue(report.errors.isEmpty(), "no errors expected, got: ${report.errors}")

        assertTrue(fs.exists(output.resolve("death-chests.yml")), "world-root death-chests.yml should be preserved")
        assertTrue(
            fs.exists(output.resolve("dimensions/minecraft/overworld/death-chests.yml")),
            "per-dimension death-chests.yml should be preserved",
        )
    }

    @Test
    fun `classic layout nested dimension data files are preserved`() {
        // Classic pre-26.1 layout: the world root itself is a dimension (has region/)
        // and nests the nether/end as DIM-1/DIM1, each carrying its own data/ folder.
        // Regression for BUG: miscRel's exclude check hid a nested dimension's own
        // data/* files when an ancestor dimension (the root) was in excludePaths.
        val fs = MemoryFS()
        val input = Paths.get("/mem/classic-nested-data")
        fs.mkdirs(input.resolve("region"))
        fs.write(
            input.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.mkdirs(input.resolve("data"))
        fs.write(input.resolve("data").resolve("root-data.dat"), "root".toByteArray())
        val nestedDims =
            mapOf(
                "DIM-1" to listOf("fortress_index.dat", "chunks.dat", "world_border.dat"),
                "DIM1" to listOf("endcity_index.dat", "raids_end.dat"),
            )
        for ((dim, dataFiles) in nestedDims) {
            val d = input.resolve(dim)
            fs.mkdirs(d.resolve("region"))
            fs.write(
                d.resolve("region").resolve("r.0.0.mca"),
                McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
            )
            fs.mkdirs(d.resolve("data"))
            dataFiles.forEach { name -> fs.write(d.resolve("data").resolve(name), "x".toByteArray()) }
        }

        val output = Paths.get("/mem/classic-nested-data-out")
        val report = Optimizer.run(request(fs, input, output))
        assertTrue(report.processedChunks > 0, "should process chunks")
        assertTrue(report.errors.isEmpty(), "no errors expected, got: ${report.errors}")

        // root dimension misc
        assertTrue(fs.exists(output.resolve("data/root-data.dat")), "root dimension data/ should be preserved")
        // nested dimension data/ must survive (pre-fix they were silently dropped)
        assertTrue(
            fs.exists(output.resolve("DIM-1/data/fortress_index.dat")),
            "DIM-1/data/fortress_index.dat should be preserved",
        )
        assertTrue(
            fs.exists(output.resolve("DIM-1/data/chunks.dat")),
            "DIM-1/data/chunks.dat should be preserved",
        )
        assertTrue(
            fs.exists(output.resolve("DIM1/data/endcity_index.dat")),
            "DIM1/data/endcity_index.dat should be preserved",
        )
        assertTrue(
            fs.exists(output.resolve("DIM1/data/raids_end.dat")),
            "DIM1/data/raids_end.dat should be preserved",
        )
        // rewritten regions still present
        assertTrue(fs.exists(output.resolve("DIM-1/region/r.0.0.mca")), "DIM-1 region mca should be in output")
        assertTrue(fs.exists(output.resolve("DIM1/region/r.0.0.mca")), "DIM1 region mca should be in output")
    }

    @Test
    fun `region with all chunks removed is not written to output`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/all-removed-region")
        fs.mkdirs(input.resolve("region"))
        // inhabited=0 is NOT > threshold(0) → chunk removed → region dropped by lazy writer
        fs.write(
            input.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 0, CompressionKind.RAW),
        )
        // inhabited=1000 > 0 → chunk kept → region rewritten
        fs.write(
            input.resolve("region").resolve("r.1.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )

        val output = Paths.get("/mem/all-removed-region-out")
        val report = Optimizer.run(request(fs, input, output))
        assertTrue(report.processedChunks > 0, "should process chunks")
        assertTrue(report.errors.isEmpty(), "no errors expected, got: ${report.errors}")

        assertTrue(fs.exists(output.resolve("region/r.1.0.mca")), "region with kept chunk should exist")
        assertFalse(fs.exists(output.resolve("region/r.0.0.mca")), "region with all chunks removed should be dropped")
    }
}
