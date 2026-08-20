package com.jokerhub.orzmc.mca

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

class RandomAccessTest {
    private fun open(
        tempDir: Path,
        payload: ByteArray,
    ): BufferedRafAccess {
        val file = tempDir.resolve("region.bin")
        Files.write(file, payload)
        val raf = RandomAccessFile(file.toFile(), "r")
        return BufferedRafAccess(RafAccess(raf), payload.size.toLong())
    }

    @Test
    fun `large read bypasses the buffer and returns exact bytes`(
        @TempDir tempDir: Path,
    ) {
        val payload = ByteArray(20_000) { (it % 251).toByte() }
        val ra = open(tempDir, payload)
        ra.seek(0)
        val dst = ByteArray(payload.size)
        ra.readFully(dst, 0, dst.size)
        assertArrayEquals(payload, dst)
        ra.close()
    }

    @Test
    fun `sequential large reads continue from the correct position`(
        @TempDir tempDir: Path,
    ) {
        val payload = ByteArray(30_000) { (it % 251).toByte() }
        val ra = open(tempDir, payload)
        ra.seek(0)
        val a = ByteArray(10_000)
        val b = ByteArray(10_000)
        ra.readFully(a, 0, a.size)
        ra.readFully(b, 0, b.size)
        assertArrayEquals(payload.copyOfRange(0, 10_000), a)
        assertArrayEquals(payload.copyOfRange(10_000, 20_000), b)
        ra.close()
    }

    @Test
    fun `small read after a large read continues from the right position`(
        @TempDir tempDir: Path,
    ) {
        val payload = ByteArray(12_000) { (it % 251).toByte() }
        val ra = open(tempDir, payload)
        ra.seek(0)
        val big = ByteArray(10_000)
        val small = ByteArray(1_000)
        ra.readFully(big, 0, big.size)
        ra.readFully(small, 0, small.size)
        assertArrayEquals(payload.copyOfRange(0, 10_000), big)
        assertArrayEquals(payload.copyOfRange(10_000, 11_000), small)
        ra.close()
    }

    @Test
    fun `re-seek after a bypass read reloads the buffer`(
        @TempDir tempDir: Path,
    ) {
        val payload = ByteArray(20_000) { (it % 251).toByte() }
        val ra = open(tempDir, payload)
        ra.seek(0)
        val big = ByteArray(20_000)
        ra.readFully(big, 0, big.size)
        ra.seek(0)
        val prefix = ByteArray(100)
        ra.readFully(prefix, 0, prefix.size)
        assertArrayEquals(payload.copyOfRange(0, 100), prefix)
        ra.close()
    }

    @Test
    fun `read past the end of file throws EOF`(
        @TempDir tempDir: Path,
    ) {
        val payload = ByteArray(8_000) { (it % 251).toByte() }
        val ra = open(tempDir, payload)
        ra.seek(payload.size.toLong() - 100)
        val dst = ByteArray(200)
        assertThrows(java.io.EOFException::class.java) { ra.readFully(dst, 0, dst.size) }
        ra.close()
    }

    @Test
    fun `mixed large and buffered reads match the underlying data`(
        @TempDir tempDir: Path,
    ) {
        val payload = ByteArray(40_000) { (it % 251).toByte() }
        val ra = open(tempDir, payload)
        ra.seek(0)
        val out = ByteArray(payload.size)
        var off = 0
        // Alternating large (>= 8192) and small reads must reproduce the whole file.
        val plan = intArrayOf(10_000, 100, 8_192, 4096, 12_000, 500, 5_112)
        for (n in plan) {
            ra.readFully(out, off, n)
            off += n
        }
        assertArrayEquals(payload, out)
        ra.close()
    }
}
