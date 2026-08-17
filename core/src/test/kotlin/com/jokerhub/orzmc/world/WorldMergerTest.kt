package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.McaMemoryBuilder.MemChunk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class WorldMergerTest {
    private val fs = MemoryFS()
    private val io = MemoryMcaIOFactory()
    private val base =
        java.nio.file.Paths
            .get("/mem/base")
    private val patch =
        java.nio.file.Paths
            .get("/mem/patch")
    private val out =
        java.nio.file.Paths
            .get("/mem/out")
    private val dim = "dimensions/minecraft/overworld"

    private fun writeMca(
        world: Path,
        kind: String,
        name: String,
        bytes: ByteArray,
    ) {
        fs.createDirectories(world)
        var cur = world
        for (seg in dim.split("/")) {
            cur = cur.resolve(seg)
            fs.createDirectories(cur)
        }
        val target = cur.resolve(kind).resolve(name)
        fs.createDirectories(target.parent!!)
        fs.write(target, bytes)
    }

    /** Decompressed NBT payload of a chunk slot, or null when the slot is empty/absent. */
    private fun readPayload(
        world: Path,
        kind: String,
        name: String,
        slot: Int,
    ): ByteArray? {
        val path = world.resolve(dim).resolve(kind).resolve(name)
        if (!fs.isRegularFile(path)) return null
        return io.openReader(fs, path).use { it.get(slot)?.allDataUncompressed() }
    }

    private fun entryCount(
        world: Path,
        kind: String,
        name: String,
    ): Int {
        val path = world.resolve(dim).resolve(kind).resolve(name)
        if (!fs.isRegularFile(path)) return 0
        return io.openReader(fs, path).use { it.entries().size }
    }

    private fun runMerge(): MergeReport =
        WorldMerger.run(
            MergeRequest(
                base = base,
                patch = patch,
                output = out,
                io = IOOptions(fs = fs, ioFactory = io),
            ),
        )

    private fun runMergeWith(
        force: Boolean = false,
        reportSink: ReportSink? = null,
    ): MergeReport =
        WorldMerger.run(
            MergeRequest(
                base = base,
                patch = patch,
                output = out,
                outputOptions = OutputOptions(force = force, copyMisc = true),
                hooks = Hooks(reportSink = reportSink),
                io = IOOptions(fs = fs, ioFactory = io),
            ),
        )

    private fun mca(vararg slots: Pair<Int, Long>): ByteArray =
        McaMemoryBuilder.buildMca(slots.map { (i, v) -> MemChunk(i, v, CompressionKind.RAW) })

    @Test
    fun `overlays patch chunks and fills pruned slots from base`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900, 1 to 100, 2 to 500))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000, 2 to 600))

        val report = runMerge()

        assertEquals(1, report.mergedRegions)
        assertEquals(2, report.patchSlots)
        assertEquals(1, report.baseSlots)
        assertEquals(0, report.errors.size)
        // slots 0 and 2 (patch) keep the newer InhabitedTime; slot 1 is filled from base
        assertEquals(1000, readInhabited(out, "region", "r.0.0.mca", 0))
        assertEquals(600, readInhabited(out, "region", "r.0.0.mca", 2))
        assertEquals(100, readInhabited(out, "region", "r.0.0.mca", 1))
    }

    @Test
    fun `entities lockstep drops base entities for patch-sourced slots`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900, 1 to 100))
        writeMca(base, "entities", "r.0.0.mca", mca(0 to 1, 1 to 1))
        // patch keeps slot 0 and has no entities file at all -> slot 0 entities must be empty
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))

        runMerge()

        assertNull(
            readPayload(out, "entities", "r.0.0.mca", 0),
            "stale base entities for patch-sourced slot must be dropped",
        )
        assertEquals(1, readInhabited(out, "entities", "r.0.0.mca", 1), "base entities for base-sourced slot are kept")
        assertEquals(1, entryCount(out, "entities", "r.0.0.mca"))
    }

    @Test
    fun `region only in base is copied unchanged`() {
        writeMca(base, "region", "r.1.1.mca", mca(5 to 50))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))

        runMerge()

        assertEquals(1, entryCount(out, "region", "r.1.1.mca"))
        assertEquals(50, readInhabited(out, "region", "r.1.1.mca", 5))
    }

    @Test
    fun `patch-only region and its entities are copied`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.2.2.mca", mca(0 to 1000))
        writeMca(patch, "entities", "r.2.2.mca", mca(0 to 1))

        runMerge()

        assertEquals(1, entryCount(out, "region", "r.2.2.mca"))
        assertEquals(1, entryCount(out, "entities", "r.2.2.mca"))
    }

    @Test
    fun `misc files are overlaid from patch and session lock is dropped`() {
        fs.createDirectories(base)
        fs.createDirectories(patch)
        fs.write(base.resolve("level.dat"), "OLD".toByteArray())
        fs.write(patch.resolve("level.dat"), "NEW".toByteArray())
        fs.write(base.resolve("session.lock"), ByteArray(0))
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))

        runMerge()

        assertArrayEquals("NEW".toByteArray(), fs.read(out.resolve("level.dat")))
        assertEquals(null, fs.read(out.resolve("session.lock")), "session.lock must not be present in output")
    }

    @Test
    fun `non-directory base or patch reports input error`() {
        fs.createDirectories(base)
        fs.createDirectories(patch)
        fs.write(patch.resolve("not-a-dir"), ByteArray(1))

        val report =
            WorldMerger.run(
                MergeRequest(
                    base = base,
                    patch = patch.resolve("not-a-dir"),
                    output = out,
                    io = IOOptions(fs = fs, ioFactory = io),
                ),
            )

        assertEquals(1, report.errors.size)
        assertEquals("Input", report.errors[0].kind)
    }

    @Test
    fun `non-empty output without force is rejected`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        fs.createDirectories(out)
        fs.write(out.resolve("keep.txt"), "x".toByteArray())

        val report = runMerge()

        assertEquals(1, report.errors.size)
        assertEquals("Output", report.errors[0].kind)
        assertArrayEquals("x".toByteArray(), fs.read(out.resolve("keep.txt"))!!)
    }

    @Test
    fun `force wipes non-empty output before merging`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        fs.createDirectories(out)
        fs.write(out.resolve("keep.txt"), "x".toByteArray())

        val report = runMergeWith(force = true)

        assertEquals(0, report.errors.size)
        assertNull(fs.read(out.resolve("keep.txt")), "stale output content must be wiped")
        assertEquals(1000, readInhabited(out, "region", "r.0.0.mca", 0))
    }

    @Test
    fun `patch-only region copies its region entities and poi siblings`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.2.2.mca", mca(0 to 1000))
        writeMca(patch, "entities", "r.2.2.mca", mca(0 to 1))
        writeMca(patch, "poi", "r.2.2.mca", mca(0 to 2))

        runMerge()

        assertEquals(1, entryCount(out, "region", "r.2.2.mca"))
        assertEquals(1, entryCount(out, "entities", "r.2.2.mca"))
        assertEquals(1, entryCount(out, "poi", "r.2.2.mca"))
    }

    @Test
    fun `patch-only region without entity or poi siblings copies region alone`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.3.3.mca", mca(0 to 1000))

        runMerge()

        assertEquals(1, entryCount(out, "region", "r.3.3.mca"))
        assertNull(fs.read(out.resolve(dim).resolve("entities").resolve("r.3.3.mca")))
        assertNull(fs.read(out.resolve(dim).resolve("poi").resolve("r.3.3.mca")))
    }

    @Test
    fun `stale base entities file is dropped when lockstep produces no entities`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(base, "entities", "r.0.0.mca", mca(0 to 1))
        // patch keeps the same slot but has no entities file -> the copied base entities
        // must be removed, not left pointing at old 08-12 entities.
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))

        runMerge()

        assertNull(
            fs.read(out.resolve(dim).resolve("entities").resolve("r.0.0.mca")),
            "stale entities file must be removed",
        )
    }

    @Test
    fun `report sink receives the merge report`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        val received = mutableListOf<OptimizeReport>()

        runMergeWith(
            reportSink =
                object : ReportSink {
                    override fun write(report: OptimizeReport) {
                        received.add(report)
                    }
                },
        )

        assertEquals(1, received.size)
        assertEquals(1, received[0].processedChunks)
        assertEquals(0, received[0].removedChunks)
    }

    private fun readInhabited(
        world: Path,
        kind: String,
        name: String,
        slot: Int,
    ): Long? {
        val payload = readPayload(world, kind, name, slot) ?: return null
        return ByteBuffer.wrap(payload, payload.size - 8, 8).order(ByteOrder.BIG_ENDIAN).long
    }
}
