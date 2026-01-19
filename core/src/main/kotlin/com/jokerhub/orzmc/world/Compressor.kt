package com.jokerhub.orzmc.world

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Compressor {
    fun compressToTimestampZip(root: Path): Path {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val parent = root.parent ?: root
        val zipPath = parent.resolve("$ts.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            Files.walk(root).forEach { p ->
                val rel = root.relativize(p)
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
        return zipPath
    }
}
