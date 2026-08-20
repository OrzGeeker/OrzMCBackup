package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.Cleaner
import com.jokerhub.orzmc.world.LoggerSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path

/**
 * T3: the Off/Global/Region progress rendering branches in Main.kt were never asserted —
 * tests only verified the run completed. This captures the rendered lines through an
 * injected [LoggerSink] and pins what each mode actually prints.
 */
class MainCliProgressModeTest {
    private class CapturingLogger : LoggerSink {
        val lines = mutableListOf<String>()

        override fun info(msg: String) {
            lines.add(msg)
        }

        override fun warn(msg: String) {
            lines.add("[warn] $msg")
        }

        override fun error(msg: String) {
            lines.add("[error] $msg")
        }
    }

    private fun buildSingleEntryWorld(
        root: Path,
        inhabited: Long,
    ) {
        Files.createDirectories(root.resolve("region"))
        Files.write(
            root.resolve("region").resolve("r.0.0.mca"),
            McaMemoryBuilder.buildSingleEntryMca(0, inhabited, CompressionKind.RAW),
        )
    }

    private fun runWithProgressMode(
        input: Path,
        out: Path,
        mode: String,
        extra: List<String> = emptyList(),
    ): Pair<Int, CapturingLogger> {
        val logger = CapturingLogger()
        val main = Main()
        main.logger = logger
        val args =
            mutableListOf(
                input.toString(),
                out.toString(),
                "-t",
                "0",
                "--progress-mode",
                mode,
                "--progress-interval",
                "1",
                "--force",
            )
        args.addAll(extra)
        val exit = CommandLine(main).execute(*args.toTypedArray())
        return exit to logger
    }

    @Test
    fun `Off mode prints no progress lines`() {
        val input = Files.createTempDirectory("pm-off-input-")
        val out = Files.createTempDirectory("pm-off-out-")
        try {
            buildSingleEntryWorld(input, 1000)
            val (exit, logger) = runWithProgressMode(input, out, "Off")
            assertEquals(0, exit)
            assertTrue(
                logger.lines.none { it.contains("进度：") || it.contains("处理区块文件：") || it.contains("开始") },
                "Off mode must not print progress: ${logger.lines}",
            )
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `Global mode prints percentage progress lines`() {
        val input = Files.createTempDirectory("pm-global-input-")
        val out = Files.createTempDirectory("pm-global-out-")
        try {
            // A few chunks guarantee at least one ChunkProgress emit at interval 1.
            val chunks =
                List(50) {
                    McaMemoryBuilder.MemChunk(index = it, inhabited = 1000, kind = CompressionKind.RAW)
                }
            Files.createDirectories(input.resolve("region"))
            Files.write(input.resolve("region").resolve("r.0.0.mca"), McaMemoryBuilder.buildMca(chunks))

            val (exit, logger) = runWithProgressMode(input, out, "Global")
            assertEquals(0, exit)
            assertTrue(
                logger.lines.any { it.startsWith("进度：") },
                "Global mode must render 进度：X%（cur/tot）: ${logger.lines}",
            )
            assertTrue(
                logger.lines.any { Regex("^进度：\\d+%（\\d+/\\d+）$").matches(it) },
                "progress line must be well-formed: ${logger.lines.filter { it.startsWith("进度：") }}",
            )
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `Region mode prints per-region-file lines`() {
        val input = Files.createTempDirectory("pm-region-input-")
        val out = Files.createTempDirectory("pm-region-out-")
        try {
            buildSingleEntryWorld(input, 1000)
            val (exit, logger) = runWithProgressMode(input, out, "Region")
            assertEquals(0, exit)
            assertTrue(
                logger.lines.any { it.contains("处理区块文件：") },
                "Region mode must render per-region lines: ${logger.lines}",
            )
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `time-based progress interval option is accepted and emits global progress`() {
        val input = Files.createTempDirectory("pm-ms-input-")
        val out = Files.createTempDirectory("pm-ms-out-")
        try {
            // Enough chunks that >1ms elapses across the run, so the time-throttled
            // emitter (--progress-interval-ms 1) is guaranteed to fire at least once.
            val all = mutableListOf<McaMemoryBuilder.MemChunk>()
            for (r in 0 until 4) {
                Files.createDirectories(input.resolve("region"))
                for (i in 0 until 256) {
                    all.add(McaMemoryBuilder.MemChunk(index = i, inhabited = 1000, kind = CompressionKind.RAW))
                }
                Files.write(
                    input.resolve("region").resolve("r.$r.0.mca"),
                    McaMemoryBuilder.buildMca(all.toList()),
                )
                all.clear()
            }

            val (exit, logger) =
                runWithProgressMode(
                    input,
                    out,
                    "Global",
                    extra = listOf("--progress-interval-ms", "1"),
                )
            assertEquals(0, exit)
            assertTrue(
                logger.lines.any { it.startsWith("进度：") },
                "--progress-interval-ms must drive Global progress rendering: ${logger.lines}",
            )
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `Off mode still processes the world and exits zero`() {
        val input = Files.createTempDirectory("pm-off2-input-")
        val out = Files.createTempDirectory("pm-off2-out-")
        try {
            buildSingleEntryWorld(input, 1000)
            val (exit, logger) = runWithProgressMode(input, out, "Off")
            assertEquals(0, exit)
            assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")), "Off mode must still produce output")
            assertTrue(logger.lines.isEmpty(), "Off mode must print nothing: ${logger.lines}")
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }
}
