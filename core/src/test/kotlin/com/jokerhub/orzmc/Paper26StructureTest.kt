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

    /**
     * Simulate the real old Paper (pre-26.1) server root layout with three
     * separate world directories — overworld at `world/`, nether inside
     * `world_nether/DIM-1/`, and end inside `world_the_end/DIM1/`.
     *
     * This is the structure documented in the Paper 26.1 migration report
     * and represents how servers looked before the Vanilla-format migration.
     */
    @Test
    fun `old paper multi world dir layout discovers all dimensions and preserves misc`() {
        val fs = MemoryFS()
        val serverRoot = Paths.get("/mem/old-paper-server")
        fs.createDirectories(serverRoot)

        // world/ — overworld (region/entities/poi at root)
        val world = serverRoot.resolve("world")
        fs.createDirectories(world)
        fs.write(world.resolve("level.dat"), "level-data".toByteArray(Charsets.UTF_8))
        fs.write(world.resolve("paper-world.yml"), "paper-config".toByteArray(Charsets.UTF_8))
        fs.createDirectories(world.resolve("playerdata"))
        fs.write(world.resolve("playerdata").resolve("player1.dat"), "player1".toByteArray(Charsets.UTF_8))
        fs.createDirectories(world.resolve("stats"))
        fs.write(world.resolve("stats").resolve("player1.json"), "stats1".toByteArray(Charsets.UTF_8))
        fs.createDirectories(world.resolve("region"))
        fs.write(
            world.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.createDirectories(world.resolve("entities"))
        fs.write(
            world.resolve("entities").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.createDirectories(world.resolve("poi"))
        fs.write(
            world.resolve("poi").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        // Overworld dimension data/
        fs.createDirectories(world.resolve("data"))
        fs.write(world.resolve("data").resolve("idcounts.dat"), "idcounts".toByteArray(Charsets.UTF_8))

        // world_nether/DIM-1/ — nether (DIM-1 inside world_nether)
        val worldNether = serverRoot.resolve("world_nether")
        fs.createDirectories(worldNether)
        val dimNeg1 = worldNether.resolve("DIM-1")
        fs.createDirectories(dimNeg1)
        fs.createDirectories(dimNeg1.resolve("region"))
        fs.write(
            dimNeg1.resolve("region").resolve("r.-1.-1.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.createDirectories(dimNeg1.resolve("entities"))
        fs.write(
            dimNeg1.resolve("entities").resolve("r.-1.-1.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        // Nether dimension data/
        fs.createDirectories(dimNeg1.resolve("data"))
        fs.write(dimNeg1.resolve("data").resolve("Fortress_index.dat"), "fortress".toByteArray(Charsets.UTF_8))
        fs.write(dimNeg1.resolve("data").resolve("raids_nether.dat"), "raids-nether".toByteArray(Charsets.UTF_8))

        // world_the_end/DIM1/ — end (DIM1 inside world_the_end)
        val worldTheEnd = serverRoot.resolve("world_the_end")
        fs.createDirectories(worldTheEnd)
        val dim1 = worldTheEnd.resolve("DIM1")
        fs.createDirectories(dim1)
        fs.createDirectories(dim1.resolve("region"))
        fs.write(
            dim1.resolve("region").resolve("r.1.1.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.createDirectories(dim1.resolve("poi"))
        fs.write(
            dim1.resolve("poi").resolve("r.1.1.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        // End dimension data/
        fs.createDirectories(dim1.resolve("data"))
        fs.write(dim1.resolve("data").resolve("EndCity_index.dat"), "endcity".toByteArray(Charsets.UTF_8))
        fs.write(dim1.resolve("data").resolve("raids_end.dat"), "raids-end".toByteArray(Charsets.UTF_8))

        val output = Paths.get("/mem/old-paper-out")
        val request =
            OptimizerRequest(
                input = serverRoot,
                output = output,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                outputOptions = OutputOptions(force = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertTrue(report.processedChunks > 0, "should process chunks from all 3 dimensions")
        assertTrue(report.errors.isEmpty(), "no errors expected")

        // Overworld dimension region
        assertTrue(
            fs.exists(output.resolve("world/region/r.0.0.mca")),
            "overworld region should be in output",
        )
        assertTrue(
            fs.exists(output.resolve("world/entities/r.0.0.mca")),
            "overworld entities should be in output",
        )

        // Overworld misc files (playerdata, stats, paper-world.yml)
        assertTrue(
            fs.exists(output.resolve("world/level.dat")),
            "world/level.dat should be copied as misc",
        )
        assertTrue(
            fs.exists(output.resolve("world/playerdata/player1.dat")),
            "world/playerdata should be copied",
        )
        assertTrue(
            fs.exists(output.resolve("world/stats/player1.json")),
            "world/stats should be copied",
        )
        assertTrue(
            fs.exists(output.resolve("world/paper-world.yml")),
            "world/paper-world.yml should be copied",
        )

        // Nether dimension
        assertTrue(
            fs.exists(output.resolve("world_nether/DIM-1/region/r.-1.-1.mca")),
            "nether region should be in output (preserving world_nether/DIM-1 layout)",
        )
        assertTrue(
            fs.exists(output.resolve("world_nether/DIM-1/entities/r.-1.-1.mca")),
            "nether entities should be in output",
        )

        // End dimension
        assertTrue(
            fs.exists(output.resolve("world_the_end/DIM1/region/r.1.1.mca")),
            "end region should be in output (preserving world_the_end/DIM1 layout)",
        )
        assertTrue(
            fs.exists(output.resolve("world_the_end/DIM1/poi/r.1.1.mca")),
            "end poi should be in output",
        )

        // Verify dimension-level data files are preserved
        assertTrue(
            fs.exists(output.resolve("world_nether/DIM-1/data/Fortress_index.dat")),
            "nether dimension data/Fortress_index.dat should be preserved",
        )
        assertTrue(
            fs.exists(output.resolve("world_nether/DIM-1/data/raids_nether.dat")),
            "nether dimension data/raids_nether.dat should be preserved",
        )
        assertTrue(
            fs.exists(output.resolve("world_the_end/DIM1/data/EndCity_index.dat")),
            "end dimension data/EndCity_index.dat should be preserved",
        )
        assertTrue(
            fs.exists(output.resolve("world_the_end/DIM1/data/raids_end.dat")),
            "end dimension data/raids_end.dat should be preserved",
        )
        // Overworld dimension-level data
        assertTrue(
            fs.exists(output.resolve("world/data/idcounts.dat")),
            "overworld data/idcounts.dat should be preserved",
        )

        // Verify dimension dirs don't contain overworld misc
        assertFalse(
            fs.exists(output.resolve("world_nether/DIM-1/level.dat")),
            "nether dimension should not contain overworld level.dat",
        )
        assertFalse(
            fs.exists(output.resolve("world_the_end/DIM1/level.dat")),
            "end dimension should not contain overworld level.dat",
        )
    }

    @Test
    fun `nested 26 dot 1 structure works with inPlace mode`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/paper26-inplace")
        fs.createDirectories(input)

        val worldRoot = input.resolve("world")
        fs.createDirectories(worldRoot)
        fs.write(worldRoot.resolve("level.dat"), "level-data".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("players"))
        fs.write(worldRoot.resolve("players").resolve("p.dat"), "p".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("data"))
        fs.write(worldRoot.resolve("data").resolve("misc.dat"), "misc".toByteArray(Charsets.UTF_8))

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

        val nether = worldRoot.resolve("dimensions/minecraft/the_nether")
        fs.createDirectories(nether)
        fs.createDirectories(nether.resolve("region"))
        fs.write(
            nether.resolve("region").resolve("r.-1.-1.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )

        val request =
            OptimizerRequest(
                input = input,
                // inPlace doesn't need output path
                output = null,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                outputOptions = OutputOptions(inPlace = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertTrue(report.processedChunks > 0, "should process chunks in inPlace mode")
        assertTrue(report.errors.isEmpty(), "no errors expected")

        // After inPlace replacement, overworld region should still exist at original location
        assertTrue(
            fs.exists(input.resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca")),
            "overworld region should exist after inPlace replacement",
        )
        assertTrue(
            fs.exists(input.resolve("world/dimensions/minecraft/the_nether/region/r.-1.-1.mca")),
            "nether region should exist after inPlace replacement",
        )

        // Root-level misc files should remain untouched by inPlace (they stay in input)
        assertTrue(
            fs.exists(input.resolve("world/level.dat")),
            "world-level level.dat should remain after inPlace",
        )
        assertTrue(
            fs.exists(input.resolve("world/players/p.dat")),
            "players should remain after inPlace",
        )
    }

    /**
     * Custom dimension namespaces (non-minecraft) are supported because dimension
     * discovery is name-agnostic — it walks for any directory containing a
     * `region/` subdirectory. Datapacks and mods can add dimensions under their
     * own namespace (e.g. `dimensions/my_datapack/custom_dim/`).
     */
    @Test
    fun `custom dimension namespace is discovered and processed`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/custom-ns")
        fs.createDirectories(input)

        val worldRoot = input.resolve("world")
        fs.createDirectories(worldRoot)
        fs.write(worldRoot.resolve("level.dat"), "level-data".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("players"))
        fs.write(worldRoot.resolve("players").resolve("p.dat"), "p".toByteArray(Charsets.UTF_8))

        // Standard minecraft namespace — overworld
        val overworld = worldRoot.resolve("dimensions/minecraft/overworld")
        fs.createDirectories(overworld)
        fs.createDirectories(overworld.resolve("region"))
        fs.write(
            overworld.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )

        // Non-minecraft namespace — custom datapack dimension
        val customDim = worldRoot.resolve("dimensions/my_datapack/custom_dimension")
        fs.createDirectories(customDim)
        fs.createDirectories(customDim.resolve("region"))
        fs.write(
            customDim.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.createDirectories(customDim.resolve("entities"))
        fs.write(
            customDim.resolve("entities").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        fs.createDirectories(customDim.resolve("data"))
        fs.createDirectories(customDim.resolve("data").resolve("my_datapack"))
        fs.write(
            customDim.resolve("data").resolve("my_datapack").resolve("custom_state.dat"),
            "custom".toByteArray(Charsets.UTF_8),
        )

        val output = Paths.get("/mem/custom-ns-out")
        val request =
            OptimizerRequest(
                input = input,
                output = output,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                outputOptions = OutputOptions(force = true),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertTrue(report.processedChunks > 0, "should process chunks from both namespaces")
        assertTrue(report.errors.isEmpty(), "no errors expected")

        // Custom dimension region/entities should be copied
        assertTrue(
            fs.exists(output.resolve("world/dimensions/my_datapack/custom_dimension/region/r.0.0.mca")),
            "custom namespace dimension region should be copied",
        )
        assertTrue(
            fs.exists(output.resolve("world/dimensions/my_datapack/custom_dimension/entities/r.0.0.mca")),
            "custom namespace dimension entities should be copied",
        )
        val miscDataPath =
            output.resolve(
                "world/dimensions/my_datapack/custom_dimension/data/my_datapack/custom_state.dat",
            )
        assertTrue(
            fs.exists(miscDataPath),
            "custom namespace dimension misc files should be copied",
        )

        // Root misc files still copied
        assertTrue(
            fs.exists(output.resolve("world/level.dat")),
            "root level.dat should be copied",
        )
        assertTrue(
            fs.exists(output.resolve("world/players/p.dat")),
            "players should be copied",
        )
    }

    @Test
    fun `copyMisc false skips misc files for nested 26 dot 1 structure`() {
        val fs = MemoryFS()
        val input = Paths.get("/mem/paper26-nomisc")
        fs.createDirectories(input)

        val worldRoot = input.resolve("world")
        fs.createDirectories(worldRoot)
        fs.write(worldRoot.resolve("level.dat"), "level-data".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("players"))
        fs.write(worldRoot.resolve("players").resolve("p.dat"), "p".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("data"))
        fs.write(worldRoot.resolve("data").resolve("extra.dat"), "extra".toByteArray(Charsets.UTF_8))
        fs.createDirectories(worldRoot.resolve("generated"))
        fs.write(worldRoot.resolve("generated").resolve("struct.nbt"), "nbt".toByteArray(Charsets.UTF_8))

        val overworld = worldRoot.resolve("dimensions/minecraft/overworld")
        fs.createDirectories(overworld)
        fs.createDirectories(overworld.resolve("region"))
        fs.write(
            overworld.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )

        val output = Paths.get("/mem/paper26-nomisc-out")
        val request =
            OptimizerRequest(
                input = input,
                output = output,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                outputOptions = OutputOptions(force = true, copyMisc = false),
                io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
            )
        val report = Optimizer.run(request)
        assertTrue(report.processedChunks > 0, "should process chunks")
        assertTrue(report.errors.isEmpty(), "no errors expected")

        // Dimension region should be copied
        assertTrue(
            fs.exists(output.resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca")),
            "region should be copied even when copyMisc=false",
        )

        // Misc files should NOT be copied
        assertFalse(
            fs.exists(output.resolve("world/level.dat")),
            "level.dat should NOT be copied when copyMisc=false",
        )
        assertFalse(
            fs.exists(output.resolve("world/players/p.dat")),
            "players should NOT be copied when copyMisc=false",
        )
        assertFalse(
            fs.exists(output.resolve("world/data/extra.dat")),
            "data/extra.dat should NOT be copied when copyMisc=false",
        )
        assertFalse(
            fs.exists(output.resolve("world/generated/struct.nbt")),
            "generated/struct.nbt should NOT be copied when copyMisc=false",
        )
    }
}
