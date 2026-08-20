package com.jokerhub.orzmc.world

import java.nio.file.Path
import java.nio.file.Paths

data class OptimizerRequest(
    val input: Path,
    val output: Path? = null,
    val filter: FilterOptions = FilterOptions(),
    val outputOptions: OutputOptions = OutputOptions(),
    val progress: ProgressOptions = ProgressOptions(),
    val runtime: RuntimeOptions = RuntimeOptions(),
    val hooks: Hooks = Hooks(),
    val io: IOOptions = IOOptions(),
)

data class FilterOptions(
    val inhabitedThresholdSeconds: Long = 300,
    val removeUnknown: Boolean = false,
    val strict: Boolean = false,
)

data class OutputOptions(
    val inPlace: Boolean = false,
    val zipOutput: Boolean = false,
    val force: Boolean = false,
    val copyMisc: Boolean = true,
    val dryRun: Boolean = false,
)

data class ProgressOptions(
    val interval: Long = 1000,
    val intervalMs: Long = 0,
    val sink: ProgressSink = NoopProgressSink,
)

data class RuntimeOptions(
    val parallelism: Int = 1,
)

data class Hooks(
    val onError: ((OptimizeError) -> Unit)? = null,
    val reportSink: ReportSink? = null,
    val metricsSink: MetricsSink? = null,
)

data class IOOptions(
    val fs: FileSystem = RealFileSystem,
    val ioFactory: McaIOFactory = DefaultMcaIOFactory(),
    /** Skip the per-region `fd.sync()` flush after finalize. Faster but less durable. */
    val syncOnFinalize: Boolean = true,
)

class OptimizerRequestBuilder internal constructor(
    private val input: Path,
    var output: Path? = null,
) {
    var filter: FilterOptions = FilterOptions()
    var outputOptions: OutputOptions = OutputOptions()
    var progress: ProgressOptions = ProgressOptions()
    var runtime: RuntimeOptions = RuntimeOptions()
    var hooks: Hooks = Hooks()
    var io: IOOptions = IOOptions()

    var inhabitedThresholdSeconds: Long
        get() = filter.inhabitedThresholdSeconds
        set(value) {
            filter = filter.copy(inhabitedThresholdSeconds = value)
        }

    var removeUnknown: Boolean
        get() = filter.removeUnknown
        set(value) {
            filter = filter.copy(removeUnknown = value)
        }

    var strict: Boolean
        get() = filter.strict
        set(value) {
            filter = filter.copy(strict = value)
        }

    var inPlace: Boolean
        get() = outputOptions.inPlace
        set(value) {
            outputOptions = outputOptions.copy(inPlace = value)
        }

    var zipOutput: Boolean
        get() = outputOptions.zipOutput
        set(value) {
            outputOptions = outputOptions.copy(zipOutput = value)
        }

    var force: Boolean
        get() = outputOptions.force
        set(value) {
            outputOptions = outputOptions.copy(force = value)
        }

    var copyMisc: Boolean
        get() = outputOptions.copyMisc
        set(value) {
            outputOptions = outputOptions.copy(copyMisc = value)
        }

    var dryRun: Boolean
        get() = outputOptions.dryRun
        set(value) {
            outputOptions = outputOptions.copy(dryRun = value)
        }

    var progressInterval: Long
        get() = progress.interval
        set(value) {
            progress = progress.copy(interval = value)
        }

    var progressIntervalMs: Long
        get() = progress.intervalMs
        set(value) {
            progress = progress.copy(intervalMs = value)
        }

    var progressSink: ProgressSink
        get() = progress.sink
        set(value) {
            progress = progress.copy(sink = value)
        }

    fun progressSink(callback: (ProgressEvent) -> Unit) {
        progress = progress.copy(sink = CallbackProgressSink(callback))
    }

    var parallelism: Int
        get() = runtime.parallelism
        set(value) {
            runtime = runtime.copy(parallelism = value)
        }

    fun onProgress(callback: (ProgressEvent) -> Unit) {
        progress = progress.copy(sink = CallbackProgressSink(callback))
    }

    fun onError(callback: (OptimizeError) -> Unit) {
        hooks = hooks.copy(onError = callback)
    }

    var reportFormat: String? = null

    var reportSink: ReportSink?
        get() = hooks.reportSink
        set(value) {
            hooks = hooks.copy(reportSink = value)
        }

    fun reportSink(
        path: String,
        format: String? = null,
    ) {
        reportFormat = format
        val p = normalizeReportPath(Paths.get(path), reportFormat)
        reportSink = FileReportSink(p, reportFormat ?: inferReportFormat(p))
    }

    fun reportSink(
        path: Path,
        format: String? = null,
    ) {
        reportFormat = format
        val p = normalizeReportPath(path, reportFormat)
        reportSink = FileReportSink(p, reportFormat ?: inferReportFormat(p))
    }

    var metricsSink: MetricsSink?
        get() = hooks.metricsSink
        set(value) {
            hooks = hooks.copy(metricsSink = value)
        }

    var fs: FileSystem
        get() = io.fs
        set(value) {
            io = io.copy(fs = value)
        }

    var ioFactory: McaIOFactory
        get() = io.ioFactory
        set(value) {
            io = io.copy(ioFactory = value)
        }

    fun filter(block: FilterOptionsBuilder.() -> Unit) {
        val builder = FilterOptionsBuilder(filter)
        builder.block()
        filter = builder.build()
    }

    fun output(block: OutputOptionsBuilder.() -> Unit) {
        val builder = OutputOptionsBuilder(outputOptions)
        builder.block()
        outputOptions = builder.build()
    }

    fun progress(block: ProgressOptionsBuilder.() -> Unit) {
        val builder = ProgressOptionsBuilder(progress)
        builder.block()
        progress = builder.build()
    }

    fun runtime(block: RuntimeOptionsBuilder.() -> Unit) {
        val builder = RuntimeOptionsBuilder(runtime)
        builder.block()
        runtime = builder.build()
    }

    fun hooks(block: HooksBuilder.() -> Unit) {
        val builder = HooksBuilder(hooks)
        builder.block()
        hooks = builder.build()
    }

    fun io(block: IOOptionsBuilder.() -> Unit) {
        val builder = IOOptionsBuilder(io)
        builder.block()
        io = builder.build()
    }

    fun build(): OptimizerRequest = OptimizerRequest(input, output, filter, outputOptions, progress, runtime, hooks, io)
}

private fun inferReportFormat(path: Path): String {
    val name = path.fileName?.toString() ?: ""
    return if (name.endsWith(".csv", ignoreCase = true)) "csv" else "json"
}

private fun normalizeReportPath(
    path: Path,
    format: String?,
): Path {
    val name = path.fileName?.toString() ?: ""
    val hasExt = name.contains(".")
    if (hasExt) return path
    val ext =
        when (format?.lowercase()) {
            "csv" -> "csv"
            "json" -> "json"
            else -> "json"
        }
    val parent = path.parent
    val fileName = "$name.$ext"
    return if (parent == null) Paths.get(fileName) else parent.resolve(fileName)
}

class FilterOptionsBuilder internal constructor(
    base: FilterOptions,
) {
    var inhabitedThresholdSeconds: Long = base.inhabitedThresholdSeconds
    var removeUnknown: Boolean = base.removeUnknown
    var strict: Boolean = base.strict

    fun build(): FilterOptions = FilterOptions(inhabitedThresholdSeconds, removeUnknown, strict)
}

class OutputOptionsBuilder internal constructor(
    base: OutputOptions,
) {
    var inPlace: Boolean = base.inPlace
    var zipOutput: Boolean = base.zipOutput
    var force: Boolean = base.force
    var copyMisc: Boolean = base.copyMisc
    var dryRun: Boolean = base.dryRun

    fun build(): OutputOptions = OutputOptions(inPlace, zipOutput, force, copyMisc, dryRun)
}

class ProgressOptionsBuilder internal constructor(
    base: ProgressOptions,
) {
    var interval: Long = base.interval
    var intervalMs: Long = base.intervalMs
    var sink: ProgressSink = base.sink

    fun onProgress(callback: (ProgressEvent) -> Unit) {
        sink = CallbackProgressSink(callback)
    }

    fun build(): ProgressOptions = ProgressOptions(interval, intervalMs, sink)
}

class RuntimeOptionsBuilder internal constructor(
    base: RuntimeOptions,
) {
    var parallelism: Int = base.parallelism

    fun build(): RuntimeOptions = RuntimeOptions(parallelism)
}

class HooksBuilder internal constructor(
    base: Hooks,
) {
    var onError: ((OptimizeError) -> Unit)? = base.onError
    var reportSink: ReportSink? = base.reportSink
    var metricsSink: MetricsSink? = base.metricsSink

    fun build(): Hooks = Hooks(onError, reportSink, metricsSink)
}

class IOOptionsBuilder internal constructor(
    base: IOOptions,
) {
    var fs: FileSystem = base.fs
    var ioFactory: McaIOFactory = base.ioFactory
    var syncOnFinalize: Boolean = base.syncOnFinalize

    fun build(): IOOptions = IOOptions(fs, ioFactory, syncOnFinalize)
}
