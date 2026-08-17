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

        if (!fs.isDirectory(base) || !fs.isDirectory(patch)) {
            record(base, ERR_INPUT, "base/patch must be existing directories")
            return MergeReport(errors = errors)
        }
        val outDir =
            resolveOutputDir(fs, request, errors, { p, k, m -> record(p, k, m) }) ?: return MergeReport(errors = errors)
        emit(ProgressStage.Init, 0, 0, base, "starting merge")

        // 1. Copy the full backup to the output, so every chunk from the base is preserved.
        copyTree(fs, base, outDir, { p, k, m -> record(p, k, m) })

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
        )

        val lock = outDir.resolve("session.lock")
        if (fs.exists(lock)) fs.deleteIfExists(lock)
        emit(ProgressStage.Done, counters.done(), 0, outDir, "merge complete")
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
    ) {
        val reserved = setOf("region", "entities", "poi")
        val total = fs.walk(patch).count { it != patch && fs.isRegularFile(it) }.toLong()
        var done = 0L
        emit(ProgressStage.CopyMisc, done, total, patch, "overlaying patch")
        for (p in fs.walk(patch)) {
            if (p == patch || !fs.isRegularFile(p)) continue
            val rel = patch.relativize(p)
            val kind = if (rel.nameCount >= 2) rel.parent.fileName.toString() else null
            val isMcaUnderReserved = kind in reserved && rel.fileName.toString().endsWith(".mca")
            if (isMcaUnderReserved) {
                if (kind == "region") {
                    val name = rel.fileName.toString()
                    val baseFile = base.resolve(rel)
                    val outFile = out.resolve(rel)
                    if (fs.isRegularFile(baseFile)) {
                        mergeRegion(fs, ioFactory, baseFile, p, outFile, counters, record)
                    } else {
                        // Patch-only region: copy the region plus its entities/poi siblings.
                        copyFile(fs, p, outFile, counters, record, copied = true)
                        val dimRel = rel.parent.parent
                        if (dimRel != null) {
                            val entRel = dimRel.resolve("entities").resolve(name)
                            val poiRel = dimRel.resolve("poi").resolve(name)
                            if (fs.isRegularFile(patch.resolve(entRel))) {
                                copyFile(
                                    fs,
                                    patch.resolve(entRel),
                                    out.resolve(entRel),
                                    counters,
                                    record,
                                    copied = true,
                                )
                            }
                            if (fs.isRegularFile(patch.resolve(poiRel))) {
                                copyFile(
                                    fs,
                                    patch.resolve(poiRel),
                                    out.resolve(poiRel),
                                    counters,
                                    record,
                                    copied = true,
                                )
                            }
                        }
                    }
                }
                // entities/poi patch files are processed together with their region; skip here.
            } else {
                copyFile(fs, p, out.resolve(rel), counters, record, copied = false)
            }
            done += 1
            if (done % 1000L == 0L) {
                emit(ProgressStage.CopyMiscProgress, done, total, p, null)
            }
        }
        emit(ProgressStage.CopyMiscProgress, done, total, patch, null)
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

        val crb = openReader(fs, ioFactory, baseRegion, record, ERR_MCA)
        val crp = openReader(fs, ioFactory, patchRegion, record, ERR_MCA)
        if (crb == null || crp == null) {
            crb?.close()
            crp?.close()
            return
        }
        val erb = openReader(fs, ioFactory, baseDim.resolve("entities").resolve(name), record, ERR_ENTITIES)
        val erp = openReader(fs, ioFactory, patchDim.resolve("entities").resolve(name), record, ERR_ENTITIES)
        val prb = openReader(fs, ioFactory, baseDim.resolve("poi").resolve(name), record, ERR_POI)
        val prp = openReader(fs, ioFactory, patchDim.resolve("poi").resolve(name), record, ERR_POI)

        val ew = WriterHolder()
        val pw = WriterHolder()
        var cw: McaWriterLike? = null
        try {
            for (i in 0 until 1024) {
                val pe = crp.get(i)
                val be = crb.get(i)
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

    fun done(): Long =
        mergedRegions.get() +
            copiedFiles.get() +
            overlayFiles.get() +
            patchSlots.get() +
            baseSlots.get() +
            linkedEntities.get() +
            linkedPoi.get()
}
