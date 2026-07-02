package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.Cleaner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files

class MainCliReportTest {
    private fun buildSingleEntryMca(
        index: Int,
        inhabited: Long,
    ): ByteArray = McaMemoryBuilder.buildSingleEntryMca(index, inhabited, CompressionKind.RAW)

    private fun extractProcessedChunks(json: String): Long? {
        val m = Regex("\"processedChunks\":(\\d+)").find(json) ?: return null
        return m.groupValues[1].toLong()
    }

    @Test
    fun `cli writes report file in json`() {
        val input = Files.createTempDirectory("cli-world-")
        Files.createDirectories(input.resolve("region"))
        Files.write(input.resolve("region").resolve("r.0.0.mca"), buildSingleEntryMca(0, 1000))
        val out = Files.createTempDirectory("cli-out-")
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
        val processed = extractProcessedChunks(content)
        assertTrue(processed != null && processed > 0)
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
        Files.deleteIfExists(report)
    }
}
