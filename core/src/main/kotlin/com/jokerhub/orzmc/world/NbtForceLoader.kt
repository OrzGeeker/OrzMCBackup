package com.jokerhub.orzmc.world

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

private const val TAG_END: Byte = 0
private const val TAG_BYTE: Byte = 1
private const val TAG_SHORT: Byte = 2
private const val TAG_INT: Byte = 3
private const val TAG_LONG: Byte = 4
private const val TAG_FLOAT: Byte = 5
private const val TAG_DOUBLE: Byte = 6
private const val TAG_BYTE_ARRAY: Byte = 7
private const val TAG_STRING: Byte = 8
private const val TAG_LIST: Byte = 9
private const val TAG_COMPOUND: Byte = 10
private const val TAG_INT_ARRAY: Byte = 11
private const val TAG_LONG_ARRAY: Byte = 12

private const val DEFAULT_MAX_ARRAY_SIZE = 10 * 1024 * 1024 // 10 MB
private const val DEFAULT_MAX_LIST_LENGTH = 65536
private const val DEFAULT_MAX_COMPOUND_DEPTH = 64

/**
 * Full NBT parser for `chunks.dat` (GZip-compressed NBT).
 *
 * Supports all 12 standard NBT tag types and extracts force-loaded chunk
 * coordinates from both the legacy `data.Forced` (LongArray) format and the
 * newer `data.tickets[].chunk_pos` format.
 *
 * All array/list allocations are bounded by configurable limits to prevent OOM
 * from crafted inputs.
 */
object NbtForceLoader {
    /**
     * Parse [file] and return force-loaded chunk coordinates.
     * @param maxArraySize maximum allowed ByteArray/IntArray/LongArray length
     * @param maxListLength maximum allowed List element count
     * @param maxCompoundDepth maximum allowed Compound nesting depth
     */
    fun parse(
        file: File,
        maxArraySize: Int = DEFAULT_MAX_ARRAY_SIZE,
        maxListLength: Int = DEFAULT_MAX_LIST_LENGTH,
        maxCompoundDepth: Int = DEFAULT_MAX_COMPOUND_DEPTH,
    ): List<Pair<Int, Int>> {
        GZIPInputStream(BufferedInputStream(file.inputStream())).use { gz ->
            return parseFrom(gz, maxArraySize, maxListLength, maxCompoundDepth)
        }
    }

    /**
     * Parse an in-memory GZip-compressed NBT payload, e.g. read through a [FileSystem].
     * Bounds are identical to [parse].
     */
    fun parse(
        bytes: ByteArray,
        maxArraySize: Int = DEFAULT_MAX_ARRAY_SIZE,
        maxListLength: Int = DEFAULT_MAX_LIST_LENGTH,
        maxCompoundDepth: Int = DEFAULT_MAX_COMPOUND_DEPTH,
    ): List<Pair<Int, Int>> {
        GZIPInputStream(BufferedInputStream(bytes.inputStream())).use { gz ->
            return parseFrom(gz, maxArraySize, maxListLength, maxCompoundDepth)
        }
    }

    private fun parseFrom(
        gz: GZIPInputStream,
        maxArraySize: Int,
        maxListLength: Int,
        maxCompoundDepth: Int,
    ): List<Pair<Int, Int>> {
        val inp = DataInputStream(gz)
        val rootType = inp.readByte()
        require(rootType == TAG_COMPOUND) { "root must be Compound" }
        readUtf(inp)
        val compound = readCompound(inp, 0, maxCompoundDepth, maxArraySize, maxListLength)
        val dataTag = compound["data"] as? Map<*, *> ?: return emptyList()
        val out = mutableListOf<Pair<Int, Int>>()
        val forced = dataTag["Forced"] as? LongArray
        if (forced != null) {
            var i = 0
            while (i + 1 < forced.size) {
                out.add(Pair(forced[i].toInt(), forced[i + 1].toInt()))
                i += 2
            }
        }
        val tickets = dataTag["tickets"] as? List<*>
        if (tickets != null) {
            @Suppress("LoopWithTooManyJumpStatements")
            for (t in tickets) {
                val tm = t as? Map<*, *> ?: continue
                val typeStr = tm["type"] as? String ?: continue
                if (typeStr == "minecraft:forced") {
                    val pos = tm["chunk_pos"] as? IntArray ?: continue
                    if (pos.size == 2) out.add(Pair(pos[0], pos[1]))
                }
            }
        }
        return out
    }

    private fun readUtf(inp: DataInputStream): String {
        val len = inp.readUnsignedShort()
        val b = ByteArray(len)
        inp.readFully(b)
        return String(b, StandardCharsets.UTF_8)
    }

    private fun readList(
        inp: DataInputStream,
        depth: Int,
        maxCompoundDepth: Int,
        maxArraySize: Int,
        maxListLength: Int,
    ): Any {
        val elemType = inp.readByte()
        val len = inp.readInt()
        require(len >= 0 && len <= maxListLength) {
            "NBT list length $len exceeds maximum $maxListLength"
        }
        val list = ArrayList<Any>(len)
        for (i in 0 until len) {
            list.add(readPayload(inp, elemType, depth, maxCompoundDepth, maxArraySize, maxListLength))
        }
        return list
    }

    private fun readCompound(
        inp: DataInputStream,
        depth: Int,
        maxCompoundDepth: Int,
        maxArraySize: Int,
        maxListLength: Int,
    ): Map<String, Any> {
        require(depth <= maxCompoundDepth) {
            "NBT compound depth $depth exceeds maximum $maxCompoundDepth"
        }
        val m = HashMap<String, Any>()
        @Suppress("LoopWithTooManyJumpStatements")
        while (true) {
            val t = inp.readByte()
            if (t == TAG_END) break
            val name = readUtf(inp)
            val v = readPayload(inp, t, depth + 1, maxCompoundDepth, maxArraySize, maxListLength)
            m[name] = v
        }
        return m
    }

    private fun readPayload(
        inp: DataInputStream,
        t: Byte,
        depth: Int,
        maxCompoundDepth: Int,
        maxArraySize: Int,
        maxListLength: Int,
    ): Any =
        when (t) {
            TAG_BYTE -> inp.readByte()
            TAG_SHORT -> inp.readShort()
            TAG_INT -> inp.readInt()
            TAG_LONG -> inp.readLong()
            TAG_FLOAT -> inp.readFloat()
            TAG_DOUBLE -> inp.readDouble()
            TAG_BYTE_ARRAY -> {
                val len = inp.readInt()
                require(len >= 0 && len <= maxArraySize) {
                    "NBT ByteArray length $len exceeds maximum $maxArraySize"
                }
                val arr = ByteArray(len)
                inp.readFully(arr)
                arr
            }
            TAG_STRING -> readUtf(inp)
            TAG_LIST -> readList(inp, depth, maxCompoundDepth, maxArraySize, maxListLength)
            TAG_COMPOUND -> readCompound(inp, depth, maxCompoundDepth, maxArraySize, maxListLength)
            TAG_INT_ARRAY -> {
                val len = inp.readInt()
                require(len >= 0 && len <= maxArraySize) {
                    "NBT IntArray length $len exceeds maximum $maxArraySize"
                }
                val arr = IntArray(len)
                for (i in 0 until len) arr[i] = inp.readInt()
                arr
            }
            TAG_LONG_ARRAY -> {
                val len = inp.readInt()
                require(len >= 0 && len <= maxArraySize) {
                    "NBT LongArray length $len exceeds maximum $maxArraySize"
                }
                val arr = LongArray(len)
                for (i in 0 until len) arr[i] = inp.readLong()
                arr
            }
            else -> throw IllegalArgumentException("unsupported tag $t")
        }
}
