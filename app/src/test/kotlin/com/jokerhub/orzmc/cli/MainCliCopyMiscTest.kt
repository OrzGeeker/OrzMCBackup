package com.jokerhub.orzmc.cli

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path

class MainCliCopyMiscTest {
    private fun createMinimalWorld(): Pair<Path, Path> {
        val input = Files.createTempDirectory("cli-world-copy-")
        Files.createDirectories(input.resolve("region"))
        // minimal MCA placeholder (size >= 8192)
        Files.write(input.resolve("region").resolve("r.0.0.mca"), ByteArray(8192))
        // misc files and folders to be copied
        Files.write(input.resolve("foo.txt"), "x".toByteArray(Charsets.UTF_8))
        Files.createDirectories(input.resolve("misc"))
        Files.write(input.resolve("misc").resolve("note.txt"), "y".toByteArray(Charsets.UTF_8))
        val out = Files.createTempDirectory("cli-out-copy-")
        return input to out
    }

    @Test
    fun `default copy-misc copies non-reserved files and folders`() {
        val (input, out) = createMinimalWorld()
        val exit = CommandLine(Main()).execute(
            input.toString(),
            out.toString(),
            "-t", "0",
            "--progress-mode", "Off",
            "--force"
        )
        assertTrue(exit == 0)
        assertTrue(Files.exists(out.resolve("foo.txt")))
        assertTrue(Files.exists(out.resolve("misc").resolve("note.txt")))
        // region should be present as normal output
        assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")))
        Files.walk(out).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `copy-misc=false does not copy non-reserved files and folders`() {
        val (input, out) = createMinimalWorld()
        val exit = CommandLine(Main()).execute(
            input.toString(),
            out.toString(),
            "-t", "0",
            "--progress-mode", "Off",
            "--force",
            "--copy-misc=false"
        )
        assertTrue(exit == 0)
        assertFalse(Files.exists(out.resolve("foo.txt")))
        assertFalse(Files.exists(out.resolve("misc").resolve("note.txt")))
        // region should be present as normal output
        assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")))
        Files.walk(out).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
