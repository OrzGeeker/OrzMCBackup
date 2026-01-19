package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.mca.McaReader
import java.nio.file.Files
import java.nio.file.Path

object McaUtils {
    fun isValidMca(path: Path): Boolean {
        return try { Files.size(path) >= 8192 } catch (_: Exception) { false }
    }

    fun countTotalChunks(dims: List<Path>): Long {
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
}
