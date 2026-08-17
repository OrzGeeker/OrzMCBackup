package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.world.*
import picocli.CommandLine
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "backup",
    description = [
        "Optimize Minecraft Java worlds",
        "Scan MCA region files and remove unused chunks. " +
            "Keep chunks by InhabitedTime threshold and force-loaded tickets.",
    ],
    mixinStandardHelpOptions = true,
    versionProvider = BuildVersionProvider::class,
)
class Main : Callable<Int> {
    var logger: LoggerSink = ConsoleLoggerSink()

    @Parameters(index = "0", description = ["Minecraft world root"], paramLabel = "WORLD_DIR")
    lateinit var input: Path

    @Parameters(
        index = "1",
        arity = "0..1",
        description = ["Output directory (must be empty; optional)"],
        paramLabel = "OUTPUT_DIR",
    )
    var output: Path? = null

    @Option(
        names = ["-t", "--inhabited-time-seconds"],
        description = ["InhabitedTime threshold in seconds (1s = 20 ticks)"],
        defaultValue = "300",
    )
    var inhabitedTimeSeconds: Long = 300

    @Option(
        names = ["--remove-unknown"],
        description = ["Treat unknown/external-compressed chunks as removable"],
        defaultValue = "false",
    )
    var removeUnknown: Boolean = false

    @Option(
        names = ["--progress-mode"],
        description = ["Progress display: Off | Global | Region"],
        defaultValue = "Region",
    )
    lateinit var progressMode: ProgressMode

    @Option(
        names = ["--in-place"],
        description = ["Process in-place: ignore OUTPUT_DIR and replace WORLD_DIR"],
        defaultValue = "false",
    )
    var inPlace: Boolean = false

    @Option(
        names = ["--zip-output"],
        description = ["Zip OUTPUT_DIR to timestamped archive (YYYYMMddHHmmss.zip) and remove it"],
        defaultValue = "false",
    )
    var zipOutput: Boolean = false

    @Option(
        names = ["-f", "--force"],
        description = ["Force overwrite OUTPUT_DIR if it exists (no prompt)"],
        defaultValue = "false",
    )
    var force: Boolean = false

    @Option(
        names = ["--strict"],
        description = ["Strict mode: fail on damaged MCA or parse errors"],
        defaultValue = "false",
    )
    var strict: Boolean = false

    @Option(names = ["--report"], description = ["Print summary report and errors"], defaultValue = "false")
    var report: Boolean = false

    @Option(names = ["--report-file"], description = ["Write report to file (JSON/CSV)"], required = false)
    var reportFile: Path? = null

    @Option(names = ["--report-format"], description = ["Report format: json | csv"], defaultValue = "json")
    var reportFormat: String = "json"

    @Option(
        names = ["--progress-interval"],
        description = ["Progress callback interval (chunks)"],
        defaultValue = "1000",
    )
    var progressInterval: Long = 1000

    @Option(
        names = ["--progress-interval-ms"],
        description = ["Progress callback interval (milliseconds)"],
        defaultValue = "0",
    )
    var progressIntervalMs: Long = 0

    @Option(names = ["--parallelism"], description = ["Parallel dimension processing threads"], defaultValue = "1")
    var parallelism: Int = 1

    @Option(
        names = ["--copy-misc"],
        description = ["Copy non-region/entities/poi files and folders in each dimension"],
        defaultValue = "true",
        // Without an explicit fallbackValue, picocli 4.7 resolves the bare
        // `--copy-misc` form of a negatable boolean option to `false`,
        // silently inverting the flag. Pin it to true.
        fallbackValue = "true",
        negatable = true,
    )
    var copyMisc: Boolean = true

    @Option(
        names = ["--dry-run"],
        description = ["Preview mode: scan and report without writing any output"],
        defaultValue = "false",
    )
    var dryRun: Boolean = false

    override fun call(): Int =
        try {
            val reportSink = reportFile?.let { FileReportSink(it, reportFormat) }
            val progressPrinter: ((ProgressEvent) -> Unit)? =
                when (progressMode) {
                    ProgressMode.Off -> null
                    else -> { e ->
                        when (e.stage) {
                            ProgressStage.Init -> logger.info("开始")
                            ProgressStage.Discover -> {
                                val t = e.total ?: 0
                                logger.info("扫描与统计区块，总数：$t")
                            }
                            ProgressStage.DimensionStart -> logger.info("处理维度：${e.path}")
                            ProgressStage.RegionStart ->
                                if (progressMode == ProgressMode.Region) {
                                    logger.info(
                                        "处理区块文件：${e.path}",
                                    )
                                }
                            ProgressStage.ChunkProgress -> {
                                val cur = e.current ?: 0
                                val tot = e.total ?: 0
                                if (progressMode == ProgressMode.Global) {
                                    val percent = if (tot > 0) (cur * 100) / tot else 0
                                    logger.info("进度：$percent%（$cur/$tot）")
                                }
                            }
                            ProgressStage.Finalize -> logger.info("完成写入：${e.path}")
                            ProgressStage.CopyMisc -> logger.info("复制杂项文件")
                            ProgressStage.CopyMiscProgress -> {
                                val cur = e.current ?: 0
                                val tot = e.total ?: 0
                                if (progressMode == ProgressMode.Global) {
                                    val percent = if (tot > 0) (cur * 100) / tot else 0
                                    logger.info("进度：$percent%（$cur/$tot）")
                                }
                            }
                            ProgressStage.Compress -> {
                                logger.info("压缩输出目录")
                                val cur = e.current ?: 0
                                val tot = e.total ?: 0
                                if (progressMode == ProgressMode.Global) {
                                    val percent = if (tot > 0) (cur * 100) / tot else 0
                                    logger.info("进度：$percent%（$cur/$tot）")
                                }
                            }
                            ProgressStage.Cleanup -> {
                                logger.info("清理输出目录")
                                val cur = e.current ?: 0
                                val tot = e.total ?: 0
                                if (progressMode == ProgressMode.Global) {
                                    val percent = if (tot > 0) (cur * 100) / tot else 0
                                    logger.info("进度：$percent%（$cur/$tot）")
                                }
                            }
                            ProgressStage.DimensionEnd -> logger.info("维度完成：${e.path}")
                            ProgressStage.Done -> {
                                val cur = e.current ?: 0
                                val tot = e.total ?: 0
                                logger.info("完成：$cur/$tot")
                            }
                        }
                    }
                }
            val progressSink = progressPrinter?.let { CallbackProgressSink(it) } ?: NoopProgressSink
            val request =
                OptimizerRequest(
                    input = input,
                    output = output,
                    filter =
                        FilterOptions(
                            inhabitedThresholdSeconds = inhabitedTimeSeconds,
                            removeUnknown = removeUnknown,
                            strict = strict,
                        ),
                    outputOptions =
                        OutputOptions(
                            inPlace = inPlace,
                            zipOutput = zipOutput,
                            force = force,
                            copyMisc = copyMisc,
                            dryRun = dryRun,
                        ),
                    progress =
                        ProgressOptions(
                            interval = progressInterval,
                            intervalMs = progressIntervalMs,
                            sink = progressSink,
                        ),
                    runtime = RuntimeOptions(parallelism = parallelism),
                    hooks = Hooks(reportSink = reportSink),
                )
            val r = Optimizer.run(request)
            if (report) logger.info(ReportIO.toText(r))
            reportFile?.let { path -> logger.info("报告已写入：$path") }
            if (strict && r.errors.isNotEmpty()) 1 else 0
        } catch (e: OptimizeException) {
            logger.error(e.message ?: "发生错误")
            1
        } catch (e: Exception) {
            logger.error("发生错误：" + (e.message ?: e.toString()))
            1
        }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val exit = dispatch(args)
            if (exit != 0) System.exit(exit)
        }

        /** Resolves `args` to a subcommand and returns its exit code without calling System.exit. */
        @JvmStatic
        fun dispatch(args: Array<String>): Int =
            when (args.firstOrNull()) {
                // Dispatch `merge ...` to the merge subcommand; everything else keeps
                // the original `backup <world>` behavior.
                "merge" -> CommandLine(MergeCommand()).execute(*args.drop(1).toTypedArray())
                else -> CommandLine(Main()).execute(*args)
            }
    }
}

class BuildVersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> {
        val v = Main::class.java.`package`?.implementationVersion ?: "dev"
        return arrayOf(v)
    }
}
