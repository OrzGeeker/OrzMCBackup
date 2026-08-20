package com.jokerhub.orzmc.world

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Utilities for compressing world output directories. */
object Compressor {
    /** Compress [root] into a sibling ZIP file named `yyyyMMddHHmmss.zip`, then return its path. */
    fun compressToTimestampZip(root: Path): Path {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        // "Next to the root" must be a location outside the tree being compressed:
        // writing the archive inside [root] (root.parent == null, e.g. a single-element
        // relative path) would make the walk include the archive itself. Resolve the
        // absolute parent so the fallback is still outside the walked tree (T4).
        val parent = root.toAbsolutePath().parent ?: root
        val zipPath = parent.resolve("$ts.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            Files.walk(root).forEach { p ->
                val rel = root.relativize(p)
                if (rel.toString().isEmpty()) return@forEach
                if (!Files.isDirectory(p)) {
                    // ZIP 规范要求正斜杠作为目录分隔符；Windows 的 Path 用 '\'，
                    // 直接写入会让 Unix 解压工具把整条路径当作单个文件名。
                    zos.putNextEntry(ZipEntry(rel.toString().replace('\\', '/')))
                    Files.copy(p, zos)
                    zos.closeEntry()
                }
            }
        }
        return zipPath
    }
}
