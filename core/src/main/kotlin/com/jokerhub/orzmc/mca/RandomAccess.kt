package com.jokerhub.orzmc.mca

import java.io.RandomAccessFile

/** Abstraction for random-access file reads. Implementations may back real files or byte arrays. */
interface RandomAccess {
    /** Move to position [pos]. */
    fun seek(pos: Long)

    /** Read exactly `buf.size` bytes into [buf]. */
    fun readFully(buf: ByteArray)

    /** Read exactly [len] bytes into [buf] at offset [off]. */
    fun readFully(
        buf: ByteArray,
        off: Int,
        len: Int,
    )

    /** Close and release resources. */
    fun close()
}

/** [RandomAccess] implementation backed by a [RandomAccessFile]. */
class RafAccess(
    private val raf: RandomAccessFile,
) : RandomAccess {
    override fun seek(pos: Long) {
        raf.seek(pos)
    }

    override fun readFully(buf: ByteArray) {
        raf.readFully(buf)
    }

    override fun readFully(
        buf: ByteArray,
        off: Int,
        len: Int,
    ) {
        raf.readFully(buf, off, len)
    }

    override fun close() {
        raf.close()
    }
}

/**
 * Buffered wrapper around [RafAccess] that reduces system calls for MCA file reads.
 *
 * MCA files use a 4096-byte sector structure. Reads are typically at sector-aligned
 * offsets (header: 8192 bytes from position 0; chunk data: 4096-aligned offsets).
 * This buffer aligns reads to [bufferSize] (default 8192) so sequential reads within
 * the same aligned block reuse the cached data instead of issuing new system calls.
 */
class BufferedRafAccess(
    private val delegate: RafAccess,
    private val fileLength: Long,
    private val bufferSize: Int = 8192,
) : RandomAccess {
    private var buf = ByteArray(bufferSize)
    private var bufStart = -1L
    private var bufPos = 0
    private var bufLen = 0

    private fun alignDown(pos: Long): Long = pos / bufferSize * bufferSize

    private fun ensure(pos: Long) {
        val aligned = alignDown(pos)
        if (aligned >= bufStart && aligned < bufStart + bufLen && bufPos < bufLen) {
            return // still inside current buffer
        }
        bufStart = aligned
        delegate.seek(bufStart)
        val remaining = (fileLength - bufStart).coerceAtLeast(0)
        val readLen = minOf(bufferSize, remaining.toInt())
        if (readLen <= 0) {
            bufLen = 0
            bufPos = 0
            return
        }
        if (buf.size < readLen) buf = ByteArray(readLen)
        delegate.readFully(buf, 0, readLen)
        bufLen = readLen
        bufPos = 0
    }

    override fun seek(pos: Long) {
        ensure(pos)
        bufPos = (pos - bufStart).toInt()
    }

    override fun readFully(buf: ByteArray) {
        readFully(buf, 0, buf.size)
    }

    override fun readFully(
        dst: ByteArray,
        off: Int,
        len: Int,
    ) {
        var remaining = len
        var dstOff = off
        while (remaining > 0) {
            if (bufPos >= bufLen) {
                // current buffer exhausted, advance to next aligned block
                bufStart += bufferSize
                ensure(bufStart)
            }
            val avail = minOf(remaining, bufLen - bufPos)
            if (avail <= 0) {
                // 数据块越过了文件末尾（损坏 chunk 的 offset/len 组合非法）：
                // 抛 EOF 而非死循环（旧实现 remaining 不减 → 无限循环卡死）
                throw java.io.EOFException("Unexpected end of file at offset $bufStart")
            }
            System.arraycopy(this.buf, bufPos, dst, dstOff, avail)
            bufPos += avail
            dstOff += avail
            remaining -= avail
        }
    }

    override fun close() {
        delegate.close()
    }
}

class MemoryAccess(
    private val data: ByteArray,
) : RandomAccess {
    private var pos: Int = 0

    override fun seek(pos: Long) {
        this.pos = pos.toInt()
    }

    override fun readFully(buf: ByteArray) {
        System.arraycopy(data, pos, buf, 0, buf.size)
        pos += buf.size
    }

    override fun readFully(
        buf: ByteArray,
        off: Int,
        len: Int,
    ) {
        System.arraycopy(data, pos, buf, off, len)
        pos += len
    }

    override fun close() {
    }
}
