package com.jokerhub.orzmc.world

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Merges an OrzMCBackup-optimized world (region files that only contain chunks whose
 * InhabitedTime exceeded the threshold) back onto a full world backup, at chunk-slot
 * granularity. Every chunk that survived optimization is taken from the optimized
 * backup (it is the newer state); every pruned slot is filled from the full backup,
 * so no old data is ever lost. entities/poi follow the region merge decision per slot.
 */
data class MergeRequest(
    val base: Path,
    val patch: Path,
    val output: Path,
    val outputOptions: OutputOptions = OutputOptions(),
    val progress: ProgressOptions = ProgressOptions(),
    val runtime: RuntimeOptions = RuntimeOptions(),
    val hooks: Hooks = Hooks(),
    val io: IOOptions = IOOptions(),
)

data class MergeReport(
    val mergedRegions: Long = 0,
    val copiedFiles: Long = 0,
    val patchSlots: Long = 0,
    val baseSlots: Long = 0,
    val linkedEntities: Long = 0,
    val linkedPoi: Long = 0,
    val overlayFiles: Long = 0,
    val errors: List<OptimizeError> = emptyList(),
)

interface MergeEngine {
    fun run(request: MergeRequest): MergeReport
}

object WorldMerger {
    @JvmStatic
    fun run(request: MergeRequest): MergeReport = DefaultMerger.run(request)
}

object DefaultMerger : MergeEngine {
    private const val ERR_INPUT = "Input"
    private const val ERR_OUTPUT = "Output"
    private const val ERR_MCA = "MCA"
    private const val ERR_ENTITIES = "Entities"
    private const val ERR_POI = "Poi"
    private const val ERR_WRITE = "Write"
    private const val ERR_COPY = "Copy"
    private val RESERVED_KINDS = setOf("region", "entities", "poi")

    override fun run(request: MergeRequest): MergeReport {
        val fs = request.io.fs
        val base = request.base
        val patch = request.patch
        val out = request.output
        val errors = mutableListOf<OptimizeError>()
        val progressSink = request.progress.sink

        fun record(
            path: Path,
            kind: String,
            msg: String,
        ) {
            val e = OptimizeError(path.toString(), kind, msg)
            request.hooks.onError?.invoke(e)
            synchronized(errors) { errors.add(e) }
        }

        fun emit(
            stage: ProgressStage,
            cur: Long? = null,
            total: Long? = null,
            path: Path? = null,
            msg: String? = null,
        ) {
            progressSink.emit(ProgressEvent(stage, cur, total, path?.toString(), msg))
        }

        // Guard against aliasing before any write: an overlapping base/patch/output could
        // wipe or corrupt the source worlds (e.g. output == base with --force).
        if (
            OverlapGuard.overlaps(fs, base, out) ||
            OverlapGuard.overlaps(fs, patch, out) ||
            OverlapGuard.overlaps(fs, base, patch)
        ) {
            record(base, ERR_INPUT, "base, patch and output must be three distinct, non-overlapping directories")
            return MergeReport(errors = errors)
        }
        if (!fs.isDirectory(base) || !fs.isDirectory(patch)) {
            record(base, ERR_INPUT, "base/patch must be existing directories")
            return MergeReport(errors = errors)
        }
        val outDir =
            resolveOutputDir(fs, request, errors, { p, k, m -> record(p, k, m) }) ?: return MergeReport(errors = errors)
        emit(ProgressStage.Init, 0, 0, base, "starting merge")

        // 1. Copy the full backup to the output, so every chunk from the base is preserved.
        //    Skip base .mca files under region/entities/poi that the patch also has, since
        //    the overlay phase rewrites them anyway (avoids a wasted intermediate copy).
        val patchMcaRels = HashSet<Path>()
        val patchRegionRels = HashSet<Path>()
        for (p in fs.walk(patch)) {
            if (p == patch || !fs.isRegularFile(p) || p.fileName.toString() == "session.lock") continue
            val rel = patch.relativize(p)
            val kind = if (rel.nameCount >= 2) rel.parent.fileName.toString() else null
            if (kind in RESERVED_KINDS && rel.fileName.toString().endsWith(".mca")) {
                if (kind == "region") patchRegionRels.add(rel)
                patchMcaRels.add(rel)
            }
        }
        // Entities/poi are rewritten by mergeRegion only when the sibling region exists in
        // patch. An orphan patch entities/poi file (region absent) is skipped by the overlay,
        // so base's copy of that entities/poi file must NOT be skipped here or base data is lost.
        patchMcaRels.removeIf { rel ->
            val kind = rel.parent?.fileName?.toString()
            val regionSibling =
                rel.parent
                    ?.parent
                    ?.resolve("region")
                    ?.resolve(rel.fileName.toString())
            kind != "region" && regionSibling !in patchRegionRels
        }
        copyTree(fs, base, outDir, patchMcaRels, { p, k, m -> record(p, k, m) })

        // 2. Overlay the optimized backup: merge region/entities/poi at chunk-slot level,
        //    replace every other file with the (newer) patch copy.
        val counters = MergeCounters()
        overlayPatch(
            fs,
            request.io.ioFactory,
            base,
            patch,
            outDir,
            counters,
            { p, k, m -> record(p, k, m) },
            { s, c, t, p, m -> emit(s, c, t, p, m) },
            request.runtime.parallelism,
        )

        val lock = outDir.resolve("session.lock")
        if (fs.exists(lock)) fs.deleteIfExists(lock)
        emit(ProgressStage.Done, counters.mergedRegions.get(), 0, outDir, "merge complete")
        val report =
            MergeReport(
                mergedRegions = counters.mergedRegions.get(),
                copiedFiles = counters.copiedFiles.get(),
                patchSlots = counters.patchSlots.get(),
                baseSlots = counters.baseSlots.get(),
                linkedEntities = counters.linkedEntities.get(),
                linkedPoi = counters.linkedPoi.get(),
                overlayFiles = counters.overlayFiles.get(),
                errors = errors,
            )
        request.hooks.reportSink?.let { sink -> sink.write(MergeReportIO.toOptimizeReport(report)) }
        return report
    }

    private fun resolveOutputDir(
        fs: FileSystem,
        request: MergeRequest,
        errors: MutableList<OptimizeError>,
        record: (Path, String, String) -> Unit,
    ): Path? {
        val out = request.output
        if (fs.exists(out)) {
            if (fs.list(out).isNotEmpty()) {
                if (!request.outputOptions.force) {
                    record(
                        out,
                        ERR_OUTPUT,
                        "Output directory already exists and is not empty; use --force to overwrite",
                    )
                    return null
                }
                fs.walk(out).sortedByDescending { it.toString().length }.forEach { fs.deleteIfExists(it) }
            }
        }
        try {
            fs.createDirectories(out)
        } catch (e: Exception) {
            record(out, ERR_OUTPUT, "Output directory is not writable: ${e.message}")
            return null
        }
        return out
    }

    private fun copyTree(
        fs: FileSystem,
        src: Path,
        dst: Path,
        skipRels: Set<Path>,
        record: (Path, String, String) -> Unit,
    ) {
        try {
            fs.createDirectories(dst)
        } catch (e: Exception) {
            record(src, ERR_COPY, "Failed to create output directory: ${e.message}")
        }
        for (p in fs.walk(src)) {
            if (p == src) continue
            val target = dst.resolve(src.relativize(p))
            when {
                fs.isDirectory(p) ->
                    try {
                        fs.createDirectories(target)
                    } catch (e: Exception) {
                        record(p, ERR_COPY, "Failed to create directory: ${e.message}")
                    }
                p.fileName.toString() == "session.lock" -> Unit
                skipRels.contains(src.relativize(p)) -> Unit
                else ->
                    try {
                        fs.createDirectories(target.parent ?: dst)
                        fs.copy(p, target, true)
                    } catch (e: Exception) {
                        record(p, ERR_COPY, "Failed to copy file: ${e.message}")
                    }
            }
        }
    }

    private fun overlayPatch(
        fs: FileSystem,
        ioFactory: McaIOFactory,
        base: Path,
        patch: Path,
        out: Path,
        counters: MergeCounters,
        record: (Path, String, String) -> Unit,
        emit: (ProgressStage, Long?, Long?, Path?, String?) -> Unit,
        parallelism: Int,
    ) {
        val files =
            fs
                .walk(patch)
                .filter { it != patch && fs.isRegularFile(it) && it.fileName.toString() != "session.lock" }
        val total = files.size.toLong()
        emit(ProgressStage.CopyMisc, 0, total, patch, "overlaying patch")

        val regionFiles =
            files.filter { p ->
                val rel = patch.relativize(p)
                rel.parent?.fileName.toString() == "region" && rel.fileName.toString().endsWith(".mca")
            }
        val miscFiles = files.filter { p -> !regionFiles.contains(p) }
        // Region merging happens first; CopyMiscProgress reports only the misc overlay, so its
        // total is the misc count, otherwise the final percentage never reaches 100%.
        val miscTotal = miscFiles.size.toLong()

        fun processRegion(p: Path) {
            val rel = patch.relativize(p)
            val name = rel.fileName.toString()
            val baseFile = base.resolve(rel)
            val outFile = out.resolve(rel)
            if (fs.isRegularFile(baseFile)) {
                try {
                    mergeRegion(fs, ioFactory, baseFile, p, outFile, counters, record)
                } catch (e: Exception) {
                    record(p, ERR_MCA, "Failed to merge region file: ${e.message}")
                }
            } else {
                // Patch-only region: copy the region plus its entities/poi siblings.
                // rel is region/<name> in the flat layout or dimensions/.../region/<name>
                // in the nested layout; the parent of "region" is the dimension dir.
                copyFile(fs, p, outFile, counters, record, copied = true)
                val dimDir = rel.parent.parent ?: Path.of("")
                val entRel = dimDir.resolve("entities").resolve(name)
                val poiRel = dimDir.resolve("poi").resolve(name)
                if (fs.isRegularFile(patch.resolve(entRel))) {
                    copyFile(fs, patch.resolve(entRel), out.resolve(entRel), counters, record, copied = true)
                }
                if (fs.isRegularFile(patch.resolve(poiRel))) {
                    copyFile(fs, patch.resolve(poiRel), out.resolve(poiRel), counters, record, copied = true)
                }
            }
        }

        if (parallelism > 1 && regionFiles.size > 1) {
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(parallelism)
            try {
                val futures =
                    regionFiles.map { p ->
                        executor.submit(java.util.concurrent.Callable { processRegion(p) })
                    }
                futures.forEach { f ->
                    try {
                        f.get()
                    } catch (e: Exception) {
                        record(patch, "Parallel", "Region parallel processing failed: ${e.message ?: "unknown error"}")
                    }
                }
            } finally {
                executor.shutdown()
            }
        } else {
            regionFiles.forEach { processRegion(it) }
        }

        var done = 0L
        for (p in miscFiles) {
            val rel = patch.relativize(p)
            val kind = if (rel.nameCount >= 2) rel.parent.fileName.toString() else null
            val isMcaUnderReserved = kind in RESERVED_KINDS && rel.fileName.toString().endsWith(".mca")
            if (!isMcaUnderReserved) {
                copyFile(fs, p, out.resolve(rel), counters, record, copied = false)
            }
            done += 1
            if (done % 1000L == 0L) {
                emit(ProgressStage.CopyMiscProgress, done, miscTotal, p, null)
            }
        }
        emit(ProgressStage.CopyMiscProgress, done, miscTotal, patch, null)
    }

    private fun copyFile(
        fs: FileSystem,
        src: Path,
        dst: Path,
        counters: MergeCounters,
        record: (Path, String, String) -> Unit,
        copied: Boolean,
    ) {
        try {
            fs.createDirectories(dst.parent ?: dst)
            fs.copy(src, dst, true)
            if (copied) counters.copiedFiles.incrementAndGet() else counters.overlayFiles.incrementAndGet()
        } catch (e: Exception) {
            record(src, ERR_COPY, "Failed to copy file: ${e.message}")
        }
    }

    private fun mergeRegion(
        fs: FileSystem,
        ioFactory: McaIOFactory,
        baseRegion: Path,
        patchRegion: Path,
        outRegion: Path,
        counters: MergeCounters,
        record: (Path, String, String) -> Unit,
    ) {
        val name = outRegion.fileName.toString()
        val outDim = outRegion.parent.parent ?: return
        val baseDim = baseRegion.parent.parent ?: return
        val patchDim = patchRegion.parent.parent ?: return
        val outEntities = outDim.resolve("entities").resolve(name)
        val outPoi = outDim.resolve("poi").resolve(name)

        // base may lack an entities/poi directory entirely (base had no such files);
        // create the output parents so the lazy writers can place siblings there.
        try {
            fs.createDirectories(outRegion.parent ?: outDim)
            fs.createDirectories(outEntities.parent ?: outDim)
            fs.createDirectories(outPoi.parent ?: outDim)
        } catch (e: Exception) {
            record(outRegion, ERR_WRITE, "Failed to create output directories: ${e.message}")
            return
        }

        val crb = openReader(fs, ioFactory, baseRegion, record, ERR_MCA)
        val crp = openReader(fs, ioFactory, patchRegion, record, ERR_MCA)
        // Patch region must be readable. If it is corrupt, the base copy (already skipped by
        // copyTree for common files) must be restored byte-for-byte so nothing is dropped.
        if (crp == null) {
            crb?.close()
            copyBaseIfPresent(fs, baseRegion, outRegion, record, ERR_MCA)
            copyBaseIfPresent(fs, baseDim.resolve("entities").resolve(name), outEntities, record, ERR_ENTITIES)
            copyBaseIfPresent(fs, baseDim.resolve("poi").resolve(name), outPoi, record, ERR_POI)
            counters.mergedRegions.incrementAndGet()
            return
        }
        val erb =
            if (crb != null) {
                openReader(fs, ioFactory, baseDim.resolve("entities").resolve(name), record, ERR_ENTITIES)
            } else {
                null
            }
        val erp = openReader(fs, ioFactory, patchDim.resolve("entities").resolve(name), record, ERR_ENTITIES)
        val prb =
            if (crb != null) {
                openReader(fs, ioFactory, baseDim.resolve("poi").resolve(name), record, ERR_POI)
            } else {
                null
            }
        val prp = openReader(fs, ioFactory, patchDim.resolve("poi").resolve(name), record, ERR_POI)

        val ew = WriterHolder()
        val pw = WriterHolder()
        var cw: McaWriterLike? = null
        try {
            for (i in 0 until 1024) {
                val pe = crp.get(i)
                val be = if (crb != null) crb.get(i) else null
                when {
                    pe != null -> {
                        cw = cw ?: ioFactory.createWriter(fs, outRegion)
                        cw.writeEntry(pe)
                        counters.patchSlots.incrementAndGet()
                        erp?.get(i)?.let { e ->
                            ew.writer(fs, ioFactory, outEntities).writeEntry(e)
                            counters.linkedEntities.incrementAndGet()
                        }
                        prp?.get(i)?.let { e ->
                            pw.writer(fs, ioFactory, outPoi).writeEntry(e)
                            counters.linkedPoi.incrementAndGet()
                        }
                    }
                    be != null -> {
                        cw = cw ?: ioFactory.createWriter(fs, outRegion)
                        cw.writeEntry(be)
                        counters.baseSlots.incrementAndGet()
                        erb?.get(i)?.let { e ->
                            ew.writer(fs, ioFactory, outEntities).writeEntry(e)
                            counters.linkedEntities.incrementAndGet()
                        }
                        prb?.get(i)?.let { e ->
                            pw.writer(fs, ioFactory, outPoi).writeEntry(e)
                            counters.linkedPoi.incrementAndGet()
                        }
                    }
                }
            }
            try {
                cw?.finalizeFile()
                ew.writerOrNull()?.finalizeFile()
                pw.writerOrNull()?.finalizeFile()
            } catch (e: Exception) {
                record(outRegion, ERR_WRITE, "Failed to finalize write: ${e.message}")
            }
            // If the lockstep produced no entities/poi, drop the stale base copy left by copyTree.
            if (ew.writerOrNull() == null) fs.deleteIfExists(outEntities)
            if (pw.writerOrNull() == null) fs.deleteIfExists(outPoi)
            counters.mergedRegions.incrementAndGet()
        } finally {
            listOf(crb, crp, erb, erp, prb, prp).forEach { it?.close() }
            listOf(cw, ew.writerOrNull(), pw.writerOrNull()).forEach {
                try {
                    it?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun copyBaseIfPresent(
        fs: FileSystem,
        src: Path,
        dst: Path,
        record: (Path, String, String) -> Unit,
        kind: String,
    ) {
        if (!fs.isRegularFile(src)) return
        try {
            fs.createDirectories(dst.parent ?: dst)
            fs.copy(src, dst, true)
        } catch (e: Exception) {
            record(src, kind, "Failed to copy file: ${e.message}")
        }
    }

    private fun openReader(
        fs: FileSystem,
        ioFactory: McaIOFactory,
        path: Path,
        record: (Path, String, String) -> Unit,
        kind: String,
    ): McaReaderLike? =
        try {
            if (fs.isRegularFile(path) && McaUtils.isValidMca(fs, path)) {
                ioFactory.openReader(fs, path)
            } else {
                null
            }
        } catch (e: Exception) {
            record(path, kind, "Failed to read MCA file: ${e.message}")
            null
        }
}

/** Lazily-created MCA writer holder used for the entities/poi lockstep merge. */
private class WriterHolder {
    private var writer: McaWriterLike? = null

    fun writer(
        fs: FileSystem,
        ioFactory: McaIOFactory,
        path: Path,
    ): McaWriterLike {
        writer?.let { return it }
        val created = ioFactory.createWriter(fs, path)
        writer = created
        return created
    }

    fun writerOrNull(): McaWriterLike? = writer
}

private class MergeCounters {
    val mergedRegions = AtomicLong(0)
    val copiedFiles = AtomicLong(0)
    val patchSlots = AtomicLong(0)
    val baseSlots = AtomicLong(0)
    val linkedEntities = AtomicLong(0)
    val linkedPoi = AtomicLong(0)
    val overlayFiles = AtomicLong(0)
}
