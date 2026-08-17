package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.world.*
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "merge",
    description = [
        "Merge an OrzMCBackup-optimized world back onto a full world backup.",
        "Overlays the optimized (newer) chunk data onto the full backup at chunk-slot " +
            "granularity; pruned slots are filled from the full backup so nothing is lost.",
    ],
    mixinStandardHelpOptions = true,
    versionProvider = BuildVersionProvider::class,
)
class MergeCommand : Callable<Int> {
    var logger: LoggerSink = ConsoleLoggerSink()

    @Parameters(index = "0", description = ["Full world backup (older, complete)"], paramLabel = "BASE")
    lateinit var base: Path

    @Parameters(index = "1", description = ["Optimized world (newer, partial)"], paramLabel = "PATCH")
    lateinit var patch: Path

    @Parameters(index = "2", description = ["Output directory"], paramLabel = "OUTPUT")
    lateinit var output: Path

    @Option(
        names = ["-f", "--force"],
        description = ["Force overwrite OUTPUT if it exists"],
        defaultValue = "false",
    )
    var force: Boolean = false

    @Option(
        names = ["--progress-mode"],
        description = ["Progress display: Off | Global | Region"],
        defaultValue = "Off",
    )
    lateinit var progressMode: ProgressMode

    @Option(
        names = ["--progress-interval"],
        description = ["Progress callback interval (files)"],
        defaultValue = "1000",
    )
    var progressInterval: Long = 1000

    @Option(names = ["--report"], description = ["Print summary report"], defaultValue = "false")
    var report: Boolean = false

    @Option(names = ["--report-file"], description = ["Write report to file (JSON/CSV)"], required = false)
    var reportFile: Path? = null

    @Option(names = ["--report-format"], description = ["Report format: json | csv"], defaultValue = "json")
    var reportFormat: String = "json"

    override fun call(): Int =
        try {
            val progressPrinter: ((ProgressEvent) -> Unit)? =
                when (progressMode) {
                    ProgressMode.Off -> null
                    else -> { e ->
                        when (e.stage) {
                            ProgressStage.Init -> logger.info("开始合并")
                            ProgressStage.CopyMisc -> logger.info("覆盖/合并优化备份文件")
                            ProgressStage.CopyMiscProgress -> {
                                val cur = e.current ?: 0
                                val tot = e.total ?: 0
                                if (progressMode == ProgressMode.Global) {
                                    val percent = if (tot > 0) (cur * 100) / tot else 0
                                    logger.info("进度：$percent%（$cur/$tot）")
                                }
                            }
                            ProgressStage.Done -> {
                                val cur = e.current ?: 0
                                logger.info("完成：$cur")
                            }
                            else -> {}
                        }
                    }
                }
            val progressSink = progressPrinter?.let { CallbackProgressSink(it) } ?: NoopProgressSink
            val request =
                MergeRequest(
                    base = base,
                    patch = patch,
                    output = output,
                    outputOptions = OutputOptions(force = force, copyMisc = true),
                    progress = ProgressOptions(interval = progressInterval, sink = progressSink),
                    runtime = RuntimeOptions(parallelism = 1),
                    hooks = Hooks(reportSink = reportFile?.let { FileReportSink(it, reportFormat) }),
                    io = IOOptions(),
                )
            val r = WorldMerger.run(request)
            if (report) logger.info(MergeReportIO.toText(r))
            reportFile?.let { path -> logger.info("报告已写入：$path") }
            if (r.errors.isNotEmpty()) 1 else 0
        } catch (e: OptimizeException) {
            logger.error(e.message ?: "发生错误")
            1
        } catch (e: Exception) {
            logger.error("发生错误：" + (e.message ?: e.toString()))
            1
        }
}
