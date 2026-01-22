package com.jokerhub.orzmc.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object TestPaths {
    private val worldPath: Path = run {
        val url = Thread.currentThread().contextClassLoader.getResource("Fixtures/world")
        if (url != null && url.protocol == "file") {
            Paths.get(url.toURI())
        } else {
            val cwd = Paths.get(System.getProperty("user.dir"))
            val candidate = if (cwd.fileName?.toString() == "core") {
                cwd.resolve("src/test/resources/Fixtures/world")
            } else {
                cwd.resolve("core/src/test/resources/Fixtures/world")
            }
            candidate
        }
    }
    fun world(): Path = worldPath
    fun worldDataChunks(): Path = world().resolve("data").resolve("chunks.dat")
    fun worldRegion(name: String): Path = world().resolve("region").resolve(name)
}
