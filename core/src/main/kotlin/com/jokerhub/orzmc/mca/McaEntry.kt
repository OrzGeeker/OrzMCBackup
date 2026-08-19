package com.jokerhub.orzmc.mca

import net.jpountz.xxhash.XXHashFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Represents a single chunk entry within an MCA (Anvil) region file.
 *
 * Each entry references a position in the underlying region file and provides
 * access to the chunk's raw serialized bytes, decompressed data, and metadata
 * such as position, compression method, and modification time.
 *
 * Entry instances are created by [McaReader] and are read-only views into
 * the underlying file; they do not own the file handle.
 */
class McaEntry(
    private val file: RandomAccess,
    private val start: Long,
    private val length: Int,
    private val index: Int,
    private val modified: Int,
    private val regionX: Int,
    private val regionZ: Int,
) {
    /** Compression methods used in Minecraft region files. */
    enum class CompressionMethod { GZIP, ZLIB, RAW, LZ4, CUSTOM, EXT_GZIP, EXT_ZLIB, EXT_RAW, EXT_LZ4, UNKNOWN }

    /** Index within the sector table (0-1023). Maps to (x = index % 32, z = index / 32) within the region. */
    fun regionIndex(): Int = index

    /** Local X coordinate within the region (0-31). */
    fun xPos(): Int = index % 32

    /** Local Z coordinate within the region (0-31). */
    fun zPos(): Int = index / 32

    /** Global X coordinate across the world. */
    fun globalX(): Int = regionX * 32 + xPos()

    /** Global Z coordinate across the world. */
    fun globalZ(): Int = regionZ * 32 + zPos()

    /** Timestamp of last modification (Unix epoch seconds). */
    fun modifiedTime(): Int = modified

    private fun readHeader(): Triple<Int, CompressionMethod, String?> {
        file.seek(start)
        val header = ByteArray(5)
        file.readFully(header)
        val len = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val methodByte = header[4].toInt()
        val method =
            when (methodByte.toByte().toInt()) {
                1 -> CompressionMethod.GZIP
                2 -> CompressionMethod.ZLIB
                3 -> CompressionMethod.RAW
                4 -> CompressionMethod.LZ4
                127 -> CompressionMethod.CUSTOM
                -127 -> CompressionMethod.EXT_GZIP
                -126 -> CompressionMethod.EXT_ZLIB
                -125 -> CompressionMethod.EXT_RAW
                -124 -> CompressionMethod.EXT_LZ4
                // 未知/损坏的压缩类型：不抛异常（长度字段仍可读，允许原样透传保留），
                // 仅在解压（allDataUncompressed）时抛错——由调用方决定保留策略。
                else -> CompressionMethod.UNKNOWN
            }
        var custom: String? = null
        if (method == CompressionMethod.CUSTOM) {
            val nameLenBuf = ByteArray(2)
            file.readFully(nameLenBuf)
            val n =
                ByteBuffer
                    .wrap(nameLenBuf)
                    .order(ByteOrder.BIG_ENDIAN)
                    .short
                    .toInt() and 0xFFFF
            val nameBytes = ByteArray(n)
            file.readFully(nameBytes)
            custom = String(nameBytes)
        }
        return Triple(len, method, custom)
    }

    fun serializedBytes(): ByteArray {
        val (len, _, _) = readHeader()
        // 损坏 chunk 的长度字段可能是垃圾值（如 0x0ac9fbd1 ≈ 180MB）：
        // 超阈值视为不可信 → 返回空（调用方跳过，避免读大块数据卡死）。
        // 注意：长度正常但压缩类型非法的 chunk 仍可原样透传（数据字节在原位）。
        if (len < 0 || len > MAX_VALID_CHUNK_LENGTH) {
            return ByteArray(0)
        }
        val total = 4L + len.toLong()
        file.seek(start)
        val out = ByteArray(total.toInt())
        file.readFully(out)
        return out
    }

    fun dataBytes(): Triple<CompressionMethod, ByteArray, String?> {
        val (len, method, custom) = readHeader()
        var pos = start + 5
        if (method == CompressionMethod.CUSTOM) {
            val nameLenBuf = ByteArray(2)
            file.readFully(nameLenBuf)
            val n =
                ByteBuffer
                    .wrap(nameLenBuf)
                    .order(ByteOrder.BIG_ENDIAN)
                    .short
                    .toInt() and 0xFFFF
            val nameBytes = ByteArray(n)
            file.readFully(nameBytes)
            pos += 2 + n
        }
        val customLen = if (method == CompressionMethod.CUSTOM) 2 + (custom?.length ?: 0) else 0
        val dataLen = len - 1 - customLen
        file.seek(pos)
        val data = ByteArray(dataLen)
        file.readFully(data)
        return Triple(method, data, custom)
    }

    fun allDataUncompressed(): ByteArray {
        val (method, data, _) = dataBytes()
        return when (method) {
            CompressionMethod.RAW -> data
            CompressionMethod.ZLIB -> InflaterInputStream(data.inputStream()).use { it.readBytes() }
            CompressionMethod.GZIP -> GZIPInputStream(data.inputStream()).use { it.readBytes() }
            CompressionMethod.LZ4 -> decodeLZ4Blocks(data)
            CompressionMethod.UNKNOWN ->
                throw IllegalArgumentException("unknown compression: $method")
            else -> ByteArray(0)
        }
    }

    fun isExternal(): Boolean {
        val (_, method, _) = readHeader()
        return method == CompressionMethod.EXT_GZIP ||
            method == CompressionMethod.EXT_ZLIB ||
            method == CompressionMethod.EXT_RAW ||
            method == CompressionMethod.EXT_LZ4
    }

    companion object {
        /** 合法 chunk 数据最大长度（压缩后）。MC 单 chunk 压缩后 < 1MB（更大走 .mcc 外部文件），8MB 为安全阈值。 */
        private const val MAX_VALID_CHUNK_LENGTH = 8 * 1024 * 1024

        private val LZ4_MAGIC = "LZ4Block".toByteArray()
        private const val LZ4_HEADER_LEN = 8 + 1 + 4 + 4 + 4
        private const val LZ4_XXHASH_SEED = 0x9747b28c.toInt()

        private fun xxh32(
            data: ByteArray,
            seed: Int,
        ): Int {
            val factory = XXHashFactory.fastestInstance()
            val hasher = factory.hash32()
            return hasher.hash(data, 0, data.size, seed)
        }

        @Suppress("ThrowsCount")
        private fun decodeLZ4Blocks(inp: ByteArray): ByteArray {
            var i = 0
            val out = java.io.ByteArrayOutputStream()
            val lz4 =
                net.jpountz.lz4.LZ4Factory
                    .safeInstance()
                    .safeDecompressor()
            while (i + LZ4_HEADER_LEN <= inp.size) {
                if (!inp
                        .copyOfRange(i, i + 8)
                        .contentEquals(LZ4_MAGIC)
                ) {
                    throw IllegalArgumentException("invalid LZ4 magic")
                }
                val token = inp[i + 8].toInt()
                val method = token and 0xF0
                val compLen = ByteBuffer.wrap(inp, i + 9, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val decompLen = ByteBuffer.wrap(inp, i + 13, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val checksumLe = ByteBuffer.wrap(inp, i + 17, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val start = i + LZ4_HEADER_LEN
                if (start + compLen > inp.size) throw IllegalArgumentException("LZ4 block truncated")
                val block = inp.copyOfRange(start, start + compLen)
                val decoded =
                    when (method) {
                        0x10 -> block // RAW
                        0x20 -> {
                            val dest = ByteArray(decompLen)
                            lz4.decompress(block, 0, block.size, dest, 0)
                            dest
                        }

                        else -> throw IllegalArgumentException("unsupported LZ4 method")
                    }
                val checksum = (xxh32(decoded, LZ4_XXHASH_SEED) and 0x0FFFFFFF)
                if (checksum != checksumLe) throw IllegalArgumentException("LZ4 checksum mismatch")
                out.write(decoded)
                i = start + compLen
            }
            if (i != inp.size) throw IllegalArgumentException("dangling LZ4 bytes")
            return out.toByteArray()
        }

        @JvmStatic
        fun decodeLZ4BlocksForTest(inp: ByteArray): ByteArray = decodeLZ4Blocks(inp)
    }
}
