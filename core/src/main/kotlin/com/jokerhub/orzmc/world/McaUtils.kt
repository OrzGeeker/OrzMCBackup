package com.jokerhub.orzmc.world

import java.nio.file.Path

/** Utilities for inspecting MCA region files. */
object McaUtils {
    fun isValidMca(
        fs: FileSystem,
        path: Path,
    ): Boolean =
        try {
            fs.size(path) >= 8192
        } catch (_: Exception) {
            false
        }

    fun countTotalChunks(
        fs: FileSystem,
        ioFactory: McaIOFactory,
        dims: List<Path>,
        onError: ((Path, String, String) -> Unit)? = null,
    ): Long {
        var total = 0L
        for (dim in dims) {
            val regionDir = dim.resolve("region")
            if (!fs.isDirectory(regionDir)) continue
            fs.list(regionDir).filter { it.toString().endsWith(".mca") && isValidMca(fs, it) }.forEach { p ->
                try {
                    ioFactory.openReader(fs, p).use { r ->
                        total += r.count()
                    }
                } catch (e: Exception) {
                    onError?.invoke(p, "MCA", "Failed to count chunks: ${e.message}")
                }
            }
        }
        return total
    }
}
