package com.jokerhub.orzmc.world

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

class CompressorTest {
    @Test
    fun `zip entries use forward slash separators on any platform`(
        @TempDir root: Path,
    ) {
        // 构造嵌套目录（Windows 下 Path.relativize 会产生反斜杠）
        val dimData = root.resolve("DIM-1").resolve("data")
        Files.createDirectories(dimData)
        Files.write(dimData.resolve("chunks.dat"), byteArrayOf(1, 2, 3))
        Files.write(dimData.resolve("Fortress_index.dat"), byteArrayOf(4, 5))
        Files.write(root.resolve("level.dat"), byteArrayOf(9))

        val zipPath = Compressor.compressToTimestampZip(root)

        val names = mutableListOf<String>()
        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zis.nextEntry
            }
        }
        assertTrue(names.isNotEmpty())
        // 任何条目不得包含反斜杠（ZIP 规范要求正斜杠）
        assertTrue(names.none { it.contains('\\') }, "zip 条目含反斜杠: $names")
        // 嵌套路径以正斜杠呈现
        assertTrue(names.contains("DIM-1/data/chunks.dat"), "缺少正斜杠嵌套条目: $names")
        assertTrue(names.contains("DIM-1/data/Fortress_index.dat"))
        assertTrue(names.contains("level.dat"))
    }
}
