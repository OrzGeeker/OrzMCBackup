package com.jokerhub.orzmc.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object TestTmp {
    fun createTempDirectory(prefix: String): Path {
        val base = Paths.get(System.getProperty("user.dir")).resolve("build").resolve("tmp")
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix)
    }
}
