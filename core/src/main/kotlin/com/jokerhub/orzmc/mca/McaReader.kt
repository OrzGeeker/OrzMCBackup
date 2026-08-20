package com.jokerhub.orzmc.mca

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern

/**
 * Reads Minecraft Anvil region files (.mca).
 *
 * Each region file contains a 8 KiB header (4 KiB location table + 4 KiB timestamp table)
 * followed by sector-aligned chunk data. This class parses the header and provides
 * access to individual chunk entries via [entries] or [get].
 *
 * @param xPos region X coordinate (derived from filename)
 * @param zPos region Z coordinate (derived from filename)
 */
class McaReader(
    private val file: RandomAccess,
    private val path: String,
    val xPos: Int,
    val zPos: Int,
) : AutoCloseable {
    private var offsets: IntArray? = null
    private var sizes: IntArray? = null
    private var timestamps: IntArray? = null

    companion object {
        private val FILENAME_RE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca$")

        /** Open a region file from disk. The filename must match `r.x.z.mca`. */
        fun open(path: String): McaReader {
            val m = FILENAME_RE.matcher(path)
            require(m.find()) { "invalid mca filename: $path" }
            val x = m.group(1).toInt()
            val z = m.group(2).toInt()
            val raf = RandomAccessFile(File(path), "r")
            val fileLen = raf.length()
            return McaReader(BufferedRafAccess(RafAccess(raf), fileLen), path, x, z)
        }

        /** Open a region file from in-memory bytes. Useful for testing. */
        fun openFromBytes(
            path: String,
            bytes: ByteArray,
        ): McaReader {
            val m = FILENAME_RE.matcher(path)
            require(m.find()) { "invalid mca filename: $path" }
            val x = m.group(1).toInt()
            val z = m.group(2).toInt()
            return McaReader(MemoryAccess(bytes), path, x, z)
        }
    }

    private fun readHeader() {
        file.seek(0)
        val loc = ByteArray(4096)
        file.readFully(loc)
        val time = ByteArray(4096)
        file.readFully(time)
        val offs = IntArray(1024)
        val sizesArr = IntArray(1024)
        for (i in 0 until 1024) {
            val base = i * 4
            val v = ByteBuffer.wrap(loc, base, 4).order(ByteOrder.BIG_ENDIAN).int
            val off = (v ushr 8) * 4096
            val size = (v and 0xFF) * 4096
            offs[i] = off
            sizesArr[i] = size
        }
        val ts = IntArray(1024)
        for (i in 0 until 1024) {
            val base = i * 4
            ts[i] = ByteBuffer.wrap(time, base, 4).order(ByteOrder.BIG_ENDIAN).int
        }
        offsets = offs
        sizes = sizesArr
        timestamps = ts
    }

    private fun ensure() {
        if (offsets == null) readHeader()
    }

    /** Return all chunk entries in this region file (skipping unused sector slots). */
    fun entries(): List<McaEntry> {
        ensure()
        val offs = offsets!!
        val sizesArr = sizes!!
        val ts = timestamps!!
        val out = ArrayList<McaEntry>()
        for (i in 0 until 1024) {
            val off = offs[i]
            val size = sizesArr[i]
            val t = ts[i]
            if (off == 0 || size == 0) continue
            out.add(
                McaEntry(
                    file = file,
                    start = off.toLong(),
                    length = size,
                    index = i,
                    modified = t,
                    regionX = xPos,
                    regionZ = zPos,
                ),
            )
        }
        return out
    }

    /** Get a single chunk entry by sector index (0-1023), or null if unused. */
    fun get(index: Int): McaEntry? {
        ensure()
        val off = offsets!![index]
        val size = sizes!![index]
        val ts = timestamps!![index]
        if (off == 0 || size == 0) return null
        return McaEntry(file, off.toLong(), size, index, ts, xPos, zPos)
    }

    /** Count used chunk slots without allocating [McaEntry] objects. */
    fun count(): Int {
        ensure()
        var n = 0
        for (i in 0 until 1024) {
            if (offsets!![i] != 0 && sizes!![i] != 0) n++
        }
        return n
    }

    override fun close() {
        try {
            file.close()
        } catch (_: Exception) {
        }
    }
}
