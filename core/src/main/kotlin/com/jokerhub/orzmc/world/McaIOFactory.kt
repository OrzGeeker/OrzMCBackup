package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.mca.McaEntry
import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.mca.McaWriter
import java.nio.file.Path

interface McaReaderLike {
    fun entries(): List<McaEntry>
    fun get(index: Int): McaEntry?
}

interface McaWriterLike {
    fun writeEntry(entry: McaEntry)
    fun finalizeFile()
}

interface McaIOFactory {
    fun openReader(fs: FileSystem, path: Path): McaReaderLike
    fun createWriter(fs: FileSystem, path: Path): McaWriterLike
}

class DefaultMcaIOFactory : McaIOFactory {
    override fun openReader(fs: FileSystem, path: Path): McaReaderLike {
        val real = fs.toRealPath(path)
        return RealMcaReaderAdapter(McaReader.open(real.toString()))
    }

    override fun createWriter(fs: FileSystem, path: Path): McaWriterLike {
        val real = fs.toRealPath(path)
        return RealMcaWriterAdapter(McaWriter(real.toString()))
    }
}

class RealMcaWriterAdapter(private val delegate: McaWriter) : McaWriterLike {
    override fun writeEntry(entry: McaEntry) {
        delegate.writeEntry(entry)
    }

    override fun finalizeFile() {
        delegate.finalizeFile()
    }
}

class RealMcaReaderAdapter(private val delegate: McaReader) : McaReaderLike {
    override fun entries(): List<McaEntry> = delegate.entries()
    override fun get(index: Int): McaEntry? = delegate.get(index)
}

class MemoryMcaWriter(private val mem: MemoryFS, private val path: Path) : McaWriterLike {
    private var dataOffset = 8192
    private val offsets = IntArray(1024)
    private val sizes = IntArray(1024)
    private val timestamps = IntArray(1024)
    private val data = java.io.ByteArrayOutputStream()

    override fun writeEntry(entry: McaEntry) {
        val serialized = entry.serializedBytes()
        val start = dataOffset
        data.write(serialized)
        val written = serialized.size
        val pad = (4096 - (written % 4096)) % 4096
        if (pad > 0) data.write(ByteArray(pad))
        dataOffset += written + pad
        val idx = entry.regionIndex()
        offsets[idx] = start
        sizes[idx] = written + pad
        timestamps[idx] = entry.modifiedTime()
    }

    override fun finalizeFile() {
        val loc = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.BIG_ENDIAN)
        for (i in 0 until 1024) {
            val offSectors = (offsets[i] / 4096)
            val sizeSectors = (sizes[i] / 4096)
            val v = (offSectors shl 8) or (sizeSectors and 0xFF)
            loc.putInt(v)
        }
        val time = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.BIG_ENDIAN)
        for (i in 0 until 1024) {
            time.putInt(timestamps[i])
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(loc.array())
        out.write(time.array())
        out.write(data.toByteArray())
        mem.write(path, out.toByteArray())
    }
}

class MemoryMcaIOFactory : McaIOFactory {
    override fun openReader(fs: FileSystem, path: Path): McaReaderLike {
        val mem = fs as? MemoryFS ?: throw IllegalArgumentException("MemoryMcaIOFactory requires MemoryFS")
        val bytes = mem.read(path) ?: ByteArray(0)
        return RealMcaReaderAdapter(McaReader.openFromBytes(path.fileName.toString(), bytes))
    }

    override fun createWriter(fs: FileSystem, path: Path): McaWriterLike {
        val mem = fs as? MemoryFS ?: throw IllegalArgumentException("MemoryMcaIOFactory requires MemoryFS")
        return MemoryMcaWriter(mem, path)
    }
}
