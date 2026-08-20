package com.jokerhub.orzmc.world

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.DosFileAttributes

/**
 * Direct unit tests for [Cleaner] (T10): the DOS-attribute clearing and the
 * retrying tree delete previously only had indirect Windows e2e coverage that
 * silently skipped on non-Windows hosts.
 */
class CleanerTest {
    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    @Test
    fun `clearDosAttributes is a no-op on a plain file`() {
        val f = Files.createTempFile("cleaner-plain", ".txt")
        try {
            Cleaner.clearDosAttributes(f) // must not throw, file must survive
            assertTrue(Files.exists(f))
        } finally {
            Files.deleteIfExists(f)
        }
    }

    @Test
    fun `clearDosAttributes clears the read-only attribute on Windows`() {
        assumeTrue(isWindows())
        val f = Files.createTempFile("cleaner-ro", ".txt")
        try {
            Files.getFileAttributeView(f, DosFileAttributeView::class.java).setReadOnly(true)
            assertTrue(Files.readAttributes(f, DosFileAttributes::class.java).isReadOnly)
            Cleaner.clearDosAttributes(f)
            assertFalse(Files.readAttributes(f, DosFileAttributes::class.java).isReadOnly)
        } finally {
            Cleaner.clearDosAttributes(f)
            Files.deleteIfExists(f)
        }
    }

    @Test
    fun `deleteTreeWithRetry removes a tree containing a read-only file`() {
        val root = Files.createTempDirectory("cleaner-tree")
        val sub = root.resolve("sub")
        Files.createDirectories(sub)
        val f = sub.resolve("ro.txt")
        Files.write(f, byteArrayOf(1))
        f.toFile().setReadOnly()
        try {
            val ok = Cleaner.deleteTreeWithRetry(root, attempts = 5, sleepMs = 50)
            assertTrue(ok)
            assertFalse(Files.exists(root))
        } finally {
            // Best-effort cleanup if the assertion above failed.
            f.toFile().setWritable(true)
            root.toFile().setWritable(true)
            Cleaner.deleteTreeWithRetry(root, 3, 20)
        }
    }

    @Test
    fun `deleteTreeWithRetry on an absent path returns false`() {
        val root = Files.createTempDirectory("cleaner-absent")
        assertTrue(Files.deleteIfExists(root))
        // Files.walk on a missing root throws; the retry loop exhausts attempts and reports failure.
        assertFalse(Cleaner.deleteTreeWithRetry(root, attempts = 3, sleepMs = 10))
    }
}
