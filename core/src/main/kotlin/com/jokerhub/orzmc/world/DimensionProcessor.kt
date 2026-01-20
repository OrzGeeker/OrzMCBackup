package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.patterns.ChunkPattern
import java.nio.file.Path

data class DimensionResult(
    val processed: Long,
    val removed: Long
)

object DimensionProcessor {
    fun process(
        fs: FileSystem,
        ioFactory: McaIOFactory,
        inputDim: Path,
        targetDim: Path,
        patterns: List<ChunkPattern>,
        onError: (Path, String, String) -> Unit,
        onProgress: ((ProgressEvent) -> Unit)?,
        totalChunks: Long,
        progressInterval: Long,
        progressIntervalMs: Long,
        processedCounter: java.util.concurrent.atomic.AtomicLong,
        strict: Boolean
    ): DimensionResult {
        var removedTotal = 0L
        fs.createDirectories(targetDim)
        val regionDir = inputDim.resolve("region")
        val entitiesDir = inputDim.resolve("entities")
        val poiDir = inputDim.resolve("poi")
        fs.createDirectories(targetDim.resolve("region"))
        if (fs.isDirectory(entitiesDir)) fs.createDirectories(targetDim.resolve("entities"))
        if (fs.isDirectory(poiDir)) fs.createDirectories(targetDim.resolve("poi"))
        onProgress?.invoke(ProgressEvent(ProgressStage.DimensionStart, null, null, inputDim.toString(), null))
        val useTime = progressIntervalMs > 0
        var lastEmit = System.currentTimeMillis()
        fs.list(regionDir).filter { p -> p.toString().endsWith(".mca") }.forEach regionLoop@{ rf ->
            onProgress?.invoke(ProgressEvent(ProgressStage.RegionStart, null, null, rf.toString(), null))
            if (!McaUtils.isValidMca(fs, rf)) {
                onError(rf, "MCA", "MCA 文件损坏或不完整")
                if (!strict) return@regionLoop
            }
            val name = rf.fileName.toString()
            val cr = try {
                ioFactory.openReader(fs, rf)
            } catch (e: Exception) {
                onError(rf, "MCA", "无法读取 MCA 文件")
                return@regionLoop
            }
            val cw = ioFactory.createWriter(fs, targetDim.resolve("region").resolve(name))
            val efile = entitiesDir.resolve(name)
            val pfile = poiDir.resolve(name)
            val ew = if (java.nio.file.Files.isRegularFile(fs.toRealPath(efile)) && McaUtils.isValidMca(
                    fs,
                    efile
                )
            ) ioFactory.createWriter(fs, targetDim.resolve("entities").resolve(name)) else null
            val pw = if (java.nio.file.Files.isRegularFile(fs.toRealPath(pfile)) && McaUtils.isValidMca(
                    fs,
                    pfile
                )
            ) ioFactory.createWriter(fs, targetDim.resolve("poi").resolve(name)) else null
            var removed = 0L
            val entries = try {
                cr.entries()
            } catch (e: Exception) {
                onError(rf, "Entries", "读取区块条目失败")
                emptyList()
            }
            for (entry in entries) {
                var keep = false
                for (p in patterns) {
                    try {
                        if (p.matches(entry)) {
                            keep = true; break
                        }
                    } catch (e: Exception) {
                        onError(rf, "Pattern", "匹配模式失败")
                    }
                }
                if (keep) {
                    try {
                        cw.writeEntry(entry)
                    } catch (e: Exception) {
                        onError(rf, "Write", "写入条目失败")
                    }
                    try {
                        val er = if (java.nio.file.Files.isRegularFile(fs.toRealPath(efile)) && McaUtils.isValidMca(
                                fs,
                                efile
                            )
                        ) ioFactory.openReader(fs, efile) else null
                        val eentry = er?.get(entry.regionIndex())
                        if (eentry != null && ew != null) try {
                            ew.writeEntry(eentry)
                        } catch (e: Exception) {
                            onError(efile, "WriteEntities", "写入实体条目失败")
                        }
                    } catch (e: Exception) {
                        onError(efile, "Entities", "读取实体失败")
                    }
                    try {
                        val pr = if (java.nio.file.Files.isRegularFile(fs.toRealPath(pfile)) && McaUtils.isValidMca(
                                fs,
                                pfile
                            )
                        ) ioFactory.openReader(fs, pfile) else null
                        val pentry = pr?.get(entry.regionIndex())
                        if (pentry != null && pw != null) try {
                            pw.writeEntry(pentry)
                        } catch (e: Exception) {
                            onError(pfile, "WritePoi", "写入 POI 条目失败")
                        }
                    } catch (e: Exception) {
                        onError(pfile, "Poi", "读取 POI 失败")
                    }
                } else {
                    removed += 1
                    removedTotal += 1
                }
                val processed = processedCounter.incrementAndGet()
                if (useTime) {
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= progressIntervalMs) {
                        onProgress?.invoke(
                            ProgressEvent(
                                ProgressStage.ChunkProgress,
                                processed,
                                totalChunks,
                                rf.toString(),
                                null
                            )
                        )
                        lastEmit = now
                    }
                } else if (progressInterval > 0 && processed % progressInterval == 0L) {
                    onProgress?.invoke(
                        ProgressEvent(
                            ProgressStage.ChunkProgress,
                            processed,
                            totalChunks,
                            rf.toString(),
                            null
                        )
                    )
                }
            }
            try {
                cw.finalizeFile()
            } catch (e: Exception) {
                onError(rf, "Finalize", "完成写入失败")
            }
            try {
                ew?.finalizeFile()
            } catch (e: Exception) {
                onError(efile, "FinalizeEntities", "完成实体写入失败")
            }
            try {
                pw?.finalizeFile()
            } catch (e: Exception) {
                onError(pfile, "FinalizePoi", "完成 POI 写入失败")
            }
        }
        onProgress?.invoke(ProgressEvent(ProgressStage.DimensionEnd, null, null, inputDim.toString(), null))
        return DimensionResult(processedCounter.get(), removedTotal)
    }
}
