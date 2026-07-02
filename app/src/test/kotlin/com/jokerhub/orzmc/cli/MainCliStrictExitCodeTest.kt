package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.world.Cleaner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path

class MainCliStrictExitCodeTest {
    private fun createWorldWithBadMca(): Pair<Path, Path> {
        val input = Files.createTempDirectory("cli-world-strict-")
        Files.createDirectories(input.resolve("region"))
        Files.write(input.resolve("region").resolve("r.bad.mca"), "bad".toByteArray(Charsets.UTF_8))
        val out = Files.createTempDirectory("cli-out-strict-")
        return input to out
    }

    @Test
    fun `strict mode returns non-zero exit`() {
        val (input, out) = createWorldWithBadMca()
        val exit =
            CommandLine(Main()).execute(
                input.toString(),
                out.toString(),
                "-t",
                "0",
                "--progress-mode",
                "Off",
                "--force",
                "--strict",
            )
        assertTrue(exit != 0)
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `non-strict mode returns zero with report errors`() {
        val (input, out) = createWorldWithBadMca()
        val report = Files.createTempFile("cli-report-", ".json")
        val exit =
            CommandLine(Main()).execute(
                input.toString(),
                out.toString(),
                "-t",
                "0",
                "--progress-mode",
                "Off",
                "--force",
                "--report-file",
                report.toString(),
                "--report-format",
                "json",
            )
        assertTrue(exit == 0)
        val content = String(Files.readAllBytes(report), Charsets.UTF_8)
        assertTrue(content.contains("\"errors\":["))
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
        Files.deleteIfExists(report)
    }
}
