package com.jokerhub.orzmc.util

import net.jpountz.lz4.LZ4Factory
import net.jpountz.xxhash.XXHashFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

enum class CompressionKind { RAW, ZLIB, GZIP, LZ4 }

object McaMemoryBuilder {
    data class MemChunk(val index: Int, val inhabited: Long, val kind: CompressionKind)

    private fun inhabitedTag(value: Long): ByteArray {
        val name = "InhabitedTime".toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(4))
        val bb = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(name.size.toShort())
        out.write(bb.array())
        out.write(name)
        val vb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        vb.putLong(value)
        out.write(vb.array())
        return out.toByteArray()
    }

    private fun compress(kind: CompressionKind, data: ByteArray): Pair<Int, ByteArray> {
        return when (kind) {
            CompressionKind.RAW -> 3 to data
            CompressionKind.ZLIB -> {
                val bos = ByteArrayOutputStream()
                DeflaterOutputStream(bos, Deflater(Deflater.DEFAULT_COMPRESSION, false)).use { it.write(data) }
                2 to bos.toByteArray()
            }

            CompressionKind.GZIP -> {
                val bos = ByteArrayOutputStream()
                GZIPOutputStream(bos).use { it.write(data) }
                1 to bos.toByteArray()
            }

            CompressionKind.LZ4 -> {
                val lz4 = LZ4Factory.safeInstance().fastCompressor()
                val maxLen = lz4.maxCompressedLength(data.size)
                val buf = ByteArray(maxLen)
                val len = lz4.compress(data, 0, data.size, buf, 0)
                val comp = buf.copyOf(len)
                val token = 0x20
                val bb = ByteBuffer.allocate(8 + 1 + 4 + 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
                bb.put("LZ4Block".toByteArray())
                bb.put(token.toByte())
                bb.putInt(comp.size)
                bb.putInt(data.size)
                val factory = XXHashFactory.fastestInstance()
                val hasher = factory.hash32()
                val bbData = ByteBuffer.wrap(data)
                val checksum = hasher.hash(bbData, 0, data.size, 0x9747b28c.toInt()) and 0x0FFFFFFF
                bb.putInt(checksum)
                val out = ByteArrayOutputStream()
                out.write(bb.array())
                out.write(comp)
                4 to out.toByteArray()
            }
        }
    }

    fun buildSingleEntryMca(regionX: Int, regionZ: Int, index: Int, inhabited: Long, kind: CompressionKind): ByteArray {
        val payload = inhabitedTag(inhabited)
        val (method, body) = compress(kind, payload)
        val bos = ByteArrayOutputStream()
        val loc = ByteArray(4096)
        val bbLoc = ByteBuffer.wrap(loc).order(ByteOrder.BIG_ENDIAN)
        val time = ByteArray(4096)
        val bbTime = ByteBuffer.wrap(time).order(ByteOrder.BIG_ENDIAN)
        var offsetBytes = 8192
        fun writeOne(idx: Int, m: Int, b: ByteArray) {
            val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1 + b.size).array()
            bos.write(lenBuf)
            bos.write(byteArrayOf(m.toByte()))
            bos.write(b)
            val written = 4 + 1 + b.size
            val pad = (4096 - (written % 4096)) % 4096
            if (pad > 0) bos.write(ByteArray(pad))
            val offSectors = (offsetBytes / 4096)
            val sizeSectors = (written + pad) / 4096
            val v = (offSectors shl 8) or (sizeSectors and 0xFF)
            bbLoc.position(idx * 4)
            bbLoc.putInt(v)
            bbTime.position(idx * 4)
            bbTime.putInt((System.currentTimeMillis() / 1000).toInt())
            offsetBytes += written + pad
        }
        writeOne(index, method, body)
        val header = ByteArrayOutputStream()
        header.write(loc)
        header.write(time)
        val headerBytes = header.toByteArray()
        val final = ByteArrayOutputStream()
        final.write(headerBytes)
        final.write(bos.toByteArray())
        return final.toByteArray()
    }

    fun buildMca(regionX: Int, regionZ: Int, chunks: List<MemChunk>): ByteArray {
        val loc = ByteArray(4096)
        val bbLoc = ByteBuffer.wrap(loc).order(ByteOrder.BIG_ENDIAN)
        val time = ByteArray(4096)
        val bbTime = ByteBuffer.wrap(time).order(ByteOrder.BIG_ENDIAN)
        val data = ByteArrayOutputStream()
        var offsetBytes = 8192
        for (c in chunks) {
            val payload = inhabitedTag(c.inhabited)
            val (method, body) = compress(c.kind, payload)
            val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1 + body.size).array()
            data.write(lenBuf)
            data.write(byteArrayOf(method.toByte()))
            data.write(body)
            val written = 4 + 1 + body.size
            val pad = (4096 - (written % 4096)) % 4096
            if (pad > 0) data.write(ByteArray(pad))
            val offSectors = (offsetBytes / 4096)
            val sizeSectors = (written + pad) / 4096
            val v = (offSectors shl 8) or (sizeSectors and 0xFF)
            bbLoc.position(c.index * 4)
            bbLoc.putInt(v)
            bbTime.position(c.index * 4)
            bbTime.putInt((System.currentTimeMillis() / 1000).toInt())
            offsetBytes += written + pad
        }
        val header = ByteArrayOutputStream()
        header.write(loc)
        header.write(time)
        val out = ByteArrayOutputStream()
        out.write(header.toByteArray())
        out.write(data.toByteArray())
        return out.toByteArray()
    }
}
