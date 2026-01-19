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
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
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
        parallelism: Int = 1,
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
                record(Paths.get("<none>"), "Output", msg)
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
        val totalChunks = McaUtils.countTotalChunks(tasks)
        val processedChunksAtomic = java.util.concurrent.atomic.AtomicLong(0L)
        var removedTotal = 0L
        emit(ProgressStage.Discover, 0, totalChunks, input, "统计区块")

        if (parallelism <= 1) {
            tasks.forEach { dim ->
                val rel = input.relativize(dim)
                val targetDim = out.resolve(rel)
                val forced = try { ForceLoad.parse(dim, strict) } catch (e: ForceLoadedParseException) {
                    if (strict) record(dim, "ForceLoaded", e.message ?: "解析强制加载列表失败")
                    emptyList()
                }
                val patterns = listOf(
                    ListPattern(forced),
                    InhabitedTimePattern(ticks, removeUnknown)
                )
                val res = DimensionProcessor.process(
                    dim,
                    targetDim,
                    patterns,
                    { p, k, m -> record(p, k, m) },
                    onProgress,
                    totalChunks,
                    progressInterval,
                    progressIntervalMs,
                    processedChunksAtomic,
                    strict
                )
                removedTotal += res.removed
            }
        } else {
            val executor = java.util.concurrent.Executors.newFixedThreadPool(parallelism)
            val futures = mutableListOf<java.util.concurrent.Future<DimensionResult>>()
            tasks.forEach { dim ->
                val rel = input.relativize(dim)
                val targetDim = out.resolve(rel)
                val forced = try { ForceLoad.parse(dim, strict) } catch (e: ForceLoadedParseException) {
                    if (strict) record(dim, "ForceLoaded", e.message ?: "解析强制加载列表失败")
                    emptyList()
                }
                val patterns = listOf(
                    ListPattern(forced),
                    InhabitedTimePattern(ticks, removeUnknown)
                )
                val task = java.util.concurrent.Callable<DimensionResult> {
                        DimensionProcessor.process(
                        dim,
                        targetDim,
                        patterns,
                        { p, k, m -> record(p, k, m) },
                        onProgress,
                        totalChunks,
                        progressInterval,
                        progressIntervalMs,
                        processedChunksAtomic,
                        strict
                        )
                }
                futures.add(executor.submit(task))
            }
            futures.forEach { f ->
                try { removedTotal += f.get().removed } catch (_: Exception) { }
            }
            executor.shutdown()
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
                val ok = Cleaner.deleteTreeWithRetry(out, 5, 500)
                if (!ok) throw IOException("cleanup failed")
            } catch (e: IOException) {
                throw InPlaceReplacementException("清理临时目录失败：${out}", e)
            }
        } else {
            if (zipOutput) {
                try {
                    emit(ProgressStage.Compress, null, null, out, null)
                    Compressor.compressToTimestampZip(out)
                } catch (e: IOException) {
                    val msg = "压缩输出目录失败：${out}"
                    record(out, "Compress", msg)
                }
                try {
                    emit(ProgressStage.Cleanup, null, null, out, null)
                    val ok = Cleaner.deleteTreeWithRetry(out, 5, 500)
                    if (!ok) throw IOException("cleanup failed")
                } catch (e: IOException) {
                    val msg = "删除输出目录失败：${out}"
                    record(out, "Cleanup", msg)
                }
            }
        }
        emit(ProgressStage.Done, processedChunksAtomic.get(), totalChunks, input, null)
        return OptimizeReport(processedChunksAtomic.get(), removedTotal, errors)
    }

    private fun isDimensionDir(path: Path): Boolean = Files.isDirectory(path.resolve("region"))

    private fun discoverDimensions(root: Path): List<Path> {
        val tasks = mutableListOf<Path>()
        if (isDimensionDir(root)) tasks.add(root)
        Files.list(root).use { s -> s.filter { Files.isDirectory(it) && isDimensionDir(it) }.forEach { tasks.add(it) } }
        Files.walk(root).use { s -> s.filter { Files.isDirectory(it) && isDimensionDir(it) }.forEach { p -> if (!tasks.contains(p)) tasks.add(p) } }
        return tasks
    }

    private fun countTotalChunks(dims: List<Path>): Long = McaUtils.countTotalChunks(dims)
}
