package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.util.TestPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Merges a real committed Anvil-format fixture pair (see Fixtures/merge/README.md) through
 * the production [RealFileSystem] + [DefaultMcaIOFactory] path, proving the slot-level merge
 * works on genuine on-disk region files without the in-memory test doubles.
 */
class RealMcaMergeTest {
    private val base = TestPaths.mergeBase()
    private val patch = TestPaths.mergePatch()

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

    private fun readInhabited(
        path: Path,
        slot: Int,
    ): Long? {
        val payload =
            McaReader
                .open(path.toString())
                .use { it.get(slot)?.allDataUncompressed() }
                ?: return null
        // The generated NBT ends with the 8-byte InhabitedTime long followed by TAG_End,
        // so the long is the 9 bytes from the end.
        return ByteBuffer.wrap(payload, payload.size - 9, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    @Test
    fun `merges real anvil regions slot by slot`() {
        val out = Files.createTempDirectory("merge-real-out-")
        try {
            val report =
                WorldMerger.run(
                    MergeRequest(
                        base = base,
                        patch = patch,
                        output = out,
                        outputOptions = OutputOptions(force = true),
                        io = IOOptions(fs = RealFileSystem, ioFactory = DefaultMcaIOFactory()),
                    ),
                )

            assertEquals(0, report.errors.size)
            assertEquals(1, report.mergedRegions)
            assertEquals(40, report.patchSlots)
            assertEquals(60, report.baseSlots)

            val outDim = out.resolve("dimensions/minecraft/overworld")
            val region = outDim.resolve("region/r.0.0.mca")
            assertEquals(100, slotCount(region), "merged region keeps every base and patch slot")
            assertEquals(2000L, readInhabited(region, 0), "patch chunk wins where patch has the slot")
            assertEquals(2039L, readInhabited(region, 39), "patch chunk wins where patch has the slot")
            assertEquals(1040L, readInhabited(region, 40), "pruned slot is filled from base")
            assertEquals(1099L, readInhabited(region, 99))

            assertEquals(100, slotCount(outDim.resolve("entities/r.0.0.mca")), "entities merge lockstep")
            assertEquals(100, slotCount(outDim.resolve("poi/r.0.0.mca")), "poi merge lockstep")

            assertEquals(10, slotCount(outDim.resolve("region/r.1.1.mca")), "base-only region is preserved")
            assertTrue(Files.exists(out.resolve("level.dat")))
            assertEquals("patch-level-dat", String(Files.readAllBytes(out.resolve("level.dat"))))
        } finally {
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }
}
