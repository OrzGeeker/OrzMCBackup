package com.jokerhub.orzmc.mca

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes Minecraft Anvil region files (.mca).
 *
 * Supports writing chunk entries with 4 KiB sector alignment and generating
 * the location/timestamp header tables. Call [writeEntry] for each chunk,
 * then [finalizeFile] to flush the header, then [close].
 */
class McaWriter(
    path: String,
) {
    private val file = RandomAccessFile(File(path), "rw")
    private var dataOffset = 8192L
    private val offsets = IntArray(1024)
    private val sizes = IntArray(1024)
    private val timestamps = IntArray(1024)

    init {
        file.setLength(0)
        file.write(ByteArray(8192))
    }

    fun writeEntry(entry: McaEntry) {
        val serialized = entry.serializedBytes()
        if (serialized.isEmpty()) {
            // 损坏 chunk（长度字段荒谬，数据不可信）：跳过并明确报错，由调用方记录后继续
            throw IllegalStateException(
                "Chunk data unreadable (corrupted length field): index ${entry.regionIndex()}",
            )
        }
        val start = dataOffset
        file.seek(start)
        file.write(serialized)
        val written = serialized.size.toLong()
        val pad = (4096 - (written % 4096)) % 4096
        if (pad > 0) file.write(ByteArray(pad.toInt()))
        dataOffset += written + pad
        val idx = entry.regionIndex()
        offsets[idx] = start.toInt()
        sizes[idx] = (written + pad).toInt()
        timestamps[idx] = entry.modifiedTime()
    }

    fun finalizeFile() {
        val loc = ByteArray(4096)
        val bbLoc = ByteBuffer.wrap(loc).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until 1024) {
            val offSectors = offsets[i] / 4096
            val sizeSectors = sizes[i] / 4096
            val v = (offSectors shl 8) or (sizeSectors and 0xFF)
            bbLoc.putInt(v)
        }
        val time = ByteArray(4096)
        val bbTime = ByteBuffer.wrap(time).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until 1024) {
            bbTime.putInt(timestamps[i])
        }
        file.seek(0)
        file.write(loc)
        file.write(time)
        file.fd.sync()
    }

    fun close() {
        file.close()
    }
}
