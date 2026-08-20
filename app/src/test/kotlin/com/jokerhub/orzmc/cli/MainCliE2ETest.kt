package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.Cleaner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * In-JVM end-to-end tests for the backup/merge CLI semantics that were only ever
 * guarded by the uncommitted `cli_tests.ps1` script (T1). Covering: dry-run,
 * zip-output, csv report, unknown report-format fallback, force semantics,
 * missing output parameter, in-place replacement, classic-layout nested dimension
 * data preservation, and merge error paths (alias refusal, damaged patch fallback).
 */
class MainCliE2ETest {
    private fun buildSingleEntryWorld(
        root: Path,
        inhabited: Long,
        name: String = "r.0.0.mca",
    ) {
        Files.createDirectories(root.resolve("region"))
        Files.write(
            root.resolve("region").resolve(name),
            McaMemoryBuilder.buildSingleEntryMca(0, inhabited, CompressionKind.RAW),
        )
    }

    private fun slotCount(path: Path): Int {
        val header = Files.readAllBytes(path).copyOfRange(0, 4096)
        var n = 0
        for (i in 0 until 1024) {
            val o0 = header[i * 4]
            val o1 = header[i * 4 + 1]
            val o2 = header[i * 4 + 2]
            if (o0 != 0.toByte() || o1 != 0.toByte() || o2 != 0.toByte()) n++
        }
        return n
    }

    @Test
    fun `backup dry run writes no output and exits zero`() {
        val input = Files.createTempDirectory("cli-dry-input-")
        val out = Files.createTempDirectory("cli-dry-out-").resolve("out")
        try {
            buildSingleEntryWorld(input, 1000)
            val exit =
                CommandLine(Main()).execute(
                    input.toString(),
                    out.toString(),
                    "-t",
                    "0",
                    "--dry-run",
                    "--progress-mode",
                    "Off",
                )
            assertEquals(0, exit)
            assertFalse(Files.exists(out), "dry-run must not create the output directory")
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `backup zip output produces timestamped zip and removes output dir`() {
        val input = Files.createTempDirectory("cli-zip-input-")
        val outParent = Files.createTempDirectory("cli-zip-parent-")
        val out = outParent.resolve("out")
        try {
            buildSingleEntryWorld(input, 1000)
            val exit =
                CommandLine(Main()).execute(
                    input.toString(),
                    out.toString(),
                    "-t",
                    "0",
                    "--zip-output",
                    "--progress-mode",
                    "Off",
                    "--force",
                )
            assertEquals(0, exit)
            assertFalse(Files.exists(out), "output dir must be removed after zipping")
            val zips =
                Files
                    .list(outParent)
                    .filter { it.fileName.toString().endsWith(".zip") }
                    .collect(Collectors.toList())
            assertEquals(1, zips.size, "exactly one timestamped zip expected in the output parent")
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(outParent, 5, 10)
        }
    }

    @Test
    fun `backup writes csv report file`() {
        val input = Files.createTempDirectory("cli-csv-input-")
        val out = Files.createTempDirectory("cli-csv-out-")
        val report = Files.createTempDirectory("cli-csv-rep-").resolve("report.csv")
        try {
            buildSingleEntryWorld(input, 1000)
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
                    "csv",
                )
            assertEquals(0, exit)
            assertTrue(Files.exists(report), "csv report file should exist")
            val text = Files.readString(report)
            assertTrue(text.startsWith("processedChunks,removedChunks,errorsCount"))
            assertTrue(Regex("\n\\d+,\\d+,0\n").containsMatchIn(text), "csv should carry a stats row")
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
            Files.deleteIfExists(report)
        }
    }

    @Test
    fun `unknown report format silently falls back to json`() {
        // Documented current behavior (ReportIO.write): unknown formats fall back to JSON.
        val input = Files.createTempDirectory("cli-fmt-input-")
        val out = Files.createTempDirectory("cli-fmt-out-")
        val report = Files.createTempDirectory("cli-fmt-rep-").resolve("report.xml")
        try {
            buildSingleEntryWorld(input, 1000)
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
                    "xml",
                )
            assertEquals(0, exit)
            assertTrue(Files.exists(report))
            assertTrue(
                Files.readString(report).contains("\"processedChunks\":"),
                "unknown format must fall back to JSON",
            )
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
            Files.deleteIfExists(report)
        }
    }

    @Test
    fun `backup refuses non-empty output without force`() {
        // Exit semantics: errors only fail the run under --strict (see MainCliStrictExitCodeTest).
        val input = Files.createTempDirectory("cli-force-input-")
        val out = Files.createTempDirectory("cli-force-out-")
        try {
            buildSingleEntryWorld(input, 1000)
            Files.write(out.resolve("keep.txt"), "x".toByteArray())
            val exit =
                CommandLine(Main()).execute(
                    input.toString(),
                    out.toString(),
                    "-t",
                    "0",
                    "--progress-mode",
                    "Off",
                    "--strict",
                )
            assertEquals(1, exit)
            assertTrue(Files.exists(out.resolve("keep.txt")), "output must be left untouched without --force")
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `backup missing output parameter fails under strict`() {
        val input = Files.createTempDirectory("cli-noout-input-")
        try {
            buildSingleEntryWorld(input, 1000)
            val exit =
                CommandLine(Main()).execute(
                    input.toString(),
                    "-t",
                    "0",
                    "--progress-mode",
                    "Off",
                    "--strict",
                )
            assertEquals(1, exit, "backup without OUTPUT_DIR and without --in-place must fail under --strict")
            assertTrue(Files.exists(input.resolve("region").resolve("r.0.0.mca")), "input must be left untouched")
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
        }
    }

    @Test
    fun `backup in place replaces input and drops fully removed regions`() {
        val input = Files.createTempDirectory("cli-inplace-input-")
        try {
            buildSingleEntryWorld(input, 1000, "r.0.0.mca") // kept: inhabited > threshold 0
            buildSingleEntryWorld(input, 0, "r.1.0.mca") // removed: inhabited is not > 0
            val exit =
                CommandLine(Main()).execute(
                    input.toString(),
                    "-t",
                    "0",
                    "--in-place",
                    "--progress-mode",
                    "Off",
                )
            assertEquals(0, exit)
            assertTrue(Files.exists(input.resolve("region").resolve("r.0.0.mca")), "kept region must be written back")
            assertFalse(
                Files.exists(input.resolve("region").resolve("r.1.0.mca")),
                "fully removed region must be dropped",
            )
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
        }
    }

    @Test
    fun `backup classic layout nested dimension data files are preserved`() {
        val input = Files.createTempDirectory("cli-classic-input-")
        val out = Files.createTempDirectory("cli-classic-out-")
        try {
            // World root is itself a dimension and nests the nether/end as DIM-1/DIM1,
            // each carrying its own data/ folder (classic pre-26.1 layout).
            buildSingleEntryWorld(input, 1000)
            Files.createDirectories(input.resolve("data"))
            Files.write(input.resolve("data").resolve("root-data.dat"), "root".toByteArray())
            val nested =
                mapOf(
                    "DIM-1" to listOf("fortress_index.dat", "chunks.dat", "world_border.dat"),
                    "DIM1" to listOf("endcity_index.dat", "raids_end.dat"),
                )
            for ((dim, dataFiles) in nested) {
                val d = input.resolve(dim)
                Files.createDirectories(d.resolve("region"))
                Files.write(
                    d.resolve("region").resolve("r.0.0.mca"),
                    McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW),
                )
                Files.createDirectories(d.resolve("data"))
                dataFiles.forEach { name -> Files.write(d.resolve("data").resolve(name), "x".toByteArray()) }
            }

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
            assertEquals(0, exit)
            assertTrue(Files.exists(out.resolve("data/root-data.dat")), "root dimension data/ should be preserved")
            assertTrue(Files.exists(out.resolve("DIM-1/data/fortress_index.dat")), "DIM-1 fortress_index.dat missing")
            assertTrue(Files.exists(out.resolve("DIM-1/data/chunks.dat")), "DIM-1 chunks.dat missing")
            assertTrue(Files.exists(out.resolve("DIM-1/data/world_border.dat")), "DIM-1 world_border.dat missing")
            assertTrue(Files.exists(out.resolve("DIM1/data/endcity_index.dat")), "DIM1 endcity_index.dat missing")
            assertTrue(Files.exists(out.resolve("DIM1/data/raids_end.dat")), "DIM1 raids_end.dat missing")
            assertTrue(Files.exists(out.resolve("DIM-1/region/r.0.0.mca")), "DIM-1 region should be rewritten")
            assertTrue(Files.exists(out.resolve("DIM1/region/r.0.0.mca")), "DIM1 region should be rewritten")
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `merge refuses alias base equal to output`() {
        val base = Files.createTempDirectory("cli-alias-base-")
        val patch = Files.createTempDirectory("cli-alias-patch-")
        try {
            buildSingleEntryWorld(base, 1000)
            buildSingleEntryWorld(patch, 1000)
            val exit =
                CommandLine(MergeCommand()).execute(
                    base.toString(),
                    patch.toString(),
                    base.toString(), // output aliases base
                    "--force",
                    "--progress-mode",
                    "Off",
                )
            assertNotEquals(0, exit, "merge with output == base must be refused")
            assertTrue(Files.exists(base.resolve("region").resolve("r.0.0.mca")), "base must be left untouched")
        } finally {
            Cleaner.deleteTreeWithRetry(base, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
        }
    }

    @Test
    fun `merge damaged patch region falls back to base copy`() {
        val base = Files.createTempDirectory("cli-dmg-base-")
        val patch = Files.createTempDirectory("cli-dmg-patch-")
        val out = Files.createTempDirectory("cli-dmg-out-")
        try {
            buildSingleEntryWorld(base, 1000)
            Files.createDirectories(patch.resolve("region"))
            Files.write(patch.resolve("region").resolve("r.0.0.mca"), "bad".toByteArray())

            val exit =
                CommandLine(MergeCommand()).execute(
                    base.toString(),
                    patch.toString(),
                    out.toString(),
                    "--force",
                    "--progress-mode",
                    "Off",
                )
            assertEquals(0, exit)
            // WorldMerger preserves the base's relative layout; base/patch here are classic-layout,
            // so the merged region lands back under <out>/region/r.0.0.mca.
            val outRegion = out.resolve("region").resolve("r.0.0.mca")
            assertTrue(Files.exists(outRegion), "output region must be produced from the base fallback")
            assertEquals(1, slotCount(outRegion), "base slot must survive via copyBaseIfPresent")
        } finally {
            Cleaner.deleteTreeWithRetry(base, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `unknown progress mode is rejected by picocli`() {
        val input = Files.createTempDirectory("cli-pmode-input-")
        val out = Files.createTempDirectory("cli-pmode-out-")
        try {
            buildSingleEntryWorld(input, 1000)
            val exit =
                CommandLine(Main()).execute(
                    input.toString(),
                    out.toString(),
                    "-t",
                    "0",
                    "--progress-mode",
                    "Bogus",
                )
            assertNotEquals(0, exit, "an unknown --progress-mode must fail argument parsing")
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `backup remove-unknown removes external chunks and keeps them by default`() {
        val input = Files.createTempDirectory("cli-ext-input-")
        val outDefault = Files.createTempDirectory("cli-ext-out-default-")
        val outRemove = Files.createTempDirectory("cli-ext-out-remove-")
        try {
            Files.createDirectories(input.resolve("region"))
            Files.write(
                input.resolve("region").resolve("r.0.0.mca"),
                McaMemoryBuilder.buildSingleEntryMca(0, 1_000_000, CompressionKind.EXT_ZLIB),
            )

            // Without --remove-unknown the external chunk must survive (1 slot).
            val exitKeep =
                CommandLine(Main()).execute(
                    input.toString(),
                    outDefault.toString(),
                    "-t",
                    "0",
                    "--progress-mode",
                    "Off",
                    "--force",
                )
            assertEquals(0, exitKeep)
            assertEquals(
                1,
                slotCount(outDefault.resolve("region").resolve("r.0.0.mca")),
                "external chunk must be kept without --remove-unknown",
            )

            // With --remove-unknown the external-only region must be dropped entirely.
            val exitRemove =
                CommandLine(Main()).execute(
                    input.toString(),
                    outRemove.toString(),
                    "-t",
                    "0",
                    "--remove-unknown",
                    "--progress-mode",
                    "Off",
                    "--force",
                )
            assertEquals(0, exitRemove)
            assertFalse(
                Files.exists(outRemove.resolve("region").resolve("r.0.0.mca")),
                "external-only region must be dropped under --remove-unknown",
            )
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(outDefault, 5, 10)
            Cleaner.deleteTreeWithRetry(outRemove, 5, 10)
        }
    }

    @Test
    fun `backup with parallelism greater than one produces complete output`() {
        val input = Files.createTempDirectory("cli-par-input-")
        val out = Files.createTempDirectory("cli-par-out-")
        try {
            Files.createDirectories(input.resolve("region"))
            Files.write(
                input.resolve("region").resolve("r.0.0.mca"),
                McaMemoryBuilder.buildMca(
                    listOf(
                        McaMemoryBuilder.MemChunk(index = 0, inhabited = 1000, kind = CompressionKind.RAW),
                        McaMemoryBuilder.MemChunk(index = 1, inhabited = 0, kind = CompressionKind.RAW),
                    ),
                ),
            )
            Files.write(
                input.resolve("region").resolve("r.1.0.mca"),
                McaMemoryBuilder.buildMca(
                    listOf(
                        McaMemoryBuilder.MemChunk(index = 0, inhabited = 0, kind = CompressionKind.RAW),
                        McaMemoryBuilder.MemChunk(index = 1, inhabited = 1000, kind = CompressionKind.RAW),
                    ),
                ),
            )

            val exit =
                CommandLine(Main()).execute(
                    input.toString(),
                    out.toString(),
                    "-t",
                    "0",
                    "--parallelism",
                    "4",
                    "--progress-mode",
                    "Off",
                    "--force",
                )
            assertEquals(0, exit)
            assertEquals(1, slotCount(out.resolve("region").resolve("r.0.0.mca")), "r.0.0 must keep its one chunk")
            assertEquals(1, slotCount(out.resolve("region").resolve("r.1.0.mca")), "r.1.0 must keep its one chunk")
        } finally {
            Cleaner.deleteTreeWithRetry(input, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }

    @Test
    fun `merge with parallelism produces byte-identical output to sequential`() {
        val base = Files.createTempDirectory("cli-mpar-base-")
        val patch = Files.createTempDirectory("cli-mpar-patch-")
        val outSeq = Files.createTempDirectory("cli-mpar-seq-")
        val outPar = Files.createTempDirectory("cli-mpar-par-")
        try {
            fun buildWorld(root: Path) {
                Files.createDirectories(root.resolve("region"))
                for (r in 0 until 4) {
                    Files.write(
                        root.resolve("region").resolve("r.$r.0.mca"),
                        McaMemoryBuilder.buildMca(
                            listOf(
                                McaMemoryBuilder.MemChunk(index = 0, inhabited = 1000, kind = CompressionKind.RAW),
                                McaMemoryBuilder.MemChunk(index = 1, inhabited = 100, kind = CompressionKind.ZLIB),
                            ),
                        ),
                    )
                }
            }
            buildWorld(base)
            buildWorld(patch)

            val exitSeq =
                CommandLine(MergeCommand()).execute(
                    base.toString(),
                    patch.toString(),
                    outSeq.toString(),
                    "--force",
                    "--progress-mode",
                    "Off",
                )
            val exitPar =
                CommandLine(MergeCommand()).execute(
                    base.toString(),
                    patch.toString(),
                    outPar.toString(),
                    "--parallelism",
                    "4",
                    "--force",
                    "--progress-mode",
                    "Off",
                )
            assertEquals(0, exitSeq)
            assertEquals(0, exitPar)
            for (r in 0 until 4) {
                val a = Files.readAllBytes(outSeq.resolve("region").resolve("r.$r.0.mca"))
                val b = Files.readAllBytes(outPar.resolve("region").resolve("r.$r.0.mca"))
                assertTrue(a.contentEquals(b), "parallel merge must be byte-identical to sequential for r.$r.0.mca")
            }
        } finally {
            Cleaner.deleteTreeWithRetry(base, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
            Cleaner.deleteTreeWithRetry(outSeq, 5, 10)
            Cleaner.deleteTreeWithRetry(outPar, 5, 10)
        }
    }

    @Test
    fun `merge rejects unknown progress mode`() {
        val base = Files.createTempDirectory("cli-mpm-base-")
        val patch = Files.createTempDirectory("cli-mpm-patch-")
        val out = Files.createTempDirectory("cli-mpm-out-")
        try {
            buildSingleEntryWorld(base, 1000)
            buildSingleEntryWorld(patch, 1000)
            val exit =
                CommandLine(MergeCommand()).execute(
                    base.toString(),
                    patch.toString(),
                    out.toString(),
                    "--progress-mode",
                    "Bogus",
                )
            assertNotEquals(0, exit, "an unknown --progress-mode on merge must fail argument parsing")
        } finally {
            Cleaner.deleteTreeWithRetry(base, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }
}
