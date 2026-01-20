package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.mca.McaReader
import java.nio.file.Files
import java.nio.file.Path

object McaUtils {
    fun isValidMca(fs: FileSystem, path: Path): Boolean {
        return try {
            Files.size(fs.toRealPath(path)) >= 8192
        } catch (_: Exception) {
            false
        }
    }

    fun countTotalChunks(fs: FileSystem, dims: List<Path>): Long {
        var total = 0L
        for (dim in dims) {
            val regionDir = dim.resolve("region")
            if (!fs.isDirectory(regionDir)) continue
            fs.list(regionDir).filter { it.toString().endsWith(".mca") && isValidMca(fs, it) }.forEach { p ->
                try {
                    val r = McaReader.open(fs.toRealPath(p).toString())
                    total += r.entries().size
                } catch (_: Exception) {
                }
            }
        }
        return total
    }
}
