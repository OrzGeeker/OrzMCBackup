package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.mca.McaEntry
import com.jokerhub.orzmc.patterns.ChunkPattern
import java.nio.file.Path

data class DimensionResult(
    val processed: Long,
    val removed: Long,
)

private data class RegionResult(
    val processed: Long,
    val removed: Long,
)

internal object DimensionProcessor {
    private const val ERR_MCA = "MCA"
    private const val ERR_ENTITIES = "Entities"
    private const val ERR_POI = "Poi"
    private const val ERR_ENTRIES = "Entries"
    private const val ERR_PATTERN = "Pattern"
    private const val ERR_WRITE = "Write"
    private const val ERR_WRITE_ENTITIES = "WriteEntities"
    private const val ERR_WRITE_POI = "WritePoi"
    private const val ERR_FINALIZE = "Finalize"
    private const val ERR_FINALIZE_ENTITIES = "FinalizeEntities"
    private const val ERR_FINALIZE_POI = "FinalizePoi"

    fun process(
        fs: FileSystem,
        ioFactory: McaIOFactory,
        inputDim: Path,
        targetDim: Path,
        patterns: List<ChunkPattern>,
        onError: (Path, String, String) -> Unit,
        progress: ProgressTracker,
        strict: Boolean,
        regionParallelism: Int = 1,
        dryRun: Boolean = false,
        syncOnFinalize: Boolean = true,
    ): DimensionResult {
        var removedTotal = 0L
        fs.createDirectories(targetDim)
        val regionDir = inputDim.resolve("region")
        val entitiesDir = inputDim.resolve("entities")
        val poiDir = inputDim.resolve("poi")
        fs.createDirectories(targetDim.resolve("region"))
        if (fs.isDirectory(entitiesDir)) fs.createDirectories(targetDim.resolve("entities"))
        if (fs.isDirectory(poiDir)) fs.createDirectories(targetDim.resolve("poi"))
        progress.emit(ProgressStage.DimensionStart, null, null, inputDim, null)
        val useTime = progress.intervalMs > 0
        var lastEmit = System.currentTimeMillis()

        val regionFiles = fs.list(regionDir).filter { p -> p.toString().endsWith(".mca") }

        if (regionParallelism > 1 && regionFiles.size > 1) {
            // Parallel region processing
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(regionParallelism)
            try {
                val futures =
                    regionFiles.map { rf ->
                        executor.submit(
                            java.util.concurrent.Callable {
                                processSingleRegion(
                                    fs,
                                    ioFactory,
                                    inputDim,
                                    targetDim,
                                    patterns,
                                    onError,
                                    progress,
                                    strict,
                                    useTime,
                                    lastEmit,
                                    regionDir,
                                    entitiesDir,
                                    poiDir,
                                    rf,
                                    dryRun,
                                    syncOnFinalize,
                                )
                            },
                        )
                    }
                futures.forEach { f ->
                    try {
                        val r = f.get()
                        removedTotal += r.removed
                    } catch (e: Exception) {
                        onError(
                            inputDim,
                            "Parallel",
                            "Region parallel processing failed: ${e.message ?: "unknown error"}",
                        )
                    }
                }
            } finally {
                executor.shutdown()
            }
        } else {
            // Sequential region processing (original behavior)
            regionFiles.forEach { rf ->
                val r =
                    processSingleRegion(
                        fs,
                        ioFactory,
                        inputDim,
                        targetDim,
                        patterns,
                        onError,
                        progress,
                        strict,
                        useTime,
                        lastEmit,
                        regionDir,
                        entitiesDir,
                        poiDir,
                        rf,
                        dryRun,
                        syncOnFinalize,
                    )
                removedTotal += r.removed
            }
        }

        progress.emit(ProgressStage.DimensionEnd, null, null, inputDim, null)
        return DimensionResult(progress.processed.get(), removedTotal)
    }

    private fun processSingleRegion(
        fs: FileSystem,
        ioFactory: McaIOFactory,
        inputDim: Path,
        targetDim: Path,
        patterns: List<ChunkPattern>,
        onError: (Path, String, String) -> Unit,
        progress: ProgressTracker,
        strict: Boolean,
        useTime: Boolean,
        lastEmit: Long,
        regionDir: Path,
        entitiesDir: Path,
        poiDir: Path,
        rf: Path,
        dryRun: Boolean = false,
        syncOnFinalize: Boolean = true,
    ): RegionResult {
        var localRemoved = 0L
        var localLastEmit = lastEmit

        progress.emit(ProgressStage.RegionStart, null, null, rf, null)
        if (!McaUtils.isValidMca(fs, rf)) {
            onError(rf, ERR_MCA, "MCA file is corrupted or incomplete")
            if (!strict) return RegionResult(0, 0)
        }

        // pattern 匹配：任一 pattern 命中即保留；匹配异常（损坏 chunk 无法解析）时
        // 安全保留原始字节（避免备份丢数据），错误仅记录，不中断流程。
        fun shouldKeep(
            entry: McaEntry,
            patterns: List<ChunkPattern>,
            error: (Path, String, String) -> Unit,
        ): Boolean {
            for (p in patterns) {
                try {
                    if (p.matches(entry)) return true
                } catch (e: Exception) {
                    error(rf, ERR_PATTERN, "Pattern matching failed: ${e.message}；已保留原始区块")
                    return true
                }
            }
            return false
        }
        val name = rf.fileName.toString()
        val efile = entitiesDir.resolve(name)
        val pfile = poiDir.resolve(name)
        val cr =
            try {
                ioFactory.openReader(fs, rf)
            } catch (e: Exception) {
                onError(rf, ERR_MCA, "Failed to read MCA file: ${e.message}")
                return RegionResult(0, 0)
            }
        // Defer writer creation until after we know there are entries to keep.
        // This avoids writing empty MCA files when all chunks are removed.
        var cw: McaWriterLike? = null
        var ew: McaWriterLike? = null
        var pw: McaWriterLike? = null
        val er: McaReaderLike? =
            try {
                if (fs.isRegularFile(efile) && McaUtils.isValidMca(fs, efile)) {
                    ioFactory.openReader(fs, efile)
                } else {
                    null
                }
            } catch (e: Exception) {
                onError(efile, ERR_ENTITIES, "Failed to read entities: ${e.message}")
                null
            }
        val pr: McaReaderLike? =
            try {
                if (fs.isRegularFile(pfile) && McaUtils.isValidMca(fs, pfile)) {
                    ioFactory.openReader(fs, pfile)
                } else {
                    null
                }
            } catch (e: Exception) {
                onError(pfile, ERR_POI, "Failed to read POI: ${e.message}")
                null
            }
        try {
            val entries =
                try {
                    cr.entries()
                } catch (e: Exception) {
                    onError(rf, ERR_ENTRIES, "Failed to read chunk entries: ${e.message}")
                    emptyList()
                }
            for (entry in entries) {
                val keep = shouldKeep(entry, patterns) { path, kind, msg -> onError(path, kind, msg) }
                if (keep) {
                    // Lazily create writers only when at least one chunk is kept.
                    if (cw == null && !dryRun) {
                        cw =
                            ioFactory.createWriter(
                                fs,
                                targetDim.resolve("region").resolve(name),
                                syncOnFinalize,
                            )
                        if (er != null) {
                            ew =
                                ioFactory.createWriter(
                                    fs,
                                    targetDim.resolve("entities").resolve(name),
                                    syncOnFinalize,
                                )
                        }
                        if (pr != null) {
                            pw =
                                ioFactory.createWriter(
                                    fs,
                                    targetDim.resolve("poi").resolve(name),
                                    syncOnFinalize,
                                )
                        }
                    }
                    try {
                        cw?.writeEntry(entry)
                    } catch (
                        e: Exception,
                    ) {
                        onError(rf, ERR_WRITE, "Failed to write entry: ${e.message}")
                    }
                    try {
                        val eentry = er?.get(entry.regionIndex())
                        if (eentry != null && ew != null) {
                            try {
                                ew.writeEntry(eentry)
                            } catch (
                                e: Exception,
                            ) {
                                onError(efile, ERR_WRITE_ENTITIES, "Failed to write entity entry: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        onError(efile, ERR_ENTITIES, "Failed to read entities: ${e.message}")
                    }
                    try {
                        val pentry = pr?.get(entry.regionIndex())
                        if (pentry != null && pw != null) {
                            try {
                                pw.writeEntry(pentry)
                            } catch (
                                e: Exception,
                            ) {
                                onError(pfile, ERR_WRITE_POI, "Failed to write POI entry: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        onError(pfile, ERR_POI, "Failed to read POI: ${e.message}")
                    }
                } else {
                    localRemoved += 1
                }
                val processed = progress.processed.incrementAndGet()
                if (useTime) {
                    val now = System.currentTimeMillis()
                    if (now - localLastEmit >= progress.intervalMs) {
                        progress.emit(ProgressStage.ChunkProgress, processed, progress.total, rf, null)
                        localLastEmit = now
                    }
                } else if (progress.interval > 0 && processed % progress.interval == 0L) {
                    progress.emit(ProgressStage.ChunkProgress, processed, progress.total, rf, null)
                }
            }
            try {
                cw?.finalizeFile()
            } catch (
                e: Exception,
            ) {
                onError(rf, ERR_FINALIZE, "Failed to finalize write: ${e.message}")
            }
            try {
                ew?.finalizeFile()
            } catch (
                e: Exception,
            ) {
                onError(efile, ERR_FINALIZE_ENTITIES, "Failed to finalize entity write: ${e.message}")
            }
            try {
                pw?.finalizeFile()
            } catch (
                e: Exception,
            ) {
                onError(pfile, ERR_FINALIZE_POI, "Failed to finalize POI write: ${e.message}")
            }
        } finally {
            try {
                cr.close()
            } catch (_: Exception) {
            }
            try {
                cw?.close()
            } catch (_: Exception) {
            }
            try {
                er?.close()
            } catch (_: Exception) {
            }
            try {
                pr?.close()
            } catch (_: Exception) {
            }
            try {
                ew?.close()
            } catch (_: Exception) {
            }
            try {
                pw?.close()
            } catch (_: Exception) {
            }
        }

        return RegionResult(0, localRemoved)
    }
}
