package com.jokerhub.orzmc.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object TestPaths {
    private val root: Path = run {
        val cwd = Paths.get(System.getProperty("user.dir"))
        val p1 = cwd.resolve("core/src/test/resources/Fixtures")
        val p2 = cwd.resolve("src/test/resources/Fixtures")
        when {
            Files.exists(p1) -> p1
            Files.exists(p2) -> p2
            else -> p1
        }
    }
    fun world(): Path = root.resolve("world")
    fun worldDataChunks(): Path = world().resolve("data").resolve("chunks.dat")
    fun worldRegion(name: String): Path = world().resolve("region").resolve(name)
}
