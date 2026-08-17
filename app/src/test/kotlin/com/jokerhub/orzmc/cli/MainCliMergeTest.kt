package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.Cleaner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path

class MainCliMergeTest {
    private val dim = "dimensions/minecraft/overworld"

    private fun writeMca(
        root: Path,
        kind: String,
        name: String,
        slots: List<Int>,
    ) {
        val dir = root.resolve(dim).resolve(kind)
        Files.createDirectories(dir)
        val chunks = slots.map { McaMemoryBuilder.MemChunk(it, 1000L + it, CompressionKind.RAW) }
        Files.write(dir.resolve(name), McaMemoryBuilder.buildMca(chunks))
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

    private fun runMerge(
        base: Path,
        patch: Path,
        out: Path,
        vararg extra: String,
    ): Int =
        CommandLine(MergeCommand()).execute(
            base.toString(),
            patch.toString(),
            out.toString(),
            "--progress-mode",
            "Off",
            "--force",
            "--report",
            *extra,
        )

    @Test
    fun `merge overlays patch slots, keeps base slots, overlays misc, removes session lock`() {
        val base = Files.createTempDirectory("merge-base-")
        val patch = Files.createTempDirectory("merge-patch-")
        val out = Files.createTempDirectory("merge-out-")
        try {
            writeMca(base, "region", "r.0.0.mca", listOf(0))
            writeMca(base, "entities", "r.0.0.mca", listOf(0))
            Files.write(base.resolve("level.dat"), "base".toByteArray())
            Files.write(base.resolve("session.lock"), "lock".toByteArray())

            writeMca(patch, "region", "r.0.0.mca", listOf(1))
            writeMca(patch, "entities", "r.0.0.mca", listOf(1))
            Files.write(patch.resolve("level.dat"), "patch".toByteArray())

            val exit = runMerge(base, patch, out)

            assertEquals(0, exit)
            val outRegion = out.resolve(dim).resolve("region").resolve("r.0.0.mca")
            val outEntities = out.resolve(dim).resolve("entities").resolve("r.0.0.mca")
            assertTrue(Files.exists(outRegion), "output region should exist")
            assertEquals(2, slotCount(outRegion), "output region should keep base slot 0 and add patch slot 1")
            assertEquals(2, slotCount(outEntities), "entities should be merged lockstep")
            assertEquals(
                "patch",
                String(Files.readAllBytes(out.resolve("level.dat"))),
                "misc files should come from patch",
            )
            assertFalse(Files.exists(out.resolve("session.lock")), "session.lock should be removed")
        } finally {
            Cleaner.deleteTreeWithRetry(out, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
            Cleaner.deleteTreeWithRetry(base, 5, 10)
        }
    }

    @Test
    fun `merge refuses non-empty output without force`() {
        val base = Files.createTempDirectory("merge-base-")
        val patch = Files.createTempDirectory("merge-patch-")
        val out = Files.createTempDirectory("merge-out-")
        try {
            writeMca(base, "region", "r.0.0.mca", listOf(0))
            writeMca(patch, "region", "r.0.0.mca", listOf(1))
            Files.write(out.resolve("keep.txt"), "x".toByteArray())

            val exit =
                CommandLine(MergeCommand()).execute(
                    base.toString(),
                    patch.toString(),
                    out.toString(),
                    "--progress-mode",
                    "Off",
                    "--report",
                )

            assertEquals(1, exit)
            assertTrue(Files.exists(out.resolve("keep.txt")), "output must be left untouched")
        } finally {
            Cleaner.deleteTreeWithRetry(out, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
            Cleaner.deleteTreeWithRetry(base, 5, 10)
        }
    }

    @Test
    fun `merge writes json report file`() {
        val base = Files.createTempDirectory("merge-base-")
        val patch = Files.createTempDirectory("merge-patch-")
        val out = Files.createTempDirectory("merge-out-")
        val report = Files.createTempDirectory("merge-rep-").resolve("report.json")
        try {
            writeMca(base, "region", "r.0.0.mca", listOf(0))
            writeMca(patch, "region", "r.0.0.mca", listOf(1))

            val exit = runMerge(base, patch, out, "--report-file", report.toString())

            assertEquals(0, exit)
            assertTrue(Files.exists(report), "report file should exist")
            val text = Files.readString(report)
            assertTrue(text.contains("\"mergedRegions\":1"))
            assertTrue(text.contains("\"patchSlots\":1"))
        } finally {
            Cleaner.deleteTreeWithRetry(out, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
            Cleaner.deleteTreeWithRetry(base, 5, 10)
            Files.deleteIfExists(report)
        }
    }

    @Test
    fun `merge writes csv report file`() {
        val base = Files.createTempDirectory("merge-base-")
        val patch = Files.createTempDirectory("merge-patch-")
        val out = Files.createTempDirectory("merge-out-")
        val report = Files.createTempDirectory("merge-rep-").resolve("report.csv")
        try {
            writeMca(base, "region", "r.0.0.mca", listOf(0))
            writeMca(patch, "region", "r.0.0.mca", listOf(1))

            val exit =
                runMerge(
                    base,
                    patch,
                    out,
                    "--report-file",
                    report.toString(),
                    "--report-format",
                    "csv",
                )

            assertEquals(0, exit)
            assertTrue(Files.exists(report), "report file should exist")
            val text = Files.readString(report)
            assertTrue(
                text.startsWith("mergedRegions,copiedFiles,patchSlots,baseSlots,linkedEntities,linkedPoi,"),
            )
            assertTrue(text.contains("\n1,0,1,1,0,0"))
        } finally {
            Cleaner.deleteTreeWithRetry(out, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
            Cleaner.deleteTreeWithRetry(base, 5, 10)
            Files.deleteIfExists(report)
        }
    }

    @Test
    fun `merge supports global progress mode`() {
        val base = Files.createTempDirectory("merge-base-")
        val patch = Files.createTempDirectory("merge-patch-")
        val out = Files.createTempDirectory("merge-out-")
        try {
            writeMca(base, "region", "r.0.0.mca", listOf(0))
            writeMca(patch, "region", "r.0.0.mca", listOf(1))

            val exit =
                CommandLine(MergeCommand()).execute(
                    base.toString(),
                    patch.toString(),
                    out.toString(),
                    "--force",
                    "--progress-mode",
                    "Global",
                )

            assertEquals(0, exit)
            assertTrue(Files.exists(out.resolve(dim).resolve("region").resolve("r.0.0.mca")))
        } finally {
            Cleaner.deleteTreeWithRetry(out, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
            Cleaner.deleteTreeWithRetry(base, 5, 10)
        }
    }

    @Test
    fun `merge missing base directory exits non-zero`() {
        val patch = Files.createTempDirectory("merge-patch-")
        val out = Files.createTempDirectory("merge-out-")
        val missingBase = Files.createTempDirectory("merge-missing-").resolve("world")
        try {
            writeMca(patch, "region", "r.0.0.mca", listOf(1))

            val exit =
                CommandLine(MergeCommand()).execute(
                    missingBase.toString(),
                    patch.toString(),
                    out.toString(),
                    "--force",
                    "--progress-mode",
                    "Off",
                )

            assertEquals(1, exit)
        } finally {
            Cleaner.deleteTreeWithRetry(out, 5, 10)
            Cleaner.deleteTreeWithRetry(patch, 5, 10)
            Cleaner.deleteTreeWithRetry(missingBase.parent, 5, 10)
        }
    }
}
