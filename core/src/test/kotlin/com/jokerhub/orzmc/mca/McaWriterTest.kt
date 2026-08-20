package com.jokerhub.orzmc.mca

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class McaWriterTest {
    @Test
    fun `write entries and finalize produces valid mca file`(
        @TempDir tempDir: Path,
    ) {
        val srcFile = tempDir.resolve("r.0.0.mca")
        val outFile = tempDir.resolve("out.mca")
        // Build MCA with entries at indices 0 and 5
        val mcaBytes =
            McaMemoryBuilder.buildMca(
                listOf(
                    McaMemoryBuilder.MemChunk(index = 0, inhabited = 1000, kind = CompressionKind.RAW),
                    McaMemoryBuilder.MemChunk(index = 5, inhabited = 2000, kind = CompressionKind.RAW),
                ),
            )
        java.nio.file.Files
            .write(srcFile, mcaBytes)

        // Keep reader open while writer writes (same pattern as DimensionProcessor.process())
        val reader = McaReader.open(srcFile.toString())
        try {
            val entry0 = reader.get(0) ?: throw AssertionError("entry 0 not found")
            val entry5 = reader.get(5) ?: throw AssertionError("entry 5 not found")

            val writer = McaWriter(outFile.toString())
            try {
                writer.writeEntry(entry0)
                writer.writeEntry(entry5)
                writer.finalizeFile()
            } finally {
                writer.close()
            }

            // Verify raw bytes were readable while reader was open
            assertTrue(entry0.serializedBytes().size > 5, "entry 0 raw bytes should have header+payload")
            assertTrue(entry5.serializedBytes().size > 5, "entry 5 raw bytes should have header+payload")
        } finally {
            reader.close()
        }

        // Verify output file size >= 8192
        val fileSize =
            java.nio.file.Files
                .size(outFile)
        assertTrue(fileSize >= 8192, "file should be at least 8192 bytes")

        // Verify header structure
        val raf = java.io.RandomAccessFile(outFile.toFile(), "r")
        try {
            val loc = ByteArray(4096)
            raf.readFully(loc)
            val time = ByteArray(4096)
            raf.readFully(time)

            // Index 0 should have valid offset/size
            val v0 = ByteBuffer.wrap(loc, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val off0 = (v0 ushr 8) * 4096
            val size0 = (v0 and 0xFF) * 4096
            assertTrue(off0 > 0, "offset for index 0 should be > 0")
            assertTrue(size0 > 0, "size for index 0 should be > 0")

            // Index 5 should also have valid offset/size
            val v5 = ByteBuffer.wrap(loc, 20, 4).order(ByteOrder.BIG_ENDIAN).int
            val off5 = (v5 ushr 8) * 4096
            val size5 = (v5 and 0xFF) * 4096
            assertTrue(off5 > 0, "offset for index 5 should be > 0")
            assertTrue(size5 > 0, "size for index 5 should be > 0")

            // Non-written indices should be zero
            val v1 = ByteBuffer.wrap(loc, 4, 4).order(ByteOrder.BIG_ENDIAN).int
            assertEquals(0, v1, "unused index 1 should have zero offset/size")

            // Both entries should have non-zero timestamps
            val ts0 = ByteBuffer.wrap(time, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val ts5 = ByteBuffer.wrap(time, 20, 4).order(ByteOrder.BIG_ENDIAN).int
            assertTrue(ts0 > 0, "timestamp for index 0 should be > 0")
            assertTrue(ts5 > 0, "timestamp for index 5 should be > 0")
        } finally {
            raf.close()
        }
    }

    @Test
    fun `write no entries produces empty mca`(
        @TempDir tempDir: Path,
    ) {
        val mcaPath = tempDir.resolve("r.empty.mca")
        val writer = McaWriter(mcaPath.toString())
        try {
            writer.finalizeFile()
        } finally {
            writer.close()
        }
        val fileSize =
            java.nio.file.Files
                .size(mcaPath)
        assertTrue(fileSize >= 8192, "empty mca should be at least 8192 bytes (header only)")

        // All offsets should be zero
        val raf = java.io.RandomAccessFile(mcaPath.toFile(), "r")
        try {
            val loc = ByteArray(4096)
            raf.readFully(loc)
            for (i in 0 until 1024) {
                val v = ByteBuffer.wrap(loc, i * 4, 4).order(ByteOrder.BIG_ENDIAN).int
                assertEquals(0, v, "index $i should have zero offset in empty mca")
            }
        } finally {
            raf.close()
        }
    }

    @Test
    fun `writer with syncOnFinalize disabled still produces a valid mca`(
        @TempDir tempDir: Path,
    ) {
        val srcFile = tempDir.resolve("r.0.0.mca")
        val outFile = tempDir.resolve("out-nosync.mca")
        val raw = McaMemoryBuilder.buildSingleEntryMca(3, 1234, CompressionKind.ZLIB)
        java.nio.file.Files
            .write(srcFile, raw)

        val reader = McaReader.open(srcFile.toString())
        try {
            val entry = reader.get(3) ?: throw AssertionError("entry 3 not found")
            val writer = McaWriter(outFile.toString(), syncOnFinalize = false)
            try {
                writer.writeEntry(entry)
                writer.finalizeFile()
            } finally {
                writer.close()
            }
        } finally {
            reader.close()
        }

        val readBackFile = tempDir.resolve("r.3.0.mca")
        java.nio.file.Files
            .copy(outFile, readBackFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val readBackReader = McaReader.open(readBackFile.toString())
        try {
            assertEquals(1, readBackReader.count())
            val readBack = readBackReader.get(3) ?: throw AssertionError("entry 3 not found")
            assertEquals(3, readBack.regionIndex())
        } finally {
            readBackReader.close()
        }
    }

    @Test
    fun `count reports the number of used chunk slots`(
        @TempDir tempDir: Path,
    ) {
        val mcaBytes =
            McaMemoryBuilder.buildMca(
                listOf(
                    McaMemoryBuilder.MemChunk(index = 0, inhabited = 1000, kind = CompressionKind.RAW),
                    McaMemoryBuilder.MemChunk(index = 5, inhabited = 2000, kind = CompressionKind.RAW),
                ),
            )
        val file = tempDir.resolve("r.0.0.mca")
        java.nio.file.Files
            .write(file, mcaBytes)
        val reader = McaReader.open(file.toString())
        try {
            assertEquals(2, reader.count())
            assertEquals(reader.entries().size, reader.count())
        } finally {
            reader.close()
        }
    }

    @Test
    fun `written file can be read back by McaReader`(
        @TempDir tempDir: Path,
    ) {
        val srcFile = tempDir.resolve("r.0.0.mca")
        val outFile = tempDir.resolve("out.mca")
        val rawEntryBytes = McaMemoryBuilder.buildSingleEntryMca(5, 5000, CompressionKind.ZLIB)
        java.nio.file.Files
            .write(srcFile, rawEntryBytes)

        // Keep reader open while writer writes
        val reader = McaReader.open(srcFile.toString())
        try {
            val entry = reader.get(5) ?: throw AssertionError("entry not found")

            val writer = McaWriter(outFile.toString())
            try {
                writer.writeEntry(entry)
                writer.finalizeFile()
            } finally {
                writer.close()
            }
        } finally {
            reader.close()
        }

        // Copy to valid MCA filename for McaReader and read back
        val readBackFile = tempDir.resolve("r.0.0.mca")
        java.nio.file.Files
            .copy(outFile, readBackFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val readBackReader = McaReader.open(readBackFile.toString())
        try {
            assertEquals(1, readBackReader.count(), "count should report the used slots")
            val entries = readBackReader.entries()
            assertEquals(1, entries.size, "should have one entry")

            val readBack = entries.first()
            assertEquals(5, readBack.regionIndex())
            assertEquals(5, readBack.xPos()) // index 5, x = 5 % 32 = 5
            assertEquals(0, readBack.zPos()) // index 5, z = 5 / 32 = 0
            assertEquals(0 * 32 + 5, readBack.globalX())
            assertEquals(0, readBack.globalZ())
        } finally {
            readBackReader.close()
        }
    }
}
