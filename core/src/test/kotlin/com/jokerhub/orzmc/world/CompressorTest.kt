package com.jokerhub.orzmc.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipInputStream

class CompressorTest {
    private fun zipEntryNames(zipPath: Path): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zis.nextEntry
            }
        }
        return names
    }

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

        val names = zipEntryNames(zipPath)
        assertTrue(names.isNotEmpty())
        // 任何条目不得包含反斜杠（ZIP 规范要求正斜杠）
        assertTrue(names.none { it.contains('\\') }, "zip 条目含反斜杠: $names")
        // 嵌套路径以正斜杠呈现
        assertTrue(names.contains("DIM-1/data/chunks.dat"), "缺少正斜杠嵌套条目: $names")
        assertTrue(names.contains("DIM-1/data/Fortress_index.dat"))
        assertTrue(names.contains("level.dat"))
    }

    @Test
    fun `empty directory compresses into a valid empty zip`(
        @TempDir root: Path,
    ) {
        val zipPath = Compressor.compressToTimestampZip(root)
        assertTrue(Files.exists(zipPath), "an empty tree must still produce a zip")
        assertTrue(zipEntryNames(zipPath).isEmpty(), "empty tree must yield no entries")
    }

    @Test
    fun `root without a parent writes the archive outside the compressed tree`(
        @TempDir tmp: Path,
    ) {
        // A single-element relative path has parent == null; the fallback must still
        // place the archive OUTSIDE the walked tree (no self-inclusion).
        val name = "compressor-rootless-${UUID.randomUUID().toString().take(8)}"
        val root = Path.of(name)
        try {
            Files.createDirectories(root)
            Files.write(root.resolve("level.dat"), byteArrayOf(1, 2, 3))

            val zipPath = Compressor.compressToTimestampZip(root)

            // Parent is null -> archive lands in the absolute parent (the CWD), not inside root.
            assertFalse(zipPath.startsWith(root.toAbsolutePath()), "archive must not be inside the compressed tree")
            assertTrue(Files.exists(zipPath), "archive must exist next to the root")
            assertEquals(listOf("level.dat"), zipEntryNames(zipPath), "no self-inclusion allowed")
            Files.deleteIfExists(zipPath)
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `rapid same-second runs never lose the latest archive`(
        @TempDir tmp: Path,
    ) {
        // The archive is written next to [root], so root must live in an isolated parent
        // (the @TempDir) rather than being the @TempDir itself.
        val root = Files.createDirectories(tmp.resolve("world"))
        Files.createDirectories(root.resolve("data"))
        Files.write(root.resolve("data/first.dat"), "v1".toByteArray())
        Compressor.compressToTimestampZip(root)
        // Second run with different content; if it lands in the same second it overwrites
        // the first archive (documented behavior), otherwise it produces a second, newer one.
        Files.write(root.resolve("data/second.dat"), "v2".toByteArray())
        Compressor.compressToTimestampZip(root)

        val zips = Files.list(tmp).filter { it.fileName.toString().endsWith(".zip") }.toList()
        assertTrue(zips.isNotEmpty(), "at least one archive must exist")
        // The newest archive (lexicographically greatest timestamp) must contain the latest
        // content — never a partial/corrupt archive or a lost run.
        val newest = zips.maxByOrNull { it.fileName.toString() }
        assertNotNull(newest)
        val names = zipEntryNames(newest!!)
        assertTrue(names.contains("data/second.dat"), "newest archive must carry the second run's data: $names")
    }

    @Test
    fun `compression failure keeps the source intact`(
        @TempDir tmp: Path,
    ) {
        val root = Files.createDirectories(tmp.resolve("world"))
        Files.createDirectories(root.resolve("data"))
        Files.write(root.resolve("data/chunks.dat"), byteArrayOf(1, 2, 3))

        // Occupy the target archive path with a directory so Files.newOutputStream fails.
        // Retry across second boundaries: if a run crosses into the next second the name
        // changes, the collision disappears, and the run succeeds — retry with a fresh name.
        var failed = false
        repeat(5) {
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            val candidate = tmp.resolve("$ts.zip")
            Files.deleteIfExists(candidate)
            Files.createDirectory(candidate)
            try {
                Compressor.compressToTimestampZip(root)
                // Second boundary crossed: compression succeeded, no collision this run.
                Files.deleteIfExists(candidate)
            } catch (_: java.io.IOException) {
                failed = true
                return@repeat
            }
        }
        assertTrue(failed, "compress must throw when the target archive path is already a directory")
        assertTrue(
            Files.exists(root.resolve("data/chunks.dat")),
            "failed compression must leave the source tree untouched",
        )
    }
}
