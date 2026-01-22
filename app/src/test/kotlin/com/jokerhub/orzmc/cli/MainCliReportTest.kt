package com.jokerhub.orzmc.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import com.jokerhub.orzmc.world.Cleaner

class MainCliReportTest {
    @Test
    fun `cli writes report file in json`() {
        val input = Files.createTempDirectory("cli-world-")
        Files.createDirectories(input.resolve("region"))
        val out = Files.createTempDirectory("cli-out-")
        val report = Files.createTempFile("cli-report-", ".json")
        val exit = CommandLine(Main()).execute(
            input.toString(),
            out.toString(),
            "-t", "0",
            "--progress-mode", "Off",
            "--force",
            "--report-file", report.toString(),
            "--report-format", "json"
        )
        assertTrue(exit == 0)
        val content = String(Files.readAllBytes(report), Charsets.UTF_8)
        assertTrue(content.contains("\"processedChunks\""))
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(report.parent, 5, 10)
    }
}
