package com.jokerhub.orzmc.mca

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import net.jpountz.xxhash.XXHashFactory
import net.jpountz.lz4.LZ4Factory

class McaEntry(
    private val file: RandomAccessFile,
    private val start: Long,
    private val length: Int,
    private val index: Int,
    private val modified: Int,
    private val regionX: Int,
    private val regionZ: Int,
) {
    enum class CompressionMethod { GZIP, ZLIB, RAW, LZ4, CUSTOM, EXT_GZIP, EXT_ZLIB, EXT_RAW, EXT_LZ4 }

    fun regionIndex(): Int = index
    fun xPos(): Int = index % 32
    fun zPos(): Int = index / 32
    fun globalX(): Int = regionX * 32 + xPos()
    fun globalZ(): Int = regionZ * 32 + zPos()
    fun modifiedTime(): Int = modified

    private fun readHeader(): Triple<Int, CompressionMethod, String?> {
        file.seek(start)
        val header = ByteArray(5)
        file.readFully(header)
        val len = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val methodByte = header[4].toInt()
        val method = when (methodByte.toByte().toInt()) {
            1 -> CompressionMethod.GZIP
            2 -> CompressionMethod.ZLIB
            3 -> CompressionMethod.RAW
            4 -> CompressionMethod.LZ4
            127 -> CompressionMethod.CUSTOM
            -127 -> CompressionMethod.EXT_GZIP
            -126 -> CompressionMethod.EXT_ZLIB
            -125 -> CompressionMethod.EXT_RAW
            -124 -> CompressionMethod.EXT_LZ4
            else -> throw IllegalArgumentException("unknown compression: $methodByte")
        }
        var custom: String? = null
        if (method == CompressionMethod.CUSTOM) {
            val nameLenBuf = ByteArray(2)
            file.readFully(nameLenBuf)
            val n = ByteBuffer.wrap(nameLenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            val nameBytes = ByteArray(n)
            file.readFully(nameBytes)
            custom = String(nameBytes)
        }
        return Triple(len, method, custom)
    }

    fun serializedBytes(): ByteArray {
        val (len, _, _) = readHeader()
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
            val n = ByteBuffer.wrap(nameLenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
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
            else -> ByteArray(0)
        }
    }

    fun isExternal(): Boolean {
        val (_, method, _) = readHeader()
        return method == CompressionMethod.EXT_GZIP || method == CompressionMethod.EXT_ZLIB || method == CompressionMethod.EXT_RAW || method == CompressionMethod.EXT_LZ4
    }

    companion object {
        private val LZ4_MAGIC = "LZ4Block".toByteArray()
        private const val LZ4_HEADER_LEN = 8 + 1 + 4 + 4 + 4
        private const val LZ4_XXHASH_SEED = 0x9747b28c.toInt()

        private fun xxh32(data: ByteArray, seed: Int): Int {
            val factory = XXHashFactory.fastestInstance()
            val hasher = factory.hash32()
            return hasher.hash(data, 0, data.size, seed)
        }

        private fun decodeLZ4Blocks(inp: ByteArray): ByteArray {
            var i = 0
            val out = java.io.ByteArrayOutputStream()
            val lz4 = net.jpountz.lz4.LZ4Factory.safeInstance().safeDecompressor()
            while (i + LZ4_HEADER_LEN <= inp.size) {
                if (!inp.copyOfRange(i, i + 8).contentEquals(LZ4_MAGIC)) throw IllegalArgumentException("invalid LZ4 magic")
                val token = inp[i + 8].toInt()
                val method = token and 0xF0
                val compLen = ByteBuffer.wrap(inp, i + 9, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val decompLen = ByteBuffer.wrap(inp, i + 13, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val checksumLe = ByteBuffer.wrap(inp, i + 17, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val start = i + LZ4_HEADER_LEN
                if (start + compLen > inp.size) throw IllegalArgumentException("LZ4 block truncated")
                val block = inp.copyOfRange(start, start + compLen)
                val decoded = when (method) {
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
