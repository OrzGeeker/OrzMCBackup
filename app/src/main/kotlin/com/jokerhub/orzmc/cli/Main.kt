package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.world.*
import picocli.CommandLine
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "backup",
    version = ["0.1.0"],
    description = [
        "Optimize Minecraft Java worlds",
        "Scan MCA region files and remove unused chunks. Keep chunks by InhabitedTime threshold and force-loaded tickets."
    ],
    mixinStandardHelpOptions = true
)
class Main : Callable<Int> {
    @Parameters(index = "0", description = ["Minecraft world root"], paramLabel = "WORLD_DIR")
    lateinit var input: Path

    @Parameters(
        index = "1",
        arity = "0..1",
        description = ["Output directory (must be empty; optional)"],
        paramLabel = "OUTPUT_DIR"
    )
    var output: Path? = null

    @Option(
        names = ["-t", "--inhabited-time-seconds"],
        description = ["InhabitedTime threshold in seconds (1s = 20 ticks)"],
        defaultValue = "300"
    )
    var inhabitedTimeSeconds: Long = 300

    @Option(
        names = ["--remove-unknown"],
        description = ["Treat unknown/external-compressed chunks as removable"],
        defaultValue = "false"
    )
    var removeUnknown: Boolean = false

    @Option(
        names = ["--progress-mode"],
        description = ["Progress display: Off | Global | Region"],
        defaultValue = "Region"
    )
    lateinit var progressMode: ProgressMode

    @Option(
        names = ["--in-place"],
        description = ["Process in-place: ignore OUTPUT_DIR and replace WORLD_DIR"],
        defaultValue = "false"
    )
    var inPlace: Boolean = false

    @Option(
        names = ["--zip-output"],
        description = ["Zip OUTPUT_DIR to timestamped archive (YYYYMMddHHmmss.zip) and remove it"],
        defaultValue = "false"
    )
    var zipOutput: Boolean = false

    @Option(
        names = ["-f", "--force"],
        description = ["Force overwrite OUTPUT_DIR if it exists (no prompt)"],
        defaultValue = "false"
    )
    var force: Boolean = false

    @Option(
        names = ["--strict"],
        description = ["Strict mode: fail on damaged MCA or parse errors"],
        defaultValue = "false"
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
        defaultValue = "1000"
    )
    var progressInterval: Long = 1000

    @Option(
        names = ["--progress-interval-ms"],
        description = ["Progress callback interval (milliseconds)"],
        defaultValue = "0"
    )
    var progressIntervalMs: Long = 0

    @Option(names = ["--parallelism"], description = ["Parallel dimension processing threads"], defaultValue = "1")
    var parallelism: Int = 1

    override fun call(): Int {
        return try {
            val sink = reportFile?.let { FileReportSink(it, reportFormat) }
            val progressPrinter: ((ProgressEvent) -> Unit)? = when (progressMode) {
                ProgressMode.Off -> null
                else -> { e ->
                    when (e.stage) {
                        ProgressStage.Init -> println("开始")
                        ProgressStage.Discover -> {
                            val t = e.total ?: 0
                            println("扫描与统计区块，总数：$t")
                        }
                        ProgressStage.DimensionStart -> println("处理维度：${e.path}")
                        ProgressStage.RegionStart -> if (progressMode == ProgressMode.Region) println("处理区块文件：${e.path}")
                        ProgressStage.ChunkProgress -> {
                            val cur = e.current ?: 0
                            val tot = e.total ?: 0
                            if (progressMode == ProgressMode.Global) {
                                val percent = if (tot > 0) (cur * 100) / tot else 0
                                println("进度：$percent%（$cur/$tot）")
                            }
                        }
                        ProgressStage.Finalize -> println("完成写入：${e.path}")
                        ProgressStage.Compress -> println("压缩输出目录")
                        ProgressStage.Cleanup -> println("清理输出目录")
                        ProgressStage.DimensionEnd -> println("维度完成：${e.path}")
                        ProgressStage.Done -> {
                            val cur = e.current ?: 0
                            val tot = e.total ?: 0
                            println("完成：$cur/$tot")
                        }
                    }
                }
            }
            val config = OptimizerConfig(
                input = input,
                output = output,
                inhabitedThresholdSeconds = inhabitedTimeSeconds,
                removeUnknown = removeUnknown,
                progressMode = progressMode,
                zipOutput = zipOutput,
                inPlace = inPlace,
                force = force,
                strict = strict,
                progressInterval = progressInterval,
                progressIntervalMs = progressIntervalMs,
                onError = null,
                onProgress = progressPrinter,
                parallelism = parallelism,
                progressSink = null,
                reportSink = sink
            )
            val r = Optimizer.run(config)
            if (report) println(ReportIO.toText(r))
            reportFile?.let { path -> println("报告已写入：$path") }
            if (strict && r.errors.isNotEmpty()) 1 else 0
        } catch (e: OptimizeException) {
            System.err.println(e.message ?: "发生错误")
            1
        } catch (e: Exception) {
            System.err.println("发生错误：" + (e.message ?: e.toString()))
            1
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val exit = CommandLine(Main()).execute(*args)
            if (exit != 0) System.exit(exit)
        }
    }
}
