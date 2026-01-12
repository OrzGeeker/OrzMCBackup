package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.ProgressMode
import com.jokerhub.orzmc.world.OptimizeException
import com.jokerhub.orzmc.world.ReportIO
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
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

    @Parameters(index = "1", arity = "0..1", description = ["Output directory (must be empty; optional)"], paramLabel = "OUTPUT_DIR")
    var output: Path? = null

    @Option(names = ["-t", "--inhabited-time-seconds"], description = ["InhabitedTime threshold in seconds (1s = 20 ticks)"], defaultValue = "300")
    var inhabitedTimeSeconds: Long = 300

    @Option(names = ["--remove-unknown"], description = ["Treat unknown/external-compressed chunks as removable"], defaultValue = "false")
    var removeUnknown: Boolean = false

    @Option(names = ["--progress-mode"], description = ["Progress display: Off | Global | Region"], defaultValue = "Region")
    lateinit var progressMode: ProgressMode

    @Option(names = ["--in-place"], description = ["Process in-place: ignore OUTPUT_DIR and replace WORLD_DIR"], defaultValue = "false")
    var inPlace: Boolean = false

    @Option(names = ["--zip-output"], description = ["Zip OUTPUT_DIR to timestamped archive (YYYYMMddHHmmss.zip) and remove it"], defaultValue = "false")
    var zipOutput: Boolean = false

    @Option(names = ["-f", "--force"], description = ["Force overwrite OUTPUT_DIR if it exists (no prompt)"], defaultValue = "false")
    var force: Boolean = false

    @Option(names = ["--strict"], description = ["Strict mode: fail on damaged MCA or parse errors"], defaultValue = "false")
    var strict: Boolean = false
    @Option(names = ["--report"], description = ["Print summary report and errors"], defaultValue = "false")
    var report: Boolean = false
    @Option(names = ["--report-file"], description = ["Write report to file (JSON/CSV)"], required = false)
    var reportFile: Path? = null
    @Option(names = ["--report-format"], description = ["Report format: json | csv"], defaultValue = "json")
    var reportFormat: String = "json"
    @Option(names = ["--progress-interval"], description = ["Progress callback interval (chunks)"], defaultValue = "1000")
    var progressInterval: Long = 1000
    @Option(names = ["--progress-interval-ms"], description = ["Progress callback interval (milliseconds)"], defaultValue = "0")
    var progressIntervalMs: Long = 0

    override fun call(): Int {
        return try {
            val r = Optimizer.run(input, output, inhabitedTimeSeconds, removeUnknown, progressMode, zipOutput, inPlace, force, strict, progressInterval, progressIntervalMs)
            if (report) println(ReportIO.toText(r))
            reportFile?.let { path ->
                ReportIO.write(r, path, reportFormat)
                println("报告已写入：$path")
            }
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
