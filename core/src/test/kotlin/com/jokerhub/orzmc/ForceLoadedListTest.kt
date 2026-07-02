package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.patterns.ListPattern
import com.jokerhub.orzmc.util.TestPaths
import com.jokerhub.orzmc.world.ForceLoad
import com.jokerhub.orzmc.world.ForceLoadedParseException
import com.jokerhub.orzmc.world.NbtForceLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ForceLoadedListTest {
    @Test
    fun `forced coords should match some entries (legacy chunks_dat format)`() {
        val forced = NbtForceLoader.parse(TestPaths.worldDataChunks().toFile())
        val pattern = ListPattern(forced.map { it.first to it.second })
        val entries = McaReader.open(TestPaths.worldRegion("r.0.0.mca").toString()).use { it.entries() }
        val anyMatch = entries.any { pattern.matches(it) }
        assertTrue(anyMatch, "expected at least one entry to be forced-loaded")
    }

    @Test
    fun `forced coords in 26 dot 1 format chunk_tickets_dat`() {
        val ticketsFile = TestPaths.world26_1Dimension("overworld", "data/minecraft/chunk_tickets.dat")
        val forced = NbtForceLoader.parse(ticketsFile.toFile())
        assertEquals(4, forced.size, "expected 4 forced chunks from chunk_tickets.dat")
        assertTrue(forced.contains(0 to 0))
        assertTrue(forced.contains(0 to 1))
        assertTrue(forced.contains(1 to 0))
        assertTrue(forced.contains(1 to 1))
    }

    @Test
    fun `forced coords from 26 dot 1 format match entries in the region`() {
        val ticketsFile = TestPaths.world26_1Dimension("overworld", "data/minecraft/chunk_tickets.dat")
        val forced = NbtForceLoader.parse(ticketsFile.toFile())
        val pattern = ListPattern(forced.map { it.first to it.second })
        val entries =
            McaReader.open(
                TestPaths.world26_1Dimension("overworld", "region/r.0.0.mca").toString(),
            ).use { it.entries() }
        val anyMatch = entries.any { pattern.matches(it) }
        assertTrue(anyMatch, "expected at least one entry to match 26.1+ forced chunks")
    }

    @Test
    fun `ForceLoad parse resolves chunk_tickets_dat via dimension path probe chain`() {
        // Simulate the full resolution chain used by DefaultOptimizer.processSingleDimension:
        //   ForceLoad.parse(dimension, strict) → probes data/minecraft/chunk_tickets.dat → data/chunks.dat
        // The 26.1 fixture has BOTH files; the parser should use chunk_tickets.dat (higher priority).
        val dimPath = TestPaths.world26_1Dimension("overworld")
        val forced = ForceLoad.parse(dimPath, strict = false)
        assertEquals(4, forced.size, "should resolve chunk_tickets.dat via dimension path probe chain")
        assertTrue(forced.contains(0 to 0))
    }

    @Test
    fun `ForceLoad parse falls back to chunks_dat when chunk_tickets is absent`() {
        // The old fixture has only data/chunks.dat (no data/minecraft/chunk_tickets.dat).
        // ForceLoad.parse should fall back and still return results.
        val dimPath = TestPaths.world() // overworld dimension with data/chunks.dat
        val forced = ForceLoad.parse(dimPath, strict = false)
        assertTrue(forced.isNotEmpty(), "should resolve chunks.dat via fallback probe chain")
    }

    @Test
    fun `ForceLoad parse strict true throws on corrupted NBT file`() {
        // Create a fake chunks.dat that is not valid GZip NBT
        val tmpDir = Files.createTempDirectory("force-strict-")
        try {
            val dataDir = tmpDir.resolve("data")
            Files.createDirectories(dataDir)
            Files.write(dataDir.resolve("chunks.dat"), "not-valid-gzip-data".toByteArray(Charsets.UTF_8))

            assertThrows(ForceLoadedParseException::class.java) {
                ForceLoad.parse(tmpDir, strict = true)
            }
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ForceLoad parse strict false returns empty list on corrupted NBT file`() {
        val tmpDir = Files.createTempDirectory("force-nonstrict-")
        try {
            val dataDir = tmpDir.resolve("data")
            Files.createDirectories(dataDir)
            Files.write(dataDir.resolve("chunks.dat"), "not-valid-gzip-data".toByteArray(Charsets.UTF_8))

            val result = ForceLoad.parse(tmpDir, strict = false)
            assertTrue(result.isEmpty(), "non-strict mode should return empty list on parse failure")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
