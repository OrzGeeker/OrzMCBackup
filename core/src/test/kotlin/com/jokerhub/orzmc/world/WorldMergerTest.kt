package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.FailingFileSystem
import com.jokerhub.orzmc.mca.McaEntry
import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.McaMemoryBuilder.MemChunk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    /** Writes an MCA file in the legacy flat layout: <world>/<kind>/<name>. */
    private fun writeFlatMca(
        world: Path,
        kind: String,
        name: String,
        bytes: ByteArray,
    ) {
        fs.createDirectories(world)
        val target = world.resolve(kind).resolve(name)
        fs.createDirectories(target.parent!!)
        fs.write(target, bytes)
    }

    private fun entryCountAt(
        world: Path,
        kind: String,
        name: String,
    ): Int {
        val path = world.resolve(kind).resolve(name)
        if (!fs.isRegularFile(path)) return 0
        return io.openReader(fs, path).use { it.entries().size }
    }

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
    fun `base aliasing output is rejected`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))

        val report =
            WorldMerger.run(
                MergeRequest(
                    base = base,
                    patch = patch,
                    output = base,
                    io = IOOptions(fs = fs, ioFactory = io),
                ),
            )

        assertEquals(1, report.errors.size)
        assertEquals("Input", report.errors[0].kind)
        assertEquals(900, readInhabited(base, "region", "r.0.0.mca", 0), "base must be left untouched")
    }

    @Test
    fun `output nested inside base is rejected`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))

        val report =
            WorldMerger.run(
                MergeRequest(
                    base = base,
                    patch = patch,
                    output = base.resolve("sub").resolve("out"),
                    io = IOOptions(fs = fs, ioFactory = io),
                ),
            )

        assertEquals(1, report.errors.size)
        assertEquals("Input", report.errors[0].kind)
    }

    @Test
    fun `base aliasing patch is rejected`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))

        val report =
            WorldMerger.run(
                MergeRequest(
                    base = base,
                    patch = base,
                    output = out,
                    io = IOOptions(fs = fs, ioFactory = io),
                ),
            )

        assertEquals(1, report.errors.size)
        assertEquals("Input", report.errors[0].kind)
    }

    @Test
    fun `corrupt base region keeps every valid patch chunk`() {
        writeMca(base, "region", "r.0.0.mca", byteArrayOf(1, 2, 3, 4))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000, 2 to 600))

        val report = runMerge()

        assertEquals(0, report.errors.size)
        assertEquals(2, report.patchSlots)
        assertEquals(0, report.baseSlots)
        assertEquals(2, entryCount(out, "region", "r.0.0.mca"))
        assertEquals(1000, readInhabited(out, "region", "r.0.0.mca", 0))
        assertEquals(600, readInhabited(out, "region", "r.0.0.mca", 2))
    }

    @Test
    fun `entities file is written when base has no entities directory`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        writeMca(patch, "entities", "r.0.0.mca", mca(0 to 1))

        val report = runMerge()

        assertEquals(0, report.errors.size)
        assertEquals(1, entryCount(out, "entities", "r.0.0.mca"))
        assertEquals(1, readInhabited(out, "entities", "r.0.0.mca", 0))
    }

    @Test
    fun `nested and root session locks in patch are not overlaid`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        fs.write(patch.resolve("session.lock"), ByteArray(0))
        val nestedLock = patch.resolve(dim).resolve("session.lock")
        fs.createDirectories(nestedLock.parent!!)
        fs.write(nestedLock, ByteArray(0))

        runMerge()

        assertNull(fs.read(out.resolve("session.lock")), "root session.lock must not be overlaid")
        assertNull(fs.read(out.resolve(dim).resolve("session.lock")), "nested session.lock must not be overlaid")
    }

    @Test
    fun `flat layout patch-only region copies entities and poi siblings`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        writeFlatMca(patch, "region", "r.2.2.mca", mca(0 to 1000))
        writeFlatMca(patch, "entities", "r.2.2.mca", mca(0 to 1))
        writeFlatMca(patch, "poi", "r.2.2.mca", mca(0 to 2))

        runMerge()

        assertEquals(1, entryCountAt(out, "region", "r.2.2.mca"))
        assertEquals(1, entryCountAt(out, "entities", "r.2.2.mca"))
        assertEquals(1, entryCountAt(out, "poi", "r.2.2.mca"))
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

    private fun runMergeOn(
        fs: FileSystem = this.fs,
        ioFactory: McaIOFactory = this.io,
        progressSink: ProgressSink = NoopProgressSink,
        force: Boolean = false,
    ): MergeReport =
        WorldMerger.run(
            MergeRequest(
                base = base,
                patch = patch,
                output = out,
                outputOptions = OutputOptions(force = force),
                progress = ProgressOptions(sink = progressSink),
                io = IOOptions(fs = fs, ioFactory = ioFactory),
            ),
        )

    @Test
    fun `patch nested inside output is rejected`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))

        val report =
            WorldMerger.run(
                MergeRequest(
                    base = base,
                    patch = out.resolve("nested").resolve("patch"),
                    output = out,
                    io = IOOptions(fs = fs, ioFactory = io),
                ),
            )

        assertEquals(1, report.errors.size)
        assertEquals("Input", report.errors[0].kind)
    }

    @Test
    fun `corrupt patch region keeps base region and entities`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900, 1 to 100))
        writeMca(base, "entities", "r.0.0.mca", mca(0 to 1))
        writeMca(patch, "region", "r.0.0.mca", byteArrayOf(1, 2, 3, 4))

        val report = runMerge()

        assertEquals(0, report.errors.size)
        assertEquals(1, report.mergedRegions)
        assertEquals(2, entryCount(out, "region", "r.0.0.mca"))
        assertEquals(900, readInhabited(out, "region", "r.0.0.mca", 0))
        assertEquals(100, readInhabited(out, "region", "r.0.0.mca", 1))
        assertEquals(1, entryCount(out, "entities", "r.0.0.mca"))
    }

    @Test
    fun `output directory creation failure records output error`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        val failing = FailingFileSystem(fs, "createDirectories")

        val report = runMergeOn(fs = failing, ioFactory = UnwrappingIOFactory())

        assertEquals(1, report.errors.size)
        assertEquals("Output", report.errors[0].kind)
    }

    @Test
    fun `copy failure in copy tree records copy error and continues`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        fs.write(base.resolve("level.dat"), "BASE".toByteArray())
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        val failing = FailingFileSystem(fs, "copy")

        val report = runMergeOn(fs = failing, ioFactory = UnwrappingIOFactory())

        assertTrue(report.errors.any { it.kind == "Copy" })
        assertEquals(1, report.mergedRegions)
        assertEquals(1000, readInhabited(out, "region", "r.0.0.mca", 0))
    }

    @Test
    fun `finalize write failure records write error and merge completes`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))

        val report = runMergeOn(ioFactory = FailingFinalizeIOFactory())

        assertTrue(report.errors.any { it.kind == "Write" })
        assertEquals(1, report.mergedRegions)
    }

    @Test
    fun `open reader failure records mca error and keeps patch chunks`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        val baseRegion = base.resolve(dim).resolve("region").resolve("r.0.0.mca")

        val report = runMergeOn(ioFactory = ThrowingReaderIOFactory(baseRegion))

        assertTrue(report.errors.any { it.kind == "MCA" })
        assertEquals(1, report.patchSlots)
        assertEquals(0, report.baseSlots)
        assertEquals(1000, readInhabited(out, "region", "r.0.0.mca", 0))
    }

    @Test
    fun `progress sink receives init copy and done events exactly once`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        val events = mutableListOf<ProgressEvent>()

        val report = runMergeOn(progressSink = CallbackProgressSink { events.add(it) })

        assertEquals(0, report.errors.size)
        assertEquals(1, events.count { it.stage == ProgressStage.Init })
        assertEquals(1, events.count { it.stage == ProgressStage.CopyMisc })
        assertEquals(1, events.count { it.stage == ProgressStage.Done })
    }

    @Test
    fun `standalone patch entities and poi without region are ignored`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(patch, "entities", "r.9.9.mca", mca(0 to 1))
        writeMca(patch, "poi", "r.9.9.mca", mca(0 to 2))

        val report = runMerge()

        assertEquals(0, report.errors.size)
        assertNull(fs.read(out.resolve(dim).resolve("entities").resolve("r.9.9.mca")))
        assertNull(fs.read(out.resolve(dim).resolve("poi").resolve("r.9.9.mca")))
    }

    @Test
    fun `orphan patch entities without region keeps base region and entities`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900))
        writeMca(base, "entities", "r.0.0.mca", mca(0 to 5))
        writeMca(patch, "entities", "r.0.0.mca", mca(0 to 9))

        val report = runMerge()

        assertEquals(0, report.errors.size)
        assertEquals(900, readInhabited(out, "region", "r.0.0.mca", 0))
        // patch has no region r.0.0, so the region is base-only; its base entities must
        // survive even though patch carries an orphan entities file for the same name.
        assertEquals(1, entryCount(out, "entities", "r.0.0.mca"))
        assertEquals(5L, readInhabited(out, "entities", "r.0.0.mca", 0))
    }

    @Test
    fun `parallel merge produces identical slots`() {
        writeMca(base, "region", "r.0.0.mca", mca(0 to 900, 1 to 100))
        writeMca(base, "region", "r.1.1.mca", mca(3 to 50))
        writeMca(patch, "region", "r.0.0.mca", mca(0 to 1000))
        writeMca(patch, "region", "r.1.1.mca", mca(3 to 60))

        val report =
            WorldMerger.run(
                MergeRequest(
                    base = base,
                    patch = patch,
                    output = out,
                    runtime = RuntimeOptions(parallelism = 2),
                    io = IOOptions(fs = fs, ioFactory = io),
                ),
            )

        assertEquals(0, report.errors.size)
        assertEquals(2, report.mergedRegions)
        assertEquals(1000, readInhabited(out, "region", "r.0.0.mca", 0))
        assertEquals(100, readInhabited(out, "region", "r.0.0.mca", 1))
        assertEquals(60, readInhabited(out, "region", "r.1.1.mca", 3))
    }

    private fun writeDimMca(
        filesystem: FileSystem,
        world: Path,
        kind: String,
        name: String,
        bytes: ByteArray,
    ) {
        // Mirror writeMca: MemoryFS.createDirectories registers only the leaf node, so the
        // world root and every dimension segment must be created explicitly.
        filesystem.createDirectories(world)
        var cur = world
        for (seg in dim.split("/")) {
            cur = cur.resolve(seg)
            filesystem.createDirectories(cur)
        }
        val target = cur.resolve(kind).resolve(name)
        filesystem.createDirectories(target.parent!!)
        filesystem.write(target, bytes)
    }

    /** 8 regions with overlapping patch slots, base-only slots, sibling files, and a patch-only region. */
    private fun buildLargeWorld() {
        for (r in 0 until 8) {
            // base keeps slots 0,1,2 (distinct inhabited per region); patch overrides 0 and 2, prunes 1.
            writeDimMca(fs, base, "region", "r.$r.0.mca", mca(0 to (1000L + r), 1 to 100L, 2 to (500L - r)))
            writeDimMca(fs, base, "entities", "r.$r.0.mca", mca(0 to 1L, 1 to 2L))
            writeDimMca(fs, base, "poi", "r.$r.0.mca", mca(0 to 3L))
            writeDimMca(fs, patch, "region", "r.$r.0.mca", mca(0 to (2000L + r), 2 to (1500L - r)))
            writeDimMca(fs, patch, "entities", "r.$r.0.mca", mca(0 to 9L))
            writeDimMca(fs, patch, "poi", "r.$r.0.mca", mca(0 to 11L))
        }
        // A patch-only region carrying its full region/entities/poi sibling set.
        writeDimMca(fs, patch, "region", "r.9.9.mca", mca(0 to 3000L))
        writeDimMca(fs, patch, "entities", "r.9.9.mca", mca(0 to 5L))
        writeDimMca(fs, patch, "poi", "r.9.9.mca", mca(0 to 6L))
    }

    private fun runParallelMergeInto(
        output: Path,
        parallelism: Int,
    ): MergeReport =
        WorldMerger.run(
            MergeRequest(
                base = base,
                patch = patch,
                output = output,
                runtime = RuntimeOptions(parallelism = parallelism),
                io = IOOptions(fs = fs, ioFactory = io),
            ),
        )

    @Test
    fun `parallel merge is byte-identical across repeated runs and matches sequential`() {
        buildLargeWorld()
        val outP1 =
            java.nio.file.Paths
                .get("/mem/det-par-1")
        val outP2 =
            java.nio.file.Paths
                .get("/mem/det-par-2")
        val outSeq =
            java.nio.file.Paths
                .get("/mem/det-seq")

        assertEquals(0, runParallelMergeInto(outP1, 4).errors.size)
        assertEquals(0, runParallelMergeInto(outP2, 4).errors.size)
        assertEquals(0, runParallelMergeInto(outSeq, 1).errors.size)

        val regionNames = (0 until 8).map { "r.$it.0.mca" } + "r.9.9.mca"
        for (kind in listOf("region", "entities", "poi")) {
            for (name in regionNames) {
                val p1 = outP1.resolve(dim).resolve(kind).resolve(name)
                val p2 = outP2.resolve(dim).resolve(kind).resolve(name)
                val seq = outSeq.resolve(dim).resolve(kind).resolve(name)
                val a = requireNotNull(fs.read(p1)) { "$kind/$name missing in run 1" }
                val b = requireNotNull(fs.read(p2)) { "$kind/$name missing in run 2" }
                val s = requireNotNull(fs.read(seq)) { "$kind/$name missing in sequential" }
                assertArrayEquals(a, b, "parallel run 1 must equal parallel run 2 for $kind/$name")
                assertArrayEquals(s, a, "parallel must equal sequential for $kind/$name")
            }
        }
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

/** [McaIOFactory] that reaches the underlying [MemoryFS] through a [FailingFileSystem] wrapper. */
private class UnwrappingIOFactory : McaIOFactory {
    private val inner = MemoryMcaIOFactory()

    private fun unwrap(fs: FileSystem): FileSystem = if (fs is FailingFileSystem) fs.delegate else fs

    override fun openReader(
        fs: FileSystem,
        path: Path,
    ): McaReaderLike = inner.openReader(unwrap(fs), path)

    override fun createWriter(
        fs: FileSystem,
        path: Path,
        syncOnFinalize: Boolean,
    ): McaWriterLike = inner.createWriter(unwrap(fs), path, syncOnFinalize)
}

/** [McaIOFactory] whose writers throw on [McaWriterLike.finalizeFile], for write-error paths. */
private class FailingFinalizeIOFactory : McaIOFactory {
    private val inner = MemoryMcaIOFactory()

    override fun openReader(
        fs: FileSystem,
        path: Path,
    ): McaReaderLike = inner.openReader(fs, path)

    override fun createWriter(
        fs: FileSystem,
        path: Path,
        syncOnFinalize: Boolean,
    ): McaWriterLike {
        val delegate = inner.createWriter(fs, path, syncOnFinalize)
        return object : McaWriterLike {
            override fun writeEntry(entry: McaEntry) = delegate.writeEntry(entry)

            override fun finalizeFile() = throw java.io.IOException("injected finalize failure")

            override fun close() = delegate.close()
        }
    }
}

/** [McaIOFactory] whose reader throws for a specific path, for read-error paths. */
private class ThrowingReaderIOFactory(
    private val throwFor: Path,
) : McaIOFactory {
    private val inner = MemoryMcaIOFactory()

    override fun openReader(
        fs: FileSystem,
        path: Path,
    ): McaReaderLike {
        if (path == throwFor) throw java.io.IOException("injected read failure")
        return inner.openReader(fs, path)
    }

    override fun createWriter(
        fs: FileSystem,
        path: Path,
        syncOnFinalize: Boolean,
    ): McaWriterLike = inner.createWriter(fs, path, syncOnFinalize)
}
