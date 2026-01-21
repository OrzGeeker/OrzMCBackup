package com.jokerhub.orzmc.world

import java.nio.file.Path

data class OptimizerConfig(
    val input: Path,
    val output: Path? = null,
    val inhabitedThresholdSeconds: Long,
    val removeUnknown: Boolean,
    val progressMode: ProgressMode = ProgressMode.Region,
    val zipOutput: Boolean = false,
    val inPlace: Boolean = false,
    val force: Boolean = false,
    val strict: Boolean = false,
    val progressInterval: Long = 1000,
    val progressIntervalMs: Long = 0,
    val onError: ((OptimizeError) -> Unit)? = null,
    val onProgress: ((ProgressEvent) -> Unit)? = null,
    val parallelism: Int = 1,
    val copyMisc: Boolean = true,
    val progressSink: ProgressSink? = null,
    val reportSink: ReportSink? = null,
    val fs: FileSystem = RealFileSystem,
    val metricsSink: MetricsSink? = null,
    val loggerSink: LoggerSink? = null,
    val ioFactory: McaIOFactory = DefaultMcaIOFactory()
)
