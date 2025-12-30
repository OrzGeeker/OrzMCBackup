package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.mca.McaWriter
import com.jokerhub.orzmc.patterns.ChunkPattern
import com.jokerhub.orzmc.patterns.InhabitedTimePattern
import com.jokerhub.orzmc.patterns.ListPattern
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class ProgressMode { Off, Global, Region }

object Optimizer {
    @JvmStatic
    fun run(
        input: Path,
        output: Path?,
        inhabitedThresholdSeconds: Long,
        removeUnknown: Boolean,
        progressMode: ProgressMode = ProgressMode.Region,
    ) {
        require(Files.isDirectory(input)) { "input must be directory" }
        val ticks = inhabitedThresholdSeconds * 20
        val out = output ?: Files.createTempDirectory("thanos-")
        if (Files.exists(out)) {
            require(Files.list(out).findFirst().isEmpty) { "output must be empty" }
        } else {
            Files.createDirectories(out)
        }
        val tasks = discoverDimensions(input)
        val totalChunks = countTotalChunks(tasks)
        var processedChunks = 0L
        var removedTotal = 0L

        tasks.forEach { dim ->
            val rel = input.relativize(dim)
            val targetDim = out.resolve(rel)
            Files.createDirectories(targetDim)
            val forced = parseForceLoaded(dim)
            val patterns: MutableList<ChunkPattern> = mutableListOf(
                ListPattern(forced),
                InhabitedTimePattern(ticks, removeUnknown)
            )
            val regionDir = dim.resolve("region")
            val entitiesDir = dim.resolve("entities")
            val poiDir = dim.resolve("poi")
            Files.createDirectories(targetDim.resolve("region"))
            if (Files.isDirectory(entitiesDir)) Files.createDirectories(targetDim.resolve("entities"))
            if (Files.isDirectory(poiDir)) Files.createDirectories(targetDim.resolve("poi"))

            Files.list(regionDir).use { stream ->
                stream.filter { p -> p.toString().endsWith(".mca") && isValidMca(p) }.forEach regionLoop@ { rf ->
                    val name = rf.fileName.toString()
                    val cr = try { McaReader.open(rf.toString()) } catch (_: Exception) { return@regionLoop }
                    val cw = McaWriter(targetDim.resolve("region").resolve(name).toString())
                    val efile = entitiesDir.resolve(name)
                    val pfile = poiDir.resolve(name)
                    val ew = if (Files.isRegularFile(efile) && isValidMca(efile)) McaWriter(targetDim.resolve("entities").resolve(name).toString()) else null
                    val pw = if (Files.isRegularFile(pfile) && isValidMca(pfile)) McaWriter(targetDim.resolve("poi").resolve(name).toString()) else null

                    var removed = 0L
                    val entries = try { cr.entries() } catch (_: Exception) { emptyList() }
                    for (entry in entries) {
                        var keep = false
                        for (p in patterns) {
                            try {
                                if (p.matches(entry)) { keep = true; break }
                            } catch (_: Exception) { }
                        }
                        if (keep) {
                            try { cw.writeEntry(entry) } catch (_: Exception) {}
                            // entities
                            try {
                                val er = if (Files.isRegularFile(efile) && isValidMca(efile)) McaReader.open(efile.toString()) else null
                                val eentry = er?.get(entry.regionIndex())
                                if (eentry != null && ew != null) ew.writeEntry(eentry)
                            } catch (_: Exception) {}
                            // poi
                            try {
                                val pr = if (Files.isRegularFile(pfile) && isValidMca(pfile)) McaReader.open(pfile.toString()) else null
                                val pentry = pr?.get(entry.regionIndex())
                                if (pentry != null && pw != null) pw.writeEntry(pentry)
                            } catch (_: Exception) {}
                        } else {
                            removed += 1
                            removedTotal += 1
                        }
                        processedChunks += 1
                        if (progressMode == ProgressMode.Global) {
                            val pct = (processedChunks * 100 / (totalChunks.coerceAtLeast(1))).toInt()
                            if (processedChunks % 100 == 0L) println("进度: $pct% ($processedChunks/$totalChunks)")
                        }
                    }
                    cw.finalizeFile()
                    ew?.finalizeFile()
                    pw?.finalizeFile()
                }
            }
        }
        if (output == null) {
            // in-place mode replacement
            tasks.forEach { dim ->
                val rel = input.relativize(dim)
                val outDim = out.resolve(rel)
                val inDim = input.resolve(rel)
                listOf("region", "entities", "poi").forEach dimLoop@ { name ->
                    val src = outDim.resolve(name)
                    if (!Files.isDirectory(src)) return@dimLoop
                    val dst = inDim.resolve(name)
                    Files.createDirectories(dst)
                    val keep = HashSet<String>()
                    Files.list(src).use { s -> s.filter { it.toString().endsWith(".mca") }.forEach { keep.add(it.fileName.toString()) } }
                    if (Files.isDirectory(dst)) {
                        Files.list(dst).use { s -> s.filter { it.toString().endsWith(".mca") }.forEach { p ->
                            if (!keep.contains(p.fileName.toString())) Files.deleteIfExists(p)
                        } }
                    }
                    Files.list(src).use { s -> s.filter { it.toString().endsWith(".mca") }.forEach { p ->
                        val target = dst.resolve(p.fileName.toString())
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                    } }
                }
            }
            Files.walk(out).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun isDimensionDir(path: Path): Boolean = Files.isDirectory(path.resolve("region"))

    private fun discoverDimensions(root: Path): List<Path> {
        val tasks = mutableListOf<Path>()
        if (isDimensionDir(root)) tasks.add(root)
        Files.list(root).use { s -> s.filter { Files.isDirectory(it) && isDimensionDir(it) }.forEach { tasks.add(it) } }
        Files.walk(root).use { s -> s.filter { Files.isDirectory(it) && isDimensionDir(it) }.forEach { p -> if (!tasks.contains(p)) tasks.add(p) } }
        return tasks
    }

    private fun countTotalChunks(dims: List<Path>): Long {
        var total = 0L
        for (dim in dims) {
            val regionDir = dim.resolve("region")
            if (!Files.isDirectory(regionDir)) continue
            Files.list(regionDir).use { s ->
                s.filter { it.toString().endsWith(".mca") && isValidMca(it) }.forEach { p ->
                    try {
                        val r = McaReader.open(p.toString())
                        total += r.entries().size
                    } catch (_: Exception) { }
                }
            }
        }
        return total
    }

    private fun isValidMca(path: Path): Boolean {
        return try {
            Files.size(path) >= 8192
        } catch (_: Exception) {
            false
        }
    }

    private fun parseForceLoaded(dimension: Path): List<Pair<Int, Int>> {
        val f = dimension.resolve("data").resolve("chunks.dat").toFile()
        if (!f.isFile) return emptyList()
        return try { NbtForceLoader.parse(f) } catch (_: Exception) { emptyList() }
    }
}
