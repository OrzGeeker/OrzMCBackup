package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.Cleaner
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
        Files.write(
            input.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
        )
        Files.createDirectories(input.resolve("entities"))
        Files.write(input.resolve("entities").resolve("ignore.txt"), "e".toByteArray(Charsets.UTF_8))
        Files.write(input.resolve("foo.txt"), "x".toByteArray(Charsets.UTF_8))
        Files.createDirectories(input.resolve("misc"))
        Files.write(input.resolve("misc").resolve("note.txt"), "y".toByteArray(Charsets.UTF_8))
        val out = Files.createTempDirectory("cli-out-copy-")
        return input to out
    }

    @Test
    fun `default copy-misc copies non-reserved files and folders`() {
        val (input, out) = createMinimalWorld()
        val exit =
            CommandLine(Main()).execute(
                input.toString(),
                out.toString(),
                "-t",
                "0",
                "--progress-mode",
                "Off",
                "--force",
            )
        assertTrue(exit == 0)
        assertTrue(Files.exists(out.resolve("foo.txt")))
        assertTrue(Files.exists(out.resolve("misc").resolve("note.txt")))
        assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")))
        // Non-.mca files inside reserved dirs (entities/poi/region) are misc and
        // must be preserved, not silently dropped.
        assertTrue(Files.exists(out.resolve("entities").resolve("ignore.txt")))
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `bare copy-misc flag enables misc copying`() {
        val (input, out) = createMinimalWorld()
        val exit =
            CommandLine(Main()).execute(
                input.toString(),
                out.toString(),
                "-t",
                "0",
                "--progress-mode",
                "Off",
                "--force",
                "--copy-misc",
            )
        assertTrue(exit == 0)
        assertTrue(Files.exists(out.resolve("foo.txt")))
        assertTrue(Files.exists(out.resolve("misc").resolve("note.txt")))
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `copy-misc=false does not copy non-reserved files and folders`() {
        val (input, out) = createMinimalWorld()
        val exit =
            CommandLine(Main()).execute(
                input.toString(),
                out.toString(),
                "-t",
                "0",
                "--progress-mode",
                "Off",
                "--force",
                "--copy-misc=false",
            )
        assertTrue(exit == 0)
        assertFalse(Files.exists(out.resolve("foo.txt")))
        assertFalse(Files.exists(out.resolve("misc").resolve("note.txt")))
        assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")))
        assertFalse(Files.exists(out.resolve("entities").resolve("ignore.txt")))
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }
}
