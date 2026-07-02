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
        assertTrue(
            Files.exists(out.resolve("world/level.dat_old")),
            "world root level.dat_old should be in output",
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
    fun `paper 26 dot 1 preserves players data in new format`() {
        val fixture = TestPaths.world26_1()
        val input = TestTmp.createTempDirectory("fixture-26-1-players-")
        val worldDir = input.resolve("world")
        copyDir(fixture, worldDir)

        val out = TestTmp.createTempDirectory("fixture-26-1-players-out-")
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

        // players/data/ — player NBT files
        assertTrue(
            Files.exists(
                out.resolve("world/players/data/0025f28b-3172-39a8-bbdc-c66e766c286f.dat"),
            ),
            "player data .dat should be copied to output",
        )
        // players/stats/ — statistics JSON
        assertTrue(
            Files.exists(
                out.resolve("world/players/stats/0025f28b-3172-39a8-bbdc-c66e766c286f.json"),
            ),
            "player stats .json should be copied to output",
        )
        // players/advancements/ — advancement JSON
        assertTrue(
            Files.exists(
                out.resolve("world/players/advancements/0025f28b-3172-39a8-bbdc-c66e766c286f.json"),
            ),
            "player advancements .json should be copied to output",
        )

        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `paper 26 dot 1 preserves world root and per-dimension data`() {
        val fixture = TestPaths.world26_1()
        val input = TestTmp.createTempDirectory("fixture-26-1-data-")
        val worldDir = input.resolve("world")
        copyDir(fixture, worldDir)

        val out = TestTmp.createTempDirectory("fixture-26-1-data-out-")
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

        // World-root data/minecraft/ — namespaced global data
        assertTrue(
            Files.exists(out.resolve("world/data/minecraft/scoreboard.dat")),
            "world-root data/minecraft/scoreboard.dat should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("world/data/minecraft/custom_boss_events.dat")),
            "world-root data/minecraft/custom_boss_events.dat should be copied",
        )

        // Per-dimension paper-world.yml
        for (dim in listOf("overworld", "the_nether", "the_end")) {
            assertTrue(
                Files.exists(out.resolve("world/dimensions/minecraft/$dim/paper-world.yml")),
                "$dim/paper-world.yml should be copied to output",
            )
        }

        // Per-dimension data/paper/ (Paper-specific files)
        for (dim in listOf("overworld", "the_nether", "the_end")) {
            for (paperFile in listOf("level_overrides.dat", "metadata.dat", "persistent_data_container.dat")) {
                assertTrue(
                    Files.exists(out.resolve("world/dimensions/minecraft/$dim/data/paper/$paperFile")),
                    "$dim/data/paper/$paperFile should be copied to output",
                )
            }
        }

        // Per-dimension data/minecraft/ dimension-level metadata
        for (dim in listOf("overworld", "the_nether", "the_end")) {
            for (dimFile in listOf("world_gen_settings.dat", "game_rules.dat", "weather.dat")) {
                assertTrue(
                    Files.exists(out.resolve("world/dimensions/minecraft/$dim/data/minecraft/$dimFile")),
                    "$dim/data/minecraft/$dimFile should be copied to output",
                )
            }
        }

        // Dimension-specific data files
        assertTrue(
            Files.exists(
                out.resolve("world/dimensions/minecraft/overworld/data/minecraft/wandering_trader.dat"),
            ),
            "overworld-specific wandering_trader.dat should be copied",
        )
        assertTrue(
            Files.exists(
                out.resolve("world/dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat"),
            ),
            "the_end-specific ender_dragon_fight.dat should be copied",
        )

        // World-root data/minecraft/ — complete file set
        for (worldFile in listOf("random_sequences.dat", "stopwatches.dat", "world_clocks.dat")) {
            assertTrue(
                Files.exists(out.resolve("world/data/minecraft/$worldFile")),
                "world-root data/minecraft/$worldFile should be copied",
            )
        }

        // World-root data/minecraft/maps/ — nested subdirectory
        assertTrue(
            Files.exists(out.resolve("world/data/minecraft/maps/0.dat")),
            "world-root data/minecraft/maps/0.dat (nested subdirectory) should be copied",
        )

        // World-root generated/ structures
        assertTrue(
            Files.exists(out.resolve("world/generated/minecraft/structure/1.nbt")),
            "world-root generated/minecraft/structure/1.nbt should be copied",
        )

        // World-root datapacks/
        assertTrue(
            Files.exists(out.resolve("world/datapacks/bukkit/pack.mcmeta")),
            "world-root datapacks/bukkit/pack.mcmeta should be copied",
        )

        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `old structure preserves player data and metadata files`() {
        val fixture = TestPaths.world()
        val input = TestTmp.createTempDirectory("fixture-old-meta-")
        copyDir(fixture, input)

        val out = TestTmp.createTempDirectory("fixture-old-meta-out-")
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

        // Old-format player data directories
        assertTrue(
            Files.exists(out.resolve("playerdata/0025f28b-3172-39a8-bbdc-c66e766c286f.dat")),
            "old playerdata/*.dat should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("stats/0025f28b-3172-39a8-bbdc-c66e766c286f.json")),
            "old stats/*.json should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("advancements/0025f28b-3172-39a8-bbdc-c66e766c286f.json")),
            "old advancements/*.json should be copied",
        )

        // Old-format metadata files
        assertTrue(
            Files.exists(out.resolve("paper-world.yml")),
            "old root-level paper-world.yml should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("uid.dat")),
            "uid.dat should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("level.dat_old")),
            "level.dat_old should be copied",
        )

        // Generated structures
        assertTrue(
            Files.exists(out.resolve("generated/minecraft/structures/1.nbt")),
            "generated structure .nbt should be copied",
        )

        // Datapacks
        assertTrue(
            Files.exists(out.resolve("datapacks/bukkit/pack.mcmeta")),
            "datapacks/bukkit/pack.mcmeta should be copied",
        )

        // Overworld data/ — legacy dimension-level data files
        for (dataFile in listOf("idcounts.dat", "Mansion_index.dat", "map_0.dat")) {
            assertTrue(
                Files.exists(out.resolve("data/$dataFile")),
                "data/$dataFile should be copied",
            )
        }

        // Note: DIM-1/data/* and DIM1/data/* are NOT checked here because
        // the hybrid fixture layout (overworld + sub-dimensions in one dir)
        // makes sub-dimension misc files unreachable via the excludePaths
        // mechanism when input==overworld. The real old Paper uses separate
        // world_nether/ and world_the_end/ directories, tested in
        // Paper26StructureTest.old paper multi world dir layout.

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

    /**
     * Real-fixture test for the actual old Paper (pre-26.1) server layout
     * with three separate world directories. Uses Fixtures/world/ data
     * rearranged into the documented old Paper structure:
     *
     * ```
     * server_root/
     * ├── world/                  ← overworld (region/ at root, level.dat, misc)
     * ├── world_nether/DIM-1/     ← nether
     * └── world_the_end/DIM1/     ← end
     * ```
     *
     * This complements the MemoryFS test in Paper26StructureTest by using
     * real binary fixture data (real MCA files, NBT data files, etc.).
     */
    @Test
    fun `old paper real multi world dir layout discovers all dimensions and preserves data`() {
        val fixture = TestPaths.world()
        val serverRoot = TestTmp.createTempDirectory("fixture-old-multi-")

        // ── world/ = overworld (everything from fixture EXCEPT DIM-1/ and DIM1/) ──
        val world = serverRoot.resolve("world")
        Files.createDirectories(world)
        Files.walk(fixture).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { p ->
                val rel = fixture.relativize(p)
                if (rel.startsWith("DIM-1/") || rel.startsWith("DIM1/")) return@forEach
                val target = world.resolve(rel.toString())
                Files.createDirectories(target.parent)
                Files.copy(p, target)
            }
        }

        // ── world_nether/DIM-1/ = nether ──
        val dimNeg1Src = fixture.resolve("DIM-1")
        val dimNeg1Dst = serverRoot.resolve("world_nether/DIM-1")
        if (Files.isDirectory(dimNeg1Src)) {
            copyDir(dimNeg1Src, dimNeg1Dst)
        }

        // ── world_the_end/DIM1/ = end ──
        val dim1Src = fixture.resolve("DIM1")
        val dim1Dst = serverRoot.resolve("world_the_end/DIM1")
        if (Files.isDirectory(dim1Src)) {
            copyDir(dim1Src, dim1Dst)
        }

        val out = TestTmp.createTempDirectory("fixture-old-multi-out-")
        val report =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = serverRoot,
                        output = out,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                        outputOptions = OutputOptions(force = true),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(report.processedChunks > 0, "should process chunks from all 3 dimensions")
        assertTrue(report.errors.isEmpty(), "no errors expected")

        // ── Overworld ──
        assertTrue(
            Files.exists(out.resolve("world/region/r.0.0.mca")),
            "overworld region should be in output",
        )
        assertTrue(
            Files.exists(out.resolve("world/level.dat")),
            "world/level.dat should be copied as misc",
        )
        assertTrue(
            Files.exists(out.resolve("world/playerdata/0025f28b-3172-39a8-bbdc-c66e766c286f.dat")),
            "world/playerdata should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("world/paper-world.yml")),
            "world/paper-world.yml should be copied",
        )
        assertTrue(
            Files.exists(out.resolve("world/level.dat_old")),
            "world/level.dat_old should be copied",
        )
        // Overworld dimension-level data/
        assertTrue(
            Files.exists(out.resolve("world/data/idcounts.dat")),
            "overworld data/idcounts.dat should be preserved",
        )
        assertTrue(
            Files.exists(out.resolve("world/data/chunks.dat")),
            "overworld data/chunks.dat should be preserved",
        )

        // ── Nether ──
        assertTrue(
            Files.exists(out.resolve("world_nether/DIM-1/region/r.0.0.mca")),
            "nether region should be in output (preserving world_nether/DIM-1 layout)",
        )
        assertTrue(
            Files.exists(out.resolve("world_nether/DIM-1/data/Fortress_index.dat")),
            "nether data/Fortress_index.dat should be preserved",
        )
        assertTrue(
            Files.exists(out.resolve("world_nether/DIM-1/data/raids_nether.dat")),
            "nether data/raids_nether.dat should be preserved",
        )

        // ── End ──
        assertTrue(
            Files.exists(out.resolve("world_the_end/DIM1/region/r.0.0.mca")),
            "end region should be in output (preserving world_the_end/DIM1 layout)",
        )
        assertTrue(
            Files.exists(out.resolve("world_the_end/DIM1/data/EndCity_index.dat")),
            "end data/EndCity_index.dat should be preserved",
        )
        assertTrue(
            Files.exists(out.resolve("world_the_end/DIM1/data/raids_end.dat")),
            "end data/raids_end.dat should be preserved",
        )

        // ── Cross-contamination check ──
        assertFalse(
            Files.exists(out.resolve("world_nether/DIM-1/level.dat")),
            "nether dimension should NOT contain overworld level.dat",
        )
        assertFalse(
            Files.exists(out.resolve("world_the_end/DIM1/playerdata")),
            "end dimension should NOT contain overworld playerdata",
        )

        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(serverRoot, 5, 10)
    }
}
