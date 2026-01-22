package com.jokerhub.orzmc.util

import com.jokerhub.orzmc.patterns.ChunkPattern
import com.jokerhub.orzmc.world.FileSystem
import com.jokerhub.orzmc.world.McaIOFactory
import java.nio.file.Path

object TestHelper {
    fun countRemoved(fs: FileSystem, dims: List<Path>, ioFactory: McaIOFactory, pattern: ChunkPattern): Long {
        var removed = 0L
        dims.forEach { dim ->
            val regionDir = dim.resolve("region")
            fs.list(regionDir).filter { it.toString().endsWith(".mca") }.forEach { p ->
                val r = ioFactory.openReader(fs, p)
                removed += r.entries().count { e -> !pattern.matches(e) }
            }
        }
        return removed
    }

    fun countEntries(fs: FileSystem, dims: List<Path>, ioFactory: McaIOFactory): Long {
        var total = 0L
        dims.forEach { dim ->
            val regionDir = dim.resolve("region")
            fs.list(regionDir).filter { it.toString().endsWith(".mca") }.forEach { p ->
                val r = ioFactory.openReader(fs, p)
                total += r.entries().size
            }
        }
        return total
    }
}
