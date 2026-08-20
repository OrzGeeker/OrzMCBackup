package com.jokerhub.orzmc.mca

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.TestTmp
import com.jokerhub.orzmc.world.Cleaner
import com.jokerhub.orzmc.world.FilterOptions
import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.OptimizerRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Decompression-bomb guards: `McaEntry.allDataUncompressed` bounds the decompressed
 * size even though the compressed length is bounded by `MAX_VALID_CHUNK_LENGTH`.
 * A tiny high-ratio payload (e.g. all zeros) would otherwise expand to gigabytes and OOM.
 * Oversized chunks must be reported and safely kept (original bytes preserved), never dropped.
 */
class DecompressionBombTest {
    @Test
    fun `zlip chunk expanding beyond the limit throws instead of OOM`() {
        // ~64MB of zeros deflates to a few dozen KB of compressed payload but would expand
        // back past the cap on read — the classic decompression bomb shape.
        val payload = ByteArray(McaEntry.MAX_UNCOMPRESSED_CHUNK_LENGTH + 1)
        val mca = McaMemoryBuilder.buildCustomPayloadMca(0, payload, CompressionKind.ZLIB)
        McaReader.openFromBytes("r.0.0.mca", mca).use { r ->
            val entry = r.get(0)!!
            assertThrows(IllegalArgumentException::class.java) { entry.allDataUncompressed() }
        }
    }

    @Test
    fun `lz4 block declaring oversized decompressed length throws before allocation`() {
        val header = ByteArray(8 + 1 + 4 + 4 + 4)
        "LZ4Block".toByteArray().copyInto(header, 0)
        header[8] = 0x20 // 0x20 = LZ4 compressed block
        val bb = ByteBuffer.wrap(header, 9, 12).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(1) // compressed length
        bb.putInt(McaEntry.MAX_UNCOMPRESSED_CHUNK_LENGTH + 1) // decompressed length beyond the cap
        bb.putInt(0) // xxhash checksum (never reached)
        val inp = header + byteArrayOf(0)
        assertThrows(IllegalArgumentException::class.java) { McaEntry.decodeLZ4BlocksForTest(inp) }
    }

    @Test
    fun `bomb chunk is safely kept end to end with a pattern error reported`() {
        val world = TestTmp.createTempDirectory("bomb-world-")
        val out = TestTmp.createTempDirectory("bomb-out-")
        try {
            Files.createDirectories(world.resolve("region"))
            val payload = ByteArray(McaEntry.MAX_UNCOMPRESSED_CHUNK_LENGTH + 1)
            Files.write(
                world.resolve("region").resolve("r.0.0.mca"),
                McaMemoryBuilder.buildCustomPayloadMca(0, payload, CompressionKind.ZLIB),
            )

            val report =
                Optimizer.run(
                    OptimizerRequest(
                        input = world,
                        output = out,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                    ),
                )
            // The bomb chunk cannot be evaluated, so it is preserved (safe-keep), not dropped.
            assertTrue(
                Files.exists(out.resolve("region").resolve("r.0.0.mca")),
                "bomb chunk must be kept byte-for-byte",
            )
            assertTrue(report.errors.any { it.kind == "Pattern" }, "the failure must be recorded as a Pattern error")
            assertEquals(1, report.processedChunks)
        } finally {
            Cleaner.deleteTreeWithRetry(world, 5, 10)
            Cleaner.deleteTreeWithRetry(out, 5, 10)
        }
    }
}
