package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.patterns.InhabitedTimePattern
import com.jokerhub.orzmc.patterns.ListPattern
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

enum class ProgressMode { Off, Global, Region }

object Optimizer {
    


    private fun isDimensionDir(fs: FileSystem, path: Path): Boolean = fs.isDirectory(path.resolve("region"))

    private fun discoverDimensions(fs: FileSystem, root: Path): List<Path> {
        val tasks = mutableListOf<Path>()
        if (isDimensionDir(fs, root)) tasks.add(root)
        fs.list(root).filter { fs.isDirectory(it) && isDimensionDir(fs, it) }.forEach { tasks.add(it) }
        fs.walk(root).filter { fs.isDirectory(it) && isDimensionDir(fs, it) }
            .forEach { p -> if (!tasks.contains(p)) tasks.add(p) }
        return tasks
    }

    private fun countTotalChunks(fs: FileSystem, dims: List<Path>): Long = McaUtils.countTotalChunks(fs, dims)

    @JvmStatic
    fun run(config: OptimizerConfig): OptimizeReport {
        val input = config.input
        val output = config.output
        val inhabitedThresholdSeconds = config.inhabitedThresholdSeconds
        val removeUnknown = config.removeUnknown
        val zipOutput = config.zipOutput
        val inPlace = config.inPlace
        val force = config.force
        val strict = config.strict
        val progressInterval = config.progressInterval
        val progressIntervalMs = config.progressIntervalMs
        val onError = config.onError
        val progressSink = config.progressSink ?: CallbackProgressSink(config.onProgress)
        val fs = config.fs
        val metrics = config.metricsSink ?: NoopMetricsSink()
        config.loggerSink
        val parallelism = config.parallelism
        val errors = mutableListOf<OptimizeError>()
        fun record(path: Path, kind: String, msg: String) {
            val e = OptimizeError(path.toString(), kind, msg)
            onError?.invoke(e)
            errors.add(e)
            metrics.recordError(e)
        }

        fun emit(
            stage: ProgressStage,
            current: Long? = null,
            total: Long? = null,
            path: Path? = null,
            message: String? = null
        ) {
            progressSink.emit(ProgressEvent(stage, current, total, path?.toString(), message))
        }

        progressIntervalMs > 0
        if (!fs.isDirectory(input)) {
            val msg = "输入目录不存在或不是目录"
            record(input, "Input", msg)
            return OptimizeReport(processedChunks = 0, removedChunks = 0, errors = errors)
        }
        emit(ProgressStage.Init, 0, 0, input, "开始")
        val ticks = inhabitedThresholdSeconds * 20
        val out: Path = if (inPlace) {
            fs.createTempDirectory("thanos-")
        } else {
            if (output == null) {
                val msg = "非原地模式必须指定输出目录"
                record(Paths.get("<none>"), "Output", msg)
                return OptimizeReport(processedChunks = 0, removedChunks = 0, errors = errors)
            }
            try {
                if (fs.exists(output)) {
                    val nonEmpty = fs.list(output).isNotEmpty()
                    if (nonEmpty) {
                        if (force) {
                            fs.walk(output).sortedByDescending { it.toString().length }
                                .forEach { fs.deleteIfExists(it) }
                            fs.createDirectories(output)
                        } else {
                            val msg = "输出目录已存在且非空，使用 --force 覆盖"
                            record(output, "Output", msg)
                            return OptimizeReport(processedChunks = 0, removedChunks = 0, errors = errors)
                        }
                    }
                } else {
                    fs.createDirectories(output)
                }
            } catch (e: IOException) {
                val msg = "输出目录不可写或访问受限：${output}"
                record(output, "Output", msg)
                return OptimizeReport(processedChunks = 0, removedChunks = 0, errors = errors)
            }
            output
        }
        emit(ProgressStage.Discover, null, null, input, "扫描维度")
        val tasks = discoverDimensions(fs, input)
        val totalChunks = McaUtils.countTotalChunks(fs, tasks)
        val processedChunksAtomic = java.util.concurrent.atomic.AtomicLong(0L)
        var removedTotal = 0L
        emit(ProgressStage.Discover, 0, totalChunks, input, "统计区块")

        val onProgressCb: (ProgressEvent) -> Unit = { e -> progressSink.emit(e) }
        val ioFactory = config.ioFactory
        if (parallelism <= 1) {
            tasks.forEach { dim ->
                val rel = input.relativize(dim)
                val targetDim = out.resolve(rel)
                val forced = try {
                    ForceLoad.parse(dim, strict)
                } catch (e: ForceLoadedParseException) {
                    if (strict) record(dim, "ForceLoaded", e.message ?: "解析强制加载列表失败")
                    emptyList()
                }
                val patterns = listOf(
                    ListPattern(forced),
                    InhabitedTimePattern(ticks, removeUnknown)
                )
                val res = DimensionProcessor.process(
                    fs,
                    ioFactory,
                    dim,
                    targetDim,
                    patterns,
                    { p, k, m -> record(p, k, m) },
                    onProgressCb,
                    totalChunks,
                    progressInterval,
                    progressIntervalMs,
                    processedChunksAtomic,
                    strict
                )
                removedTotal += res.removed
                metrics.incProcessed(res.processed)
                metrics.incRemoved(res.removed)
            }
        } else {
            val executor = java.util.concurrent.Executors.newFixedThreadPool(parallelism)
            val futures = mutableListOf<java.util.concurrent.Future<DimensionResult>>()
            tasks.forEach { dim ->
                val rel = input.relativize(dim)
                val targetDim = out.resolve(rel)
                val forced = try {
                    ForceLoad.parse(dim, strict)
                } catch (e: ForceLoadedParseException) {
                    if (strict) record(dim, "ForceLoaded", e.message ?: "解析强制加载列表失败")
                    emptyList()
                }
                val patterns = listOf(
                    ListPattern(forced),
                    InhabitedTimePattern(ticks, removeUnknown)
                )
                val task = java.util.concurrent.Callable<DimensionResult> {
                    DimensionProcessor.process(
                        fs,
                        ioFactory,
                        dim,
                        targetDim,
                        patterns,
                        { p, k, m -> record(p, k, m) },
                        onProgressCb,
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
                try {
                    val r = f.get()
                    removedTotal += r.removed
                    metrics.incProcessed(r.processed)
                    metrics.incRemoved(r.removed)
                } catch (_: Exception) {
                }
            }
            executor.shutdown()
        }
        if (inPlace) {
            tasks.forEach { dim ->
                val rel = input.relativize(dim)
                val outDim = out.resolve(rel)
                val inDim = input.resolve(rel)
                listOf("region", "entities", "poi").forEach dimLoop@{ name ->
                    val src = outDim.resolve(name)
                    if (!fs.isDirectory(src)) return@dimLoop
                    val dst = inDim.resolve(name)
                    try {
                        fs.createDirectories(dst)
                    } catch (e: IOException) {
                        throw InPlaceReplacementException("无法创建目标目录：${dst}", e)
                    }
                    val keep = HashSet<String>()
                    fs.list(src).filter { it.toString().endsWith(".mca") }.forEach { keep.add(it.fileName.toString()) }
                    if (fs.isDirectory(dst)) {
                        try {
                            fs.list(dst).filter { it.toString().endsWith(".mca") }.forEach { p ->
                                if (!keep.contains(p.fileName.toString())) fs.deleteIfExists(p)
                            }
                        } catch (e: IOException) {
                            throw InPlaceReplacementException("清理目标目录失败：${dst}", e)
                        }
                    }
                    try {
                        fs.list(src).filter { it.toString().endsWith(".mca") }.forEach { p ->
                            val target = dst.resolve(p.fileName.toString())
                            fs.copy(p, target, true)
                        }
                    } catch (e: IOException) {
                        throw InPlaceReplacementException("复制文件到目标目录失败：${dst}", e)
                    }
                }
            }
            try {
                val ok = fs.deleteTreeWithRetry(out, 5, 500)
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
                    val ok = fs.deleteTreeWithRetry(out, 5, 500)
                    if (!ok) throw IOException("cleanup failed")
                } catch (e: IOException) {
                    val msg = "删除输出目录失败：${out}"
                    record(out, "Cleanup", msg)
                }
            }
        }
        emit(ProgressStage.Done, processedChunksAtomic.get(), totalChunks, input, null)
        val report = OptimizeReport(processedChunksAtomic.get(), removedTotal, errors)
        config.reportSink?.write(report)
        metrics.incProcessed(report.processedChunks)
        metrics.incRemoved(report.removedChunks)
        return report
    }

    @JvmStatic


    suspend fun runSuspend(config: OptimizerConfig): OptimizeReport {
        return run(config)
    }
}
