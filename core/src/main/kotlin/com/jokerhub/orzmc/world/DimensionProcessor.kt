package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.mca.McaWriter
import com.jokerhub.orzmc.patterns.ChunkPattern
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class DimensionResult(
    val processed: Long,
    val removed: Long
)

object DimensionProcessor {
    fun process(
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
        Files.createDirectories(targetDim)
        val regionDir = inputDim.resolve("region")
        val entitiesDir = inputDim.resolve("entities")
        val poiDir = inputDim.resolve("poi")
        Files.createDirectories(targetDim.resolve("region"))
        if (Files.isDirectory(entitiesDir)) Files.createDirectories(targetDim.resolve("entities"))
        if (Files.isDirectory(poiDir)) Files.createDirectories(targetDim.resolve("poi"))
        onProgress?.invoke(ProgressEvent(ProgressStage.DimensionStart, null, null, inputDim.toString(), null))
        val useTime = progressIntervalMs > 0
        var lastEmit = System.currentTimeMillis()
        Files.list(regionDir).use { stream ->
            stream.filter { p -> p.toString().endsWith(".mca") }.forEach regionLoop@ { rf ->
                onProgress?.invoke(ProgressEvent(ProgressStage.RegionStart, null, null, rf.toString(), null))
                if (!McaUtils.isValidMca(rf)) {
                    onError(rf, "MCA", "MCA 文件损坏或不完整")
                    if (!strict) return@regionLoop
                }
                val name = rf.fileName.toString()
                val cr = try { McaReader.open(rf.toString()) } catch (e: Exception) {
                    onError(rf, "MCA", "无法读取 MCA 文件")
                    return@regionLoop
                }
                val cw = McaWriter(targetDim.resolve("region").resolve(name).toString())
                val efile = entitiesDir.resolve(name)
                val pfile = poiDir.resolve(name)
                val ew = if (Files.isRegularFile(efile) && McaUtils.isValidMca(efile)) McaWriter(targetDim.resolve("entities").resolve(name).toString()) else null
                val pw = if (Files.isRegularFile(pfile) && McaUtils.isValidMca(pfile)) McaWriter(targetDim.resolve("poi").resolve(name).toString()) else null
                var removed = 0L
                val entries = try { cr.entries() } catch (e: Exception) {
                    onError(rf, "Entries", "读取区块条目失败")
                    emptyList()
                }
                for (entry in entries) {
                    var keep = false
                    for (p in patterns) {
                        try {
                            if (p.matches(entry)) { keep = true; break }
                        } catch (e: Exception) {
                            onError(rf, "Pattern", "匹配模式失败")
                        }
                    }
                    if (keep) {
                        try { cw.writeEntry(entry) } catch (e: Exception) { onError(rf, "Write", "写入条目失败") }
                        try {
                            val er = if (Files.isRegularFile(efile) && McaUtils.isValidMca(efile)) McaReader.open(efile.toString()) else null
                            val eentry = er?.get(entry.regionIndex())
                            if (eentry != null && ew != null) try { ew.writeEntry(eentry) } catch (e: Exception) { onError(efile, "WriteEntities", "写入实体条目失败") }
                        } catch (e: Exception) {
                            onError(efile, "Entities", "读取实体失败")
                        }
                        try {
                            val pr = if (Files.isRegularFile(pfile) && McaUtils.isValidMca(pfile)) McaReader.open(pfile.toString()) else null
                            val pentry = pr?.get(entry.regionIndex())
                            if (pentry != null && pw != null) try { pw.writeEntry(pentry) } catch (e: Exception) { onError(pfile, "WritePoi", "写入 POI 条目失败") }
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
                            onProgress?.invoke(ProgressEvent(ProgressStage.ChunkProgress, processed, totalChunks, rf.toString(), null))
                            lastEmit = now
                        }
                    } else if (progressInterval > 0 && processed % progressInterval == 0L) {
                        onProgress?.invoke(ProgressEvent(ProgressStage.ChunkProgress, processed, totalChunks, rf.toString(), null))
                    }
                }
                try { cw.finalizeFile() } catch (e: Exception) { onError(rf, "Finalize", "完成写入失败") }
                try { ew?.finalizeFile() } catch (e: Exception) { onError(efile, "FinalizeEntities", "完成实体写入失败") }
                try { pw?.finalizeFile() } catch (e: Exception) { onError(pfile, "FinalizePoi", "完成 POI 写入失败") }
            }
        }
        onProgress?.invoke(ProgressEvent(ProgressStage.DimensionEnd, null, null, inputDim.toString(), null))
        return DimensionResult(processedCounter.get(), removedTotal)
    }
}
