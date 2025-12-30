package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.ProgressMode
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    override fun call(): Int {
        if (!inPlace) {
            output?.let { outDir ->
                if (Files.exists(outDir)) {
                    val nonEmpty = Files.list(outDir).findFirst().isPresent
                    if (nonEmpty) {
                        if (force) {
                            Files.walk(outDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                            Files.createDirectories(outDir)
                        } else {
                            print("输出目录已存在，是否覆盖？[y/N]: ")
                            val resp = readlnOrNull()?.trim()?.lowercase()
                            if (resp == "y" || resp == "yes") {
                                Files.walk(outDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                                Files.createDirectories(outDir)
                            } else {
                                return 0
                            }
                        }
                    }
                }
            }
        }

        val dest = if (inPlace) null else output
        Optimizer.run(input, dest, inhabitedTimeSeconds, removeUnknown, progressMode)

        if (!inPlace) {
            output?.let { outDir ->
                if (zipOutput) {
                    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    val parent = outDir.parent ?: Path.of(".")
                    val zipPath = parent.resolve("$ts.zip")
                    println("开始压缩: ${outDir} → ${zipPath}")
                    zipDir(outDir, zipPath)
                    println("zip: $zipPath")
                    Files.walk(outDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
        return 0
    }

    private fun zipDir(src: Path, dstZip: Path) {
        ZipOutputStream(Files.newOutputStream(dstZip)).use { zos ->
            Files.walk(src).forEach { p ->
                val rel = src.relativize(p)
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
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val exit = CommandLine(Main()).execute(*args)
            if (exit != 0) System.exit(exit)
        }
    }
}
