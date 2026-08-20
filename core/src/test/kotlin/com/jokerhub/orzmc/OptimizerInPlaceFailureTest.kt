package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.McaMemoryBuilder.MemChunk
import com.jokerhub.orzmc.world.FileSystem
import com.jokerhub.orzmc.world.FilterOptions
import com.jokerhub.orzmc.world.IOOptions
import com.jokerhub.orzmc.world.McaIOFactory
import com.jokerhub.orzmc.world.McaReaderLike
import com.jokerhub.orzmc.world.McaWriterLike
import com.jokerhub.orzmc.world.MemoryFS
import com.jokerhub.orzmc.world.MemoryMcaIOFactory
import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.OptimizerRequest
import com.jokerhub.orzmc.world.OutputOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * T8: in-place replacement (Optimizer.handleInPlaceReplacement) was only ever exercised on
 * the happy path. These tests pin the contract (A6) that any IOException while creating the
 * target directory, copying files back into the input, or cleaning the temp directory is
 * *recorded* as an `OptimizeError` in the report (kind "InPlace") rather than thrown out of
 * [Optimizer.run] — matching the collect-don't-throw model of every other error path
 * (resolveOutputDir, copyMiscFiles, handleZipOutput). Under `--strict` the recorded error
 * surfaces as a non-zero exit. The success path removes stale (fully-pruned) regions while
 * replacing entities/poi in place.
 *
 * The failure tests wrap [MemoryFS] in a [FailingFileSystem], so the MCA io factory must be
 * [UnwrappingIOFactory] — [MemoryMcaIOFactory] casts the fs to [MemoryFS] directly, which a
 * decorator would break (same pattern as WorldMergerTest).
 */
class OptimizerInPlaceFailureTest {
    private val fs = MemoryFS()
    private val io = MemoryMcaIOFactory()
    private val world = Paths.get("/mem/inplace-world")

    private fun writeMca(
        dir: String,
        name: String,
        bytes: ByteArray,
    ) {
        val target = world.resolve(dir).resolve(name)
        fs.createDirectories(target.parent!!)
        fs.write(target, bytes)
    }

    private fun buildWorld() {
        // MemoryFS.createDirectories registers only the leaf node, so the world root itself
        // must be created explicitly (same pattern as MemoryParallelE2ETest).
        fs.createDirectories(world)
        // r.0.0: slot 0 pruned (inhabited 0), slot 1 kept.
        writeMca(
            "region",
            "r.0.0.mca",
            McaMemoryBuilder.buildMca(
                listOf(
                    MemChunk(index = 0, inhabited = 0, kind = CompressionKind.RAW),
                    MemChunk(index = 1, inhabited = 100_000, kind = CompressionKind.RAW),
                ),
            ),
        )
        // r.1.0: fully pruned -> the output drops the region, so in-place must delete it as stale.
        writeMca(
            "region",
            "r.1.0.mca",
            McaMemoryBuilder.buildMca(listOf(MemChunk(index = 0, inhabited = 0, kind = CompressionKind.RAW))),
        )
        // entities/poi for the kept slot, to be replaced back into the input.
        writeMca(
            "entities",
            "r.0.0.mca",
            McaMemoryBuilder.buildMca(listOf(MemChunk(index = 1, inhabited = 1, kind = CompressionKind.RAW))),
        )
        writeMca(
            "poi",
            "r.0.0.mca",
            McaMemoryBuilder.buildMca(listOf(MemChunk(index = 1, inhabited = 1, kind = CompressionKind.RAW))),
        )
    }

    private fun slotCount(path: Path): Int {
        val header = fs.read(path)!!.copyOfRange(0, 4096)
        var n = 0
        for (i in 0 until 1024) {
            val o0 = header[i * 4]
            val o1 = header[i * 4 + 1]
            val o2 = header[i * 4 + 2]
            if (o0 != 0.toByte() || o1 != 0.toByte() || o2 != 0.toByte()) n++
        }
        return n
    }

    private fun runInPlace(
        filesystem: FileSystem = fs,
        ioFactory: McaIOFactory = io,
    ) = Optimizer.run(
        OptimizerRequest(
            input = world,
            output = null,
            filter = FilterOptions(inhabitedThresholdSeconds = 1000),
            outputOptions = OutputOptions(inPlace = true),
            io = IOOptions(fs = filesystem, ioFactory = ioFactory),
        ),
    )

    private fun assertInPlaceError(
        report: com.jokerhub.orzmc.world.OptimizeReport,
        fragment: String,
    ) {
        assertTrue(report.errors.isNotEmpty(), "expected a recorded InPlace error, got: $report")
        assertTrue(
            report.errors.any { it.kind == "InPlace" && it.message.contains(fragment) },
            "expected an InPlace error containing '$fragment', got: ${report.errors}",
        )
    }

    @Test
    fun `successful in-place run removes stale regions and replaces entities and poi`() {
        buildWorld()

        val report = runInPlace()

        // r.0.0's pruned slot 0 + the fully-pruned r.1.0 chunk.
        assertEquals(2, report.removedChunks)
        // r.0.0 keeps slot 1: 2 -> 1 slots proves the in-place rewrite.
        assertEquals(1, slotCount(world.resolve("region").resolve("r.0.0.mca")))
        assertFalse(
            fs.isRegularFile(world.resolve("region").resolve("r.1.0.mca")),
            "fully-pruned region must be removed as stale",
        )
        assertTrue(fs.isRegularFile(world.resolve("entities").resolve("r.0.0.mca")), "entities must be replaced back")
        assertTrue(fs.isRegularFile(world.resolve("poi").resolve("r.0.0.mca")), "poi must be replaced back")
    }

    @Test
    fun `copy failure during replacement is recorded in report errors`() {
        buildWorld()
        val failing = FailingFileSystem(fs, "copy")

        val report = runInPlace(failing, UnwrappingIOFactory())

        // A6: collect-don't-throw — the run completes and reports the failure structurally.
        assertInPlaceError(report, "copy")
        // The surviving region is untouched by the failed copy.
        assertEquals(2, slotCount(world.resolve("region").resolve("r.0.0.mca")))
    }

    @Test
    fun `cleanup failure after replacement is recorded in report errors`() {
        buildWorld()
        val failing = FailingFileSystem(fs, "deleteTreeWithRetry")

        val report = runInPlace(failing, UnwrappingIOFactory())

        assertInPlaceError(report, "clean")
        // Replacement happens before cleanup, so the input reflects the filtered output even
        // though the temp directory could not be removed.
        assertEquals(1, slotCount(world.resolve("region").resolve("r.0.0.mca")))
        assertFalse(fs.isRegularFile(world.resolve("region").resolve("r.1.0.mca")))
    }

    @Test
    fun `createDirectories failure for the input region is recorded in report errors`() {
        buildWorld()
        val failing = FailCreateDirectoriesFor(fs, world.resolve("region"))

        val report = runInPlace(failing, UnwrappingIOFactory())

        assertInPlaceError(report, "create")
        assertEquals(2, slotCount(world.resolve("region").resolve("r.0.0.mca")), "input must be left untouched")
    }
}

/** [FileSystem] wrapper that fails createDirectories only for one exact path. */
private class FailCreateDirectoriesFor(
    private val delegate: FileSystem,
    private val failFor: Path,
) : FileSystem by delegate {
    override fun createDirectories(path: Path) {
        if (path == failFor) throw IOException("injected createDirectories failure for $path")
        delegate.createDirectories(path)
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
