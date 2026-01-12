package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.mca.McaWriter
import com.jokerhub.orzmc.patterns.ChunkPattern
import com.jokerhub.orzmc.patterns.InhabitedTimePattern
import com.jokerhub.orzmc.patterns.ListPattern
import com.jokerhub.orzmc.world.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.IOException

enum class ProgressMode { Off, Global, Region }

object Optimizer {
    @JvmStatic
    fun run(
        input: Path,
        output: Path?,
        inhabitedThresholdSeconds: Long,
        removeUnknown: Boolean,
        progressMode: ProgressMode = ProgressMode.Region,
        zipOutput: Boolean = false,
        inPlace: Boolean = false,
        force: Boolean = false,
        strict: Boolean = false,
        progressInterval: Long = 1000,
        progressIntervalMs: Long = 0,
        onError: ((OptimizeError) -> Unit)? = null,
        onProgress: ((ProgressEvent) -> Unit)? = null,
    ): OptimizeReport {
        return runWithReport(input, output, inhabitedThresholdSeconds, removeUnknown, progressMode, zipOutput, inPlace, force, strict, progressInterval, progressIntervalMs, onError, onProgress)
    }

    @JvmStatic
    fun runWithReport(
        input: Path,
        output: Path?,
        inhabitedThresholdSeconds: Long,
        removeUnknown: Boolean,
        progressMode: ProgressMode = ProgressMode.Region,
        zipOutput: Boolean = false,
        inPlace: Boolean = false,
        force: Boolean = false,
        strict: Boolean = false,
        progressInterval: Long = 1000,
        progressIntervalMs: Long = 0,
        onError: ((OptimizeError) -> Unit)? = null,
        onProgress: ((ProgressEvent) -> Unit)? = null,
    ): OptimizeReport {
        val errors = mutableListOf<OptimizeError>()
        fun record(path: Path, kind: String, msg: String) {
            val e = OptimizeError(path.toString(), kind, msg)
            onError?.invoke(e)
            errors.add(e)
        }
        fun emit(stage: ProgressStage, current: Long? = null, total: Long? = null, path: Path? = null, message: String? = null) {
            onProgress?.invoke(ProgressEvent(stage, current, total, path?.toString(), message))
        }
        val useTime = progressIntervalMs > 0
        var lastEmit = System.currentTimeMillis()
        if (!Files.isDirectory(input)) {
            val msg = "输入目录不存在或不是目录"
            record(input, "Input", msg)
            return OptimizeReport(processedChunks = 0, removedChunks = 0, errors = errors)
        }
        emit(ProgressStage.Init, 0, 0, input, "开始")
        val ticks = inhabitedThresholdSeconds * 20
        val out: Path = if (inPlace) {
            Files.createTempDirectory("thanos-")
        } else {
            if (output == null) {
                val msg = "非原地模式必须指定输出目录"
                record(Path.of("<none>"), "Output", msg)
                return OptimizeReport(processedChunks = 0, removedChunks = 0, errors = errors)
            }
            try {
                if (Files.exists(output)) {
                    val nonEmpty = Files.list(output).findFirst().isPresent
                    if (nonEmpty) {
                        if (force) {
                            Files.walk(output).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                            Files.createDirectories(output)
                        } else {
                            val msg = "输出目录已存在且非空，使用 --force 覆盖"
                            record(output, "Output", msg)
                            return OptimizeReport(processedChunks = 0, removedChunks = 0, errors = errors)
                        }
                    }
                } else {
                    Files.createDirectories(output)
                }
            } catch (e: IOException) {
                val msg = "输出目录不可写或访问受限：${output}"
                record(output, "Output", msg)
                return OptimizeReport(processedChunks = 0, removedChunks = 0, errors = errors)
            }
            output
        }
        emit(ProgressStage.Discover, null, null, input, "扫描维度")
        val tasks = discoverDimensions(input)
        val totalChunks = countTotalChunks(tasks)
        var processedChunks = 0L
        var removedTotal = 0L
        emit(ProgressStage.Discover, 0, totalChunks, input, "统计区块")

        tasks.forEach { dim ->
            emit(ProgressStage.DimensionStart, null, null, dim, null)
            val rel = input.relativize(dim)
            val targetDim = out.resolve(rel)
            Files.createDirectories(targetDim)
            val forced = try { parseForceLoaded(dim, strict) } catch (e: ForceLoadedParseException) {
                if (strict) record(dim, "ForceLoaded", e.message ?: "解析强制加载列表失败")
                emptyList()
            }
            val patterns: MutableList<ChunkPattern> = mutableListOf(
                ListPattern(forced),
                InhabitedTimePattern(ticks, removeUnknown)
            )
            val regionDir = dim.resolve("region")
            val entitiesDir = dim.resolve("entities")
            val poiDir = dim.resolve("poi")
            Files.createDirectories(targetDim.resolve("region"))
            if (Files.isDirectory(entitiesDir)) Files.createDirectories(targetDim.resolve("entities"))
            if (Files.isDirectory(poiDir)) Files.createDirectories(targetDim.resolve("poi"))

            Files.list(regionDir).use { stream ->
                stream.filter { p -> p.toString().endsWith(".mca") }.forEach regionLoop@ { rf ->
                    emit(ProgressStage.RegionStart, null, null, rf, null)
                    if (!isValidMca(rf)) {
                        record(rf, "MCA", "MCA 文件损坏或不完整")
                        if (!strict) return@regionLoop
                    }
                    val name = rf.fileName.toString()
                    val cr = try { McaReader.open(rf.toString()) } catch (e: Exception) {
                        record(rf, "MCA", "无法读取 MCA 文件")
                        return@regionLoop
                    }
                    val cw = McaWriter(targetDim.resolve("region").resolve(name).toString())
                    val efile = entitiesDir.resolve(name)
                    val pfile = poiDir.resolve(name)
                    val ew = if (Files.isRegularFile(efile) && isValidMca(efile)) McaWriter(targetDim.resolve("entities").resolve(name).toString()) else null
                    val pw = if (Files.isRegularFile(pfile) && isValidMca(pfile)) McaWriter(targetDim.resolve("poi").resolve(name).toString()) else null

                    var removed = 0L
                    val entries = try { cr.entries() } catch (e: Exception) {
                        record(rf, "Entries", "读取区块条目失败")
                        emptyList()
                    }
                    for (entry in entries) {
                        var keep = false
                        for (p in patterns) {
                            try {
                                if (p.matches(entry)) { keep = true; break }
                            } catch (e: Exception) {
                                record(rf, "Pattern", "匹配模式失败")
                            }
                        }
                        if (keep) {
                            try { cw.writeEntry(entry) } catch (e: Exception) { record(rf, "Write", "写入条目失败") }
                            // entities
                            try {
                                val er = if (Files.isRegularFile(efile) && isValidMca(efile)) McaReader.open(efile.toString()) else null
                                val eentry = er?.get(entry.regionIndex())
                                if (eentry != null && ew != null) try { ew.writeEntry(eentry) } catch (e: Exception) { record(efile, "WriteEntities", "写入实体条目失败") }
                            } catch (e: Exception) {
                                record(efile, "Entities", "读取实体失败")
                            }
                            // poi
                            try {
                                val pr = if (Files.isRegularFile(pfile) && isValidMca(pfile)) McaReader.open(pfile.toString()) else null
                                val pentry = pr?.get(entry.regionIndex())
                                if (pentry != null && pw != null) try { pw.writeEntry(pentry) } catch (e: Exception) { record(pfile, "WritePoi", "写入 POI 条目失败") }
                            } catch (e: Exception) {
                                record(pfile, "Poi", "读取 POI 失败")
                            }
                        } else {
                            removed += 1
                            removedTotal += 1
                    }
                        processedChunks += 1
                        if (useTime) {
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= progressIntervalMs) {
                                emit(ProgressStage.ChunkProgress, processedChunks, totalChunks, rf, null)
                                lastEmit = now
                            }
                        } else if (progressInterval > 0 && processedChunks % progressInterval == 0L) {
                            emit(ProgressStage.ChunkProgress, processedChunks, totalChunks, rf, null)
                        }
                        if (progressMode == ProgressMode.Global) {
                            val pct = (processedChunks * 100 / (totalChunks.coerceAtLeast(1))).toInt()
                            if (processedChunks % 100 == 0L) println("进度: $pct% ($processedChunks/$totalChunks)")
                        }
                    }
                    try { cw.finalizeFile() } catch (e: Exception) { record(rf, "Finalize", "完成写入失败") }
                    try { ew?.finalizeFile() } catch (e: Exception) { record(efile, "FinalizeEntities", "完成实体写入失败") }
                    try { pw?.finalizeFile() } catch (e: Exception) { record(pfile, "FinalizePoi", "完成 POI 写入失败") }
                }
            }
            emit(ProgressStage.DimensionEnd, null, null, dim, null)
        }
        if (inPlace) {
            // in-place mode replacement
            tasks.forEach { dim ->
                val rel = input.relativize(dim)
                val outDim = out.resolve(rel)
                val inDim = input.resolve(rel)
                listOf("region", "entities", "poi").forEach dimLoop@ { name ->
                    val src = outDim.resolve(name)
                    if (!Files.isDirectory(src)) return@dimLoop
                    val dst = inDim.resolve(name)
                    try {
                        Files.createDirectories(dst)
                    } catch (e: IOException) {
                        throw InPlaceReplacementException("无法创建目标目录：${dst}", e)
                    }
                    val keep = HashSet<String>()
                    Files.list(src).use { s -> s.filter { it.toString().endsWith(".mca") }.forEach { keep.add(it.fileName.toString()) } }
                    if (Files.isDirectory(dst)) {
                        try {
                            Files.list(dst).use { s -> s.filter { it.toString().endsWith(".mca") }.forEach { p ->
                                if (!keep.contains(p.fileName.toString())) Files.deleteIfExists(p)
                            } }
                        } catch (e: IOException) {
                            throw InPlaceReplacementException("清理目标目录失败：${dst}", e)
                        }
                    }
                    try {
                        Files.list(src).use { s -> s.filter { it.toString().endsWith(".mca") }.forEach { p ->
                            val target = dst.resolve(p.fileName.toString())
                            Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                        } }
                    } catch (e: IOException) {
                        throw InPlaceReplacementException("复制文件到目标目录失败：${dst}", e)
                    }
                }
            }
            try {
                Files.walk(out).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            } catch (e: IOException) {
                throw InPlaceReplacementException("清理临时目录失败：${out}", e)
            }
        } else {
            if (zipOutput) {
                val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                val parent = out.parent ?: Path.of(".")
                val zipPath = parent.resolve("$ts.zip")
                try {
                    emit(ProgressStage.Compress, null, null, out, null)
                    ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
                        Files.walk(out).forEach { p ->
                            val rel = out.relativize(p)
                            if (rel.toString().isEmpty()) return@forEach
                            if (Files.isDirectory(p)) {
                                val name = rel.toString().trimEnd('/') + "/"
                                zos.putNextEntry(ZipEntry(name))
                                zos.closeEntry()
                            } else {
                                zos.putNextEntry(ZipEntry(rel.toString()))
                                Files.copy(p, zos)
                                zos.closeEntry()
                            }
                        }
                    }
                } catch (e: IOException) {
                    val msg = "压缩输出目录失败：${out}"
                    record(out, "Compress", msg)
                }
                try {
                    emit(ProgressStage.Cleanup, null, null, out, null)
                    Files.walk(out).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                } catch (e: IOException) {
                    val msg = "删除输出目录失败：${out}"
                    record(out, "Cleanup", msg)
                }
            }
        }
        emit(ProgressStage.Done, processedChunks, totalChunks, input, null)
        return OptimizeReport(processedChunks, removedTotal, errors)
    }

    private fun isDimensionDir(path: Path): Boolean = Files.isDirectory(path.resolve("region"))

    private fun discoverDimensions(root: Path): List<Path> {
        val tasks = mutableListOf<Path>()
        if (isDimensionDir(root)) tasks.add(root)
        Files.list(root).use { s -> s.filter { Files.isDirectory(it) && isDimensionDir(it) }.forEach { tasks.add(it) } }
        Files.walk(root).use { s -> s.filter { Files.isDirectory(it) && isDimensionDir(it) }.forEach { p -> if (!tasks.contains(p)) tasks.add(p) } }
        return tasks
    }

    private fun countTotalChunks(dims: List<Path>): Long {
        var total = 0L
        for (dim in dims) {
            val regionDir = dim.resolve("region")
            if (!Files.isDirectory(regionDir)) continue
            Files.list(regionDir).use { s ->
                s.filter { it.toString().endsWith(".mca") && isValidMca(it) }.forEach { p ->
                    try {
                        val r = McaReader.open(p.toString())
                        total += r.entries().size
                    } catch (_: Exception) { }
                }
            }
        }
        return total
    }

    private fun isValidMca(path: Path): Boolean {
        return try {
            Files.size(path) >= 8192
        } catch (_: Exception) {
            false
        }
    }

    private fun parseForceLoaded(dimension: Path, strict: Boolean): List<Pair<Int, Int>> {
        val f = dimension.resolve("data").resolve("chunks.dat").toFile()
        if (!f.isFile) return emptyList()
        return try { NbtForceLoader.parse(f) } catch (e: Exception) {
            if (strict) throw ForceLoadedParseException("解析强制加载列表失败：${f}", e) else emptyList()
        }
    }
}
