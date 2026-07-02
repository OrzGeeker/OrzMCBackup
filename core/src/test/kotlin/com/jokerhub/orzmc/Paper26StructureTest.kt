package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Tests for Paper 26.1+ nested world directory structure:
 *
 * ```
 * <input>/
 * └── world/
 *     ├── level.dat
 *     ├── players/
 *     │   └── player1.dat
 *     ├── data/
 *     │   └── scores.dat
 *     └── dimensions/
 *         └── minecraft/
 *             ├── overworld/
 *             │   ├── region/r.0.0.mca
 *             │   ├── entities/r.0.0.mca
 *             │   └── data/overworld_meta.dat
 *             ├── the_nether/
 *             │   └── region/r.-1.-1.mca
 *             └── the_end/
 *                 └── region/r.1.1.mca
 * ```
 *
 * In this layout, dimensions do NOT live at the input root — they are nested
 * under `world/dimensions/minecraft/`. The root of `world/` (level.dat,
 * players/, data/) must be preserved as misc files even though `world/`
 * itself is not a dimension directory (it has no `region/` subdirectory).
 */
class Paper26StructureTest {
    @Test
    fun `nested dimensions with root-level misc files are fully backed up`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/paper26-server")
        fs.createDirectories(input)

        // Build Paper 26.1+ structure
        val worldRoot = input.resolve("world")
        fs.createDirectories(worldRoot)

        // Root-level files (must be preserved as misc)
        fs.write(worldRoot.resolve("level.dat"), "level-data".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("players"))
        fs.write(worldRoot.resolve("players").resolve("player1.dat"), "player1".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("data"))
        fs.write(worldRoot.resolve("data").resolve("scores.dat"), "scores".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("datapacks"))
        fs.write(worldRoot.resolve("datapacks").resolve("custom.zip"), "datapack".toByteArray(Charsets.UTF_8))
        fs.write(worldRoot.resolve("session.lock"), "lock".toByteArray(Charsets.UTF_8))

        // Nested dimension: overworld (has region, entities, poi, and own data/)
        // MemoryFS requires each intermediate directory to be created explicitly
        val overworld = worldRoot.resolve("dimensions/minecraft/overworld")
        fs.createDirectories(overworld)
        fs.createDirectories(overworld.resolve("region"))
        fs.write(
            overworld.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.createDirectories(overworld.resolve("entities"))
        fs.write(
            overworld.resolve("entities").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.createDirectories(overworld.resolve("poi"))
        fs.write(
            overworld.resolve("poi").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.createDirectories(overworld.resolve("data"))
        fs.write(overworld.resolve("data").resolve("overworld_meta.dat"), "meta".toByteArray(Charsets.UTF_8))

        // Nested dimension: the_nether
        val nether = worldRoot.resolve("dimensions/minecraft/the_nether")
        fs.createDirectories(nether)
        fs.createDirectories(nether.resolve("region"))
        fs.write(
            nether.resolve("region").resolve("r.-1.-1.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )

        // Nested dimension: the_end
        val theEnd = worldRoot.resolve("dimensions/minecraft/the_end")
        fs.createDirectories(theEnd)
        fs.createDirectories(theEnd.resolve("region"))
        fs.write(
            theEnd.resolve("region").resolve("r.1.1.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )

        // Run backup
        val output = Paths.get("/mem/paper26-out")
        val request =
            OptimizerRequest(
                input = input,
                output = output,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                outputOptions = OutputOptions(zipOutput = false, force = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertTrue(report.processedChunks > 0, "should process chunks from all dimensions")
        assertTrue(report.errors.isEmpty(), "no errors expected")

        // Verify root-level misc files are copied to output
        assertTrue(fs.exists(output.resolve("world/level.dat")), "level.dat should be copied")
        assertTrue(fs.exists(output.resolve("world/players/player1.dat")), "players should be copied")
        assertTrue(fs.exists(output.resolve("world/data/scores.dat")), "data should be copied")
        assertTrue(fs.exists(output.resolve("world/datapacks/custom.zip")), "datapacks should be copied")
        assertTrue(fs.exists(output.resolve("world/session.lock")), "session.lock should be copied")

        // Verify dimension data files are copied
        assertTrue(
            fs.exists(output.resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca")),
            "overworld region should be copied",
        )
        assertTrue(
            fs.exists(output.resolve("world/dimensions/minecraft/overworld/entities/r.0.0.mca")),
            "overworld entities should be copied",
        )
        assertTrue(
            fs.exists(output.resolve("world/dimensions/minecraft/overworld/poi/r.0.0.mca")),
            "overworld poi should be copied",
        )
        assertTrue(
            fs.exists(output.resolve("world/dimensions/minecraft/overworld/data/overworld_meta.dat")),
            "overworld dimension-level misc should be copied",
        )
        assertTrue(
            fs.exists(output.resolve("world/dimensions/minecraft/the_nether/region/r.-1.-1.mca")),
            "nether region should be copied",
        )
        assertTrue(
            fs.exists(output.resolve("world/dimensions/minecraft/the_end/region/r.1.1.mca")),
            "end region should be copied",
        )

        // Verify dimension region dirs have NO overworld's root-level files
        assertFalse(
            fs.exists(output.resolve("world/dimensions/minecraft/overworld/level.dat")),
            "overworld should not contain world-root level.dat",
        )
    }

    @Test
    fun `old flat structure still works with no regression`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/flat-world")
        fs.createDirectories(input)

        // Old structure: dim IS the input root
        fs.createDirectories(input.resolve("region"))
        fs.write(
            input.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.write(input.resolve("misc.txt"), "misc-content".toByteArray(Charsets.UTF_8))
        fs.createDirectories(input.resolve("misc"))
        fs.write(input.resolve("misc/note.txt"), "note".toByteArray(Charsets.UTF_8))

        val output = Paths.get("/mem/flat-out")
        val request =
            OptimizerRequest(
                input = input,
                output = output,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                outputOptions = OutputOptions(force = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertTrue(report.processedChunks > 0, "should process chunks")
        assertTrue(report.errors.isEmpty(), "no errors expected")

        // Misc files preserved (old behavior)
        assertTrue(fs.exists(output.resolve("misc.txt")), "root misc file should be copied")
        assertTrue(fs.exists(output.resolve("misc/note.txt")), "nested misc should be copied")
        assertTrue(fs.exists(output.resolve("region/r.0.0.mca")), "region should be copied")
    }

    @Test
    fun `nested structure with dryRun does not write output`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/paper26-dryrun")
        fs.createDirectories(input)

        val worldRoot = input.resolve("world")
        fs.createDirectories(worldRoot)
        fs.write(worldRoot.resolve("level.dat"), "level-data".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("players"))
        fs.write(worldRoot.resolve("players").resolve("p.dat"), "p".toByteArray(Charsets.UTF_8))

        val overworld = worldRoot.resolve("dimensions/minecraft/overworld")
        fs.createDirectories(overworld)
        fs.createDirectories(overworld.resolve("region"))
        fs.write(
            overworld.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )

        val output = Paths.get("/mem/paper26-dryrun-out")
        val request =
            OptimizerRequest(
                input = input,
                output = output,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                outputOptions = OutputOptions(dryRun = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertTrue(report.processedChunks > 0, "should process chunks in dry run")

        // Nothing written
        assertFalse(fs.exists(output.resolve("world/level.dat")), "level.dat should NOT be written in dry run")
        assertFalse(
            fs.exists(output.resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca")),
            "region should NOT be written in dry run",
        )
        assertFalse(fs.exists(output.resolve("world/players/p.dat")), "players should NOT be written in dry run")
    }
}
