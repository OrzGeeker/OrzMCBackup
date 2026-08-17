package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.patterns.InhabitedTimePattern
import com.jokerhub.orzmc.patterns.ListPattern
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher

enum class ProgressMode { Off, Global, Region }

/**
 * Shared context for dimension processing methods.
 * Collapses the 15+ parameter explosion in helper methods.
 */
internal data class DimensionContext(
    val fs: FileSystem,
    val input: Path,
    val out: Path,
    val ticks: Long,
    val progressTotal: Long,
    val processedChunksAtomic: java.util.concurrent.atomic.AtomicLong,
    val record: (Path, String, String) -> Unit,
    val emit: (ProgressStage, Long?, Long?, Path?, String?) -> Unit,
    val metricsSink: MetricsSink,
    val removeUnknown: Boolean,
    val strict: Boolean,
    val progressInterval: Long,
    val progressIntervalMs: Long,
    val progressSink: ProgressSink,
    val ioFactory: McaIOFactory,
    val parallelism: Int,
    val dryRun: Boolean = false,
)

/**
 * Backward-compatible entry point for Java and simpler Kotlin callers.
 * Delegates to [DefaultOptimizer] under the hood, providing [@JvmStatic] accessors
 * so existing `Optimizer.run(request)` calls continue to compile.
 *
 * For injection/mock support, use the [Optimizer] interface and [DefaultOptimizer].
 */
object Optimizer {
    @JvmStatic
    fun run(request: OptimizerRequest): OptimizeReport = DefaultOptimizer.run(request)

    @JvmStatic
    fun run(
        input: Path,
        output: Path? = null,
        block: OptimizerRequestBuilder.() -> Unit = {},
    ): OptimizeReport = DefaultOptimizer.run(input, output, block)
}

/**
 * Default [Optimizer] implementation.
 */
object DefaultOptimizer : OptimizerEngine {
    private fun isDimensionDir(
        fs: FileSystem,
        path: Path,
    ): Boolean = fs.isDirectory(path.resolve("region"))

    private fun discoverDimensions(
        fs: FileSystem,
        root: Path,
    ): List<Path> {
        val tasks = mutableListOf<Path>()
        if (isDimensionDir(fs, root)) tasks.add(root)
        fs
            .walk(root)
            .filter { it != root && fs.isDirectory(it) && isDimensionDir(fs, it) }
            .forEach { tasks.add(it) }
        return tasks
    }

    /**
     * Finds ancestor directories of discovered dimensions that are NOT themselves
     * dimension directories but contain [level.dat], [players/], [data/], etc. at
     * their root level — a layout introduced by Paper 26.1+ (e.g.,
     * `world/` → `world/dimensions/minecraft/overworld/`).
     *
     * Misc files from these ancestors (e.g., `world/level.dat`, `world/players/`)
     * are excluded from per-dimension misc copying since the ancestors are not
     * discovered as dimensions. They must be copied separately.
     */
    private fun discoverMiscParents(
        fs: FileSystem,
        input: Path,
        dimensions: List<Path>,
    ): List<Path> {
        val parents = mutableSetOf<Path>()
        dimensions.forEach { dim ->
            // Only traverse up for nested dimensions (Paper 26.1+ structure).
            // When dim == input (flat single-world layout), there are no
            // ancestor misc files to copy — they ARE the dimension's own misc.
            if (dim == input) return@forEach
            var parent = dim.parent
            while (parent != null && parent != input) {
                if (!isDimensionDir(fs, parent)) {
                    parents.add(parent)
                }
                parent = parent.parent
            }
        }
        // When input itself is not a dimension but directly contains misc files
        // (e.g., the world directory passed directly in a 26.1+ nested layout),
        // include it so world-level files (level.dat, players/, data/, etc.)
        // are copied. Detect this by checking for a dimensions/ subdirectory
        // (definitive marker of a 26.1+ world root) without a region/ directory.
        if (!isDimensionDir(fs, input) && fs.isDirectory(input.resolve("dimensions"))) {
            parents.add(input)
        }
        return parents.toList()
    }

    override fun run(request: OptimizerRequest): OptimizeReport {
        val input = request.input
        val fs = request.io.fs
        val errors = mutableListOf<OptimizeError>()
        val metrics = request.hooks.metricsSink ?: NoopMetricsSink()
        val progressSink = request.progress.sink

        fun record(
            path: Path,
            kind: String,
            msg: String,
        ) {
            val e = OptimizeError(path.toString(), kind, msg)
            request.hooks.onError?.invoke(e)
            errors.add(e)
            metrics.recordError(e)
        }

        fun emit(
            stage: ProgressStage,
            current: Long? = null,
            total: Long? = null,
            path: Path? = null,
            message: String? = null,
        ) {
            progressSink.emit(ProgressEvent(stage, current, total, path?.toString(), message))
        }

        if (!fs.isDirectory(input)) {
            val msg = "Input directory does not exist or is not a directory"
            record(input, "Input", msg)
            return OptimizeReport(processedChunks = 0, removedChunks = 0, errors = errors)
        }
        emit(ProgressStage.Init, 0, 0, input, "starting")

        val out = resolveOutputDir(fs, request, errors, progressSink) ?: return OptimizeReport(0, 0, errors)
        val ticks = request.filter.inhabitedThresholdSeconds * 20
        emit(ProgressStage.Discover, null, null, input, "scanning dimensions")
        val tasks = discoverDimensions(fs, input)
        val dimSet = tasks.toSet()
        val miscParents = discoverMiscParents(fs, input, tasks)
        val totalChunks = McaUtils.countTotalChunks(fs, request.io.ioFactory, tasks) { p, k, m -> record(p, k, m) }

        val miscSources = tasks + miscParents
        val miscTotal = countMiscFiles(fs, miscSources, request, dimSet)
        val zipSteps = if (!request.outputOptions.inPlace && request.outputOptions.zipOutput) 2L else 0L
        val progressTotal = totalChunks + miscTotal + zipSteps
        emit(ProgressStage.Discover, 0, totalChunks, input, "counting chunks")

        val processedChunksAtomic =
            java.util.concurrent.atomic
                .AtomicLong(0L)

        val ctx =
            DimensionContext(
                fs = fs,
                input = input,
                out = out,
                ticks = ticks,
                progressTotal = progressTotal,
                processedChunksAtomic = processedChunksAtomic,
                record = { p, k, m -> record(p, k, m) },
                emit = { s, c, t, p, m -> emit(s, c, t, p, m) },
                metricsSink = metrics,
                removeUnknown = request.filter.removeUnknown,
                strict = request.filter.strict,
                progressInterval = request.progress.interval,
                progressIntervalMs = request.progress.intervalMs,
                progressSink = progressSink,
                ioFactory = request.io.ioFactory,
                parallelism = request.runtime.parallelism,
                dryRun = request.outputOptions.dryRun,
            )

        val removedTotal = processDimensions(ctx, tasks)

        if (!request.outputOptions.dryRun) {
            if (request.outputOptions.inPlace) {
                handleInPlaceReplacement(fs, tasks, input, out)
            } else {
                if (request.outputOptions.copyMisc) {
                    copyMiscFiles(ctx, miscSources, request, dimSet)
                }
                if (request.outputOptions.zipOutput) {
                    handleZipOutput(ctx, miscTotal)
                }
            }
        }

        val doneCur = processedChunksAtomic.get() + miscTotal + zipSteps
        emit(ProgressStage.Done, doneCur, progressTotal, input, null)
        val report = OptimizeReport(processedChunksAtomic.get(), removedTotal, errors)
        request.hooks.reportSink?.write(report)
        metrics.incProcessed(report.processedChunks)
        metrics.incRemoved(report.removedChunks)
        return report
    }

    // ---- extracted helper methods ----

    private fun resolveOutputDir(
        fs: FileSystem,
        request: OptimizerRequest,
        errors: MutableList<OptimizeError>,
        progressSink: ProgressSink,
    ): Path? {
        val input = request.input
        val output = request.output
        if (request.outputOptions.dryRun) return fs.createTempDirectory("thanos-dry-")
        if (request.outputOptions.inPlace) return fs.createTempDirectory("thanos-")
        if (output == null) {
            val msg = "Output directory required when not in in-place mode"
            errors.add(OptimizeError(input.toString(), "Output", msg))
            return null
        }
        try {
            if (fs.exists(output)) {
                val nonEmpty = fs.list(output).isNotEmpty()
                if (nonEmpty) {
                    if (request.outputOptions.force) {
                        fs
                            .walk(output)
                            .sortedByDescending { it.toString().length }
                            .forEach { fs.deleteIfExists(it) }
                        fs.createDirectories(output)
                    } else {
                        val msg = "Output directory already exists and is not empty; use --force to overwrite"
                        errors.add(OptimizeError(output.toString(), "Output", msg))
                        return null
                    }
                }
            } else {
                fs.createDirectories(output)
            }
        } catch (e: IOException) {
            val msg = "Output directory is not writable or access denied: $output"
            errors.add(OptimizeError(output.toString(), "Output", msg))
            return null
        }
        return output
    }

    private fun countMiscFiles(
        fs: FileSystem,
        sources: List<Path>,
        request: OptimizerRequest,
        excludePaths: Set<Path> = emptySet(),
    ): Long {
        if (request.outputOptions.inPlace || !request.outputOptions.copyMisc) return 0L
        var c = 0L
        val reserved = setOf("region", "entities", "poi")
        val matchers = skipMatchers()
        sources.forEach { dir ->
            for (p in fs.walk(dir)) {
                if (miscRel(dir, p, excludePaths, reserved, matchers) == null) continue
                c += 1
            }
        }
        return c
    }

    private fun processDimensions(
        ctx: DimensionContext,
        tasks: List<Path>,
    ): Long {
        if (ctx.parallelism <= 1) {
            return processSerially(ctx, tasks)
        } else {
            return processInParallel(ctx, tasks)
        }
    }

    private fun processSerially(
        ctx: DimensionContext,
        tasks: List<Path>,
    ): Long {
        var removedTotal = 0L
        tasks.forEach { dim ->
            val result = processSingleDimension(ctx, dim)
            removedTotal += result.removed
            ctx.metricsSink.incProcessed(result.processed)
            ctx.metricsSink.incRemoved(result.removed)
        }
        return removedTotal
    }

    private fun processInParallel(
        ctx: DimensionContext,
        tasks: List<Path>,
    ): Long {
        var removedTotal = 0L
        val executor =
            java.util.concurrent.Executors
                .newFixedThreadPool(ctx.parallelism)
        val futures = mutableListOf<java.util.concurrent.Future<DimensionResult>>()
        tasks.forEach { dim ->
            val task = java.util.concurrent.Callable { processSingleDimension(ctx, dim) }
            futures.add(executor.submit(task))
        }
        futures.forEach { f ->
            try {
                val r = f.get()
                removedTotal += r.removed
                ctx.metricsSink.incProcessed(r.processed)
                ctx.metricsSink.incRemoved(r.removed)
            } catch (e: Exception) {
                ctx.record(
                    ctx.input,
                    "Parallel",
                    "Dimension parallel processing failed: ${e.message ?: "unknown error"}",
                )
            }
        }
        executor.shutdown()
        return removedTotal
    }

    private fun processSingleDimension(
        ctx: DimensionContext,
        dim: Path,
    ): DimensionResult {
        val rel = ctx.input.relativize(dim)
        val targetDim = ctx.out.resolve(rel)
        val forced =
            try {
                ForceLoad.parse(dim, ctx.strict)
            } catch (e: ForceLoadedParseException) {
                if (ctx.strict) ctx.record(dim, "ForceLoaded", e.message ?: "Failed to parse force-loaded chunk list")
                emptyList()
            }
        val patterns =
            listOf(
                ListPattern(forced),
                InhabitedTimePattern(ctx.ticks, ctx.removeUnknown),
            )
        return DimensionProcessor.process(
            ctx.fs,
            ctx.ioFactory,
            dim,
            targetDim,
            patterns,
            ctx.record,
            ctx.progressSink,
            ctx.progressTotal,
            ctx.progressInterval,
            ctx.progressIntervalMs,
            ctx.processedChunksAtomic,
            ctx.strict,
            ctx.parallelism,
            dryRun = ctx.dryRun,
        )
    }

    @Suppress("ThrowsCount")
    private fun handleInPlaceReplacement(
        fs: FileSystem,
        tasks: List<Path>,
        input: Path,
        out: Path,
    ) {
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
                    throw InPlaceReplacementException("Failed to create target directory: $dst", e)
                }
                val keep = HashSet<String>()
                fs.list(src).filter { it.toString().endsWith(".mca") }.forEach { keep.add(it.fileName.toString()) }
                if (fs.isDirectory(dst)) {
                    try {
                        fs.list(dst).filter { it.toString().endsWith(".mca") }.forEach { p ->
                            if (!keep.contains(p.fileName.toString())) fs.deleteIfExists(p)
                        }
                    } catch (e: IOException) {
                        throw InPlaceReplacementException("Failed to clean target directory: $dst", e)
                    }
                }
                try {
                    fs.list(src).filter { it.toString().endsWith(".mca") }.forEach { p ->
                        val target = dst.resolve(p.fileName.toString())
                        fs.copy(p, target, true)
                    }
                } catch (e: IOException) {
                    throw InPlaceReplacementException("Failed to copy files to target directory: $dst", e)
                }
            }
        }
        try {
            val ok = fs.deleteTreeWithRetry(out, 5, 500)
            if (!ok) throw IOException("cleanup failed")
        } catch (e: IOException) {
            throw InPlaceReplacementException("Failed to clean up temp directory: $out", e)
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun copyMiscFiles(
        ctx: DimensionContext,
        sources: List<Path>,
        request: OptimizerRequest,
        excludePaths: Set<Path> = emptySet(),
    ) {
        val base = ctx.processedChunksAtomic.get()
        ctx.emit(ProgressStage.CopyMisc, base, ctx.progressTotal, ctx.out, "copying misc files")
        val progressIntervalMs = ctx.progressIntervalMs
        val progressInterval = ctx.progressInterval
        var done = 0L
        val useTime = progressIntervalMs > 0
        var lastEmit = System.currentTimeMillis()

        fun maybeEmit(p: Path?) {
            if (useTime) {
                val now = System.currentTimeMillis()
                if (now - lastEmit >= progressIntervalMs) {
                    ctx.emit(ProgressStage.CopyMiscProgress, base + done, ctx.progressTotal, p, null)
                    lastEmit = now
                }
            } else if (progressInterval > 0 && done % progressInterval == 0L) {
                ctx.emit(ProgressStage.CopyMiscProgress, base + done, ctx.progressTotal, p, null)
            }
        }
        val reserved = setOf("region", "entities", "poi")
        val matchers = skipMatchers()
        sources.forEach { dir ->
            val rel = ctx.input.relativize(dir)
            val outDir = ctx.out.resolve(rel)
            ctx.fs.createDirectories(outDir)
            for (p in ctx.fs.walk(dir)) {
                val relPath = miscRel(dir, p, excludePaths, reserved, matchers) ?: continue
                val target = outDir.resolve(relPath)
                if (ctx.fs.isDirectory(p)) {
                    ctx.fs.createDirectories(target)
                    done += 1
                    maybeEmit(target)
                } else {
                    try {
                        ctx.fs.createDirectories(target.parent ?: outDir)
                    } catch (e: Exception) {
                        ctx.record(p, "CopyMisc", "创建目录失败: ${e.message}")
                    }
                    try {
                        ctx.fs.copy(p, target, true)
                    } catch (e: Exception) {
                        ctx.record(p, "CopyMisc", "复制文件失败: ${e.message}")
                    }
                    done += 1
                    maybeEmit(target)
                }
            }
        }
        ctx.emit(ProgressStage.CopyMiscProgress, base + done, ctx.progressTotal, ctx.out, null)
    }

    private fun handleZipOutput(
        ctx: DimensionContext,
        miscTotal: Long,
    ) {
        val base = ctx.processedChunksAtomic.get()
        val afterMisc = base + miscTotal
        try {
            ctx.emit(ProgressStage.Compress, afterMisc, ctx.progressTotal, ctx.out, null)
            Compressor.compressToTimestampZip(ctx.out)
            ctx.emit(ProgressStage.Compress, afterMisc + 1, ctx.progressTotal, ctx.out, null)
        } catch (e: IOException) {
            val msg = "Failed to compress output directory: ${ctx.out}"
            ctx.record(ctx.out, "Compress", msg)
        }
        try {
            ctx.emit(ProgressStage.Cleanup, afterMisc + 1, ctx.progressTotal, ctx.out, null)
            val ok = ctx.fs.deleteTreeWithRetry(ctx.out, 5, 500)
            if (!ok) throw IOException("cleanup failed")
            ctx.emit(ProgressStage.Cleanup, afterMisc + 2, ctx.progressTotal, ctx.out, null)
        } catch (e: IOException) {
            val msg = "Failed to delete output directory: ${ctx.out}"
            ctx.record(ctx.out, "Cleanup", msg)
        }
    }

    override fun run(
        input: Path,
        output: Path?,
        block: OptimizerRequestBuilder.() -> Unit,
    ): OptimizeReport {
        val builder = OptimizerRequestBuilder(input, output)
        builder.block()
        return run(builder.build())
    }
}

/**
 * Decides whether a walked misc-file path [p] under [dir] is a real misc file
 * that must be counted/copied, or something to skip. Returns the path relative
 * to [dir] when the entry is included, or `null` when it must be skipped.
 *
 * Skips:
 * - the walk root itself and entries outside [excludePaths]
 * - files matching [matchers] (glob on the bare file name, e.g. `session.lock`)
 * - top-level `.mca` files directly inside `region`/`entities`/`poi` (`rel.nameCount == 2`),
 *   which the dimension processor rewrites; nested `.mca` and non-`.mca` files
 *   (e.g. `.bak`/`.backup`) in those subtrees are misc and must be kept.
 */
private fun miscRel(
    dir: Path,
    p: Path,
    excludePaths: Set<Path>,
    reserved: Set<String>,
    matchers: List<PathMatcher>,
): Path? {
    if (p == dir) return null
    if (excludePaths.any { it != dir && p.startsWith(it) }) return null
    val rel = dir.relativize(p)
    if (rel.toString().isEmpty()) return null
    val fileName = rel.nameCount.let { if (it > 0) rel.getName(it - 1).toString() else "" }
    if (matchers.any { it.matches(Path.of(fileName)) }) return null
    val top = if (rel.nameCount > 0) rel.getName(0).toString() else ""
    if (reserved.contains(top) && rel.nameCount == 2 && rel.toString().endsWith(".mca")) return null
    return rel
}

private fun skipMatchers(): List<PathMatcher> {
    val fsDefault = FileSystems.getDefault()
    return setOf("session.lock").map { fsDefault.getPathMatcher("glob:$it") }
}
