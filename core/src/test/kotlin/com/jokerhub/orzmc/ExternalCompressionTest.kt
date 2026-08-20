package com.jokerhub.orzmc

import com.jokerhub.orzmc.patterns.InhabitedTimePattern
import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.FilterOptions
import com.jokerhub.orzmc.world.IOOptions
import com.jokerhub.orzmc.world.MemoryFS
import com.jokerhub.orzmc.world.MemoryMcaIOFactory
import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.OptimizerRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * T2: external-compression chunks (EXT_GZIP/EXT_ZLIB/EXT_RAW/EXT_LZ4) — the `.mcc`
 * external-storage marker bytes (-127..-124) — were previously impossible to build in
 * tests, leaving the `isExternal()` data-safety branch (keep/remove under `removeUnknown`)
 * uncovered. This pins that behavior at the pattern level and end-to-end through the
 * optimizer.
 */
class ExternalCompressionTest {
    private val extKinds =
        listOf(
            CompressionKind.EXT_GZIP,
            CompressionKind.EXT_ZLIB,
            CompressionKind.EXT_RAW,
            CompressionKind.EXT_LZ4,
        )

    private fun firstEntry(bytes: ByteArray): com.jokerhub.orzmc.mca.McaEntry {
        val fs = MemoryFS()
        val path = Paths.get("/mem/ext-world/region/r.0.0.mca")
        fs.createDirectories(path.parent)
        fs.write(path, bytes)
        return MemoryMcaIOFactory()
            .openReader(fs, path)
            .entries()
            .first()
    }

    @Test
    fun `all four external compression kinds are detected by isExternal`() {
        for (kind in extKinds) {
            val mca = McaMemoryBuilder.buildSingleEntryMca(0, 1000, kind)
            val entry = firstEntry(mca)
            assertTrue(entry.isExternal(), "expected $kind to be detected as external")
        }
    }

    @Test
    fun `in-region compression kinds are not external`() {
        for (kind in listOf(CompressionKind.RAW, CompressionKind.ZLIB, CompressionKind.GZIP, CompressionKind.LZ4)) {
            val mca = McaMemoryBuilder.buildSingleEntryMca(0, 1000, kind)
            val entry = firstEntry(mca)
            assertFalse(entry.isExternal(), "$kind must not be flagged external")
        }
    }

    @Test
    fun `removeUnknown true removes external chunks regardless of inhabited time`() {
        val pattern = InhabitedTimePattern(threshold = 0, removeUnknown = true)
        for (kind in extKinds) {
            val mca = McaMemoryBuilder.buildSingleEntryMca(0, inhabited = 1_000_000, kind)
            val entry = firstEntry(mca)
            assertFalse(
                pattern.matches(entry),
                "external $kind must be removed when removeUnknown=true even with high inhabited time",
            )
        }
    }

    @Test
    fun `removeUnknown false keeps external chunks`() {
        val pattern = InhabitedTimePattern(threshold = 0, removeUnknown = false)
        for (kind in extKinds) {
            val mca = McaMemoryBuilder.buildSingleEntryMca(0, inhabited = 1_000_000, kind)
            val entry = firstEntry(mca)
            assertTrue(
                pattern.matches(entry),
                "external $kind must be kept when removeUnknown=false",
            )
        }
    }

    @Test
    fun `optimizer removes external chunks under removeUnknown and keeps them otherwise`() {
        val fs = MemoryFS()
        val world = Paths.get("/mem/ext-world")
        // MemoryFS.createDirectories only registers the leaf node, so the input root itself
        // must be created explicitly (same pattern as MemoryParallelE2ETest).
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        fs.write(
            world.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, inhabited = 1_000_000, CompressionKind.EXT_ZLIB),
        )

        val removedReport =
            Optimizer.run(
                OptimizerRequest(
                    input = world,
                    output = Paths.get("/mem/ext-out-removed"),
                    filter = FilterOptions(inhabitedThresholdSeconds = 0, removeUnknown = true),
                    io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
                ),
            )
        assertEquals(1L, removedReport.removedChunks, "external chunk must be counted as removed")
        assertFalse(
            Files.exists(fs.toRealPath(Paths.get("/mem/ext-out-removed/region/r.0.0.mca"))),
            "fully removed external chunk must leave no output region file",
        )

        val keptReport =
            Optimizer.run(
                OptimizerRequest(
                    input = world,
                    output = Paths.get("/mem/ext-out-kept"),
                    filter = FilterOptions(inhabitedThresholdSeconds = 0, removeUnknown = false),
                    io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
                ),
            )
        assertEquals(1L, keptReport.processedChunks, "external chunk must be kept when removeUnknown=false")
        val keptPath = Paths.get("/mem/ext-out-kept/region/r.0.0.mca")
        val keptReal = fs.toRealPath(keptPath)
        assertTrue(Files.exists(keptReal), "kept external chunk must produce an output region")
        // Round-trip: the surviving chunk is still marked external, so a second run without
        // --remove-unknown would keep it again (the marker survives the rewrite).
        val keptEntry =
            MemoryMcaIOFactory()
                .openReader(fs, keptPath)
                .entries()
                .first()
        assertTrue(keptEntry.isExternal(), "external marker must survive the output rewrite")
    }
}
