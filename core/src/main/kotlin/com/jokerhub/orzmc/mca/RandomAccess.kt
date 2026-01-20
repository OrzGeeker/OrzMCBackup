package com.jokerhub.orzmc.mca

import java.io.RandomAccessFile

interface RandomAccess {
    fun seek(pos: Long)
    fun readFully(buf: ByteArray)
    fun readFully(buf: ByteArray, off: Int, len: Int)
}

class RafAccess(private val raf: RandomAccessFile) : RandomAccess {
    override fun seek(pos: Long) {
        raf.seek(pos)
    }

    override fun readFully(buf: ByteArray) {
        raf.readFully(buf)
    }

    override fun readFully(buf: ByteArray, off: Int, len: Int) {
        raf.readFully(buf, off, len)
    }
}

class MemoryAccess(private val data: ByteArray) : RandomAccess {
    private var pos: Int = 0
    override fun seek(pos: Long) {
        this.pos = pos.toInt()
    }

    override fun readFully(buf: ByteArray) {
        System.arraycopy(data, pos, buf, 0, buf.size)
        pos += buf.size
    }

    override fun readFully(buf: ByteArray, off: Int, len: Int) {
        System.arraycopy(data, pos, buf, off, len)
        pos += len
    }
}
