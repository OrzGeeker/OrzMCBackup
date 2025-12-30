package com.jokerhub.orzmc.world

import java.io.File
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

private const val TAG_End: Byte = 0
private const val TAG_Byte: Byte = 1
private const val TAG_Short: Byte = 2
private const val TAG_Int: Byte = 3
private const val TAG_Long: Byte = 4
private const val TAG_Float: Byte = 5
private const val TAG_Double: Byte = 6
private const val TAG_Byte_Array: Byte = 7
private const val TAG_String: Byte = 8
private const val TAG_List: Byte = 9
private const val TAG_Compound: Byte = 10
private const val TAG_Int_Array: Byte = 11
private const val TAG_Long_Array: Byte = 12

object NbtForceLoader {
    fun parse(file: File): List<Pair<Int, Int>> {
        GZIPInputStream(BufferedInputStream(file.inputStream())).use { gz ->
            val inp = DataInputStream(gz)
            val rootType = inp.readByte()
            require(rootType == TAG_Compound) { "root must be Compound" }
            readUtf(inp)
            val compound = readCompound(inp)
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
    }

    private fun readUtf(inp: DataInputStream): String {
        val len = inp.readUnsignedShort()
        val b = ByteArray(len)
        inp.readFully(b)
        return String(b, StandardCharsets.UTF_8)
    }

    private fun readList(inp: DataInputStream): Any {
        val elemType = inp.readByte()
        val len = inp.readInt()
        val list = ArrayList<Any>(len)
        for (i in 0 until len) {
            list.add(readPayload(inp, elemType))
        }
        return list
    }

    private fun readCompound(inp: DataInputStream): Map<String, Any> {
        val m = HashMap<String, Any>()
        while (true) {
            val t = inp.readByte()
            if (t == TAG_End) break
            val name = readUtf(inp)
            val v = readPayload(inp, t)
            m[name] = v
        }
        return m
    }

    private fun readPayload(inp: DataInputStream, t: Byte): Any {
        return when (t) {
            TAG_Byte -> inp.readByte()
            TAG_Short -> inp.readShort()
            TAG_Int -> inp.readInt()
            TAG_Long -> inp.readLong()
            TAG_Float -> inp.readFloat()
            TAG_Double -> inp.readDouble()
            TAG_Byte_Array -> {
                val len = inp.readInt()
                val arr = ByteArray(len)
                inp.readFully(arr)
                arr
            }
            TAG_String -> readUtf(inp)
            TAG_List -> readList(inp)
            TAG_Compound -> readCompound(inp)
            TAG_Int_Array -> {
                val len = inp.readInt()
                val arr = IntArray(len)
                for (i in 0 until len) arr[i] = inp.readInt()
                arr
            }
            TAG_Long_Array -> {
                val len = inp.readInt()
                val arr = LongArray(len)
                for (i in 0 until len) arr[i] = inp.readLong()
                arr
            }
            else -> throw IllegalArgumentException("unsupported tag $t")
        }
    }
}
