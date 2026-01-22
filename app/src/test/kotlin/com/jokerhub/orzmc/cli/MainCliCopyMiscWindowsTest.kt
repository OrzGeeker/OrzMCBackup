package com.jokerhub.orzmc.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView

class MainCliCopyMiscWindowsTest {
    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")

    private fun createWorldWithAttributes(): Pair<Path, Path> {
        val input = Files.createTempDirectory("cli-world-copy-win-")
        Files.createDirectories(input.resolve("region"))
        // minimal MCA placeholder (size >= 8192)
        Files.write(input.resolve("region").resolve("r.0.0.mca"), ByteArray(8192))
        // nested misc content
        val misc = input.resolve("misc").resolve("sub").resolve("inner")
        Files.createDirectories(misc)
        val ro = misc.resolve("readonly.txt")
        Files.write(ro, "ro".toByteArray(Charsets.UTF_8))
        val hidden = input.resolve("misc").resolve("hidden.txt")
        Files.write(hidden, "hidden".toByteArray(Charsets.UTF_8))
        if (isWindows()) {
            // set DOS readonly and hidden attributes where supported
            val roView = Files.getFileAttributeView(ro, DosFileAttributeView::class.java)
            roView?.setReadOnly(true)
            val hiddenView = Files.getFileAttributeView(hidden, DosFileAttributeView::class.java)
            hiddenView?.setHidden(true)
        } else {
            // best effort: mark readonly via POSIX if available
            try { input.resolve("misc").toFile().setWritable(false) } catch (_: Exception) {}
        }
        val out = Files.createTempDirectory("cli-out-copy-win-")
        return input to out
    }

    @Test
    fun `copy-misc copies nested and attribute-marked files on Windows`() {
        val (input, out) = createWorldWithAttributes()
        val exit = CommandLine(Main()).execute(
            input.toString(),
            out.toString(),
            "-t", "0",
            "--progress-mode", "Off",
            "--force"
        )
        assertTrue(exit == 0)
        // verify nested files copied
        assertTrue(Files.exists(out.resolve("misc").resolve("sub").resolve("inner").resolve("readonly.txt")))
        assertTrue(Files.exists(out.resolve("misc").resolve("hidden.txt")))
        // region output exists
        assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")))
        // cleanup (attempt to clear DOS readonly if present)
        Files.walk(out).sorted(Comparator.reverseOrder()).forEach { p ->
            try {
                val v = Files.getFileAttributeView(p, DosFileAttributeView::class.java)
                v?.setReadOnly(false)
            } catch (_: Exception) {}
            try { Files.deleteIfExists(p) } catch (_: Exception) {}
        }
    }
}
