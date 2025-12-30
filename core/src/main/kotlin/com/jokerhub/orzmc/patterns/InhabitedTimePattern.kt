package com.jokerhub.orzmc.patterns

import com.jokerhub.orzmc.mca.McaEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InhabitedTimePattern(
    private val threshold: Long,
    private val removeUnknown: Boolean,
) : ChunkPattern {

    override fun matches(entry: McaEntry): Boolean {
        if (entry.isExternal()) return !removeUnknown
        val data = entry.allDataUncompressed()
        if (data.isEmpty()) return !removeUnknown
        val t = findInhabitedFast(data)
        return t?.let { it >= threshold } ?: (!removeUnknown)
    }

    companion object {
        private const val LONG_TAG: Byte = 4
        private fun findInhabitedFast(data: ByteArray): Long? {
            val name = "InhabitedTime".toByteArray()
            val prefix = ByteArray(1 + 2 + name.size)
            prefix[0] = LONG_TAG
            val bb = ByteBuffer.wrap(prefix, 1, 2).order(ByteOrder.BIG_ENDIAN)
            bb.putShort(name.size.toShort())
            System.arraycopy(name, 0, prefix, 3, name.size)
            val plen = prefix.size
            var i = 0
            while (i + plen + 8 <= data.size) {
                var match = true
                for (j in 0 until plen) {
                    if (data[i + j] != prefix[j]) { match = false; break }
                }
                if (match) {
                    val v = ByteBuffer.wrap(data, i + plen, 8).order(ByteOrder.BIG_ENDIAN).long
                    return v
                }
                i++
            }
            return null
        }
    }
}
