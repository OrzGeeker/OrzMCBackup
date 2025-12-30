package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaEntry
import net.jpountz.lz4.LZ4Factory
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Lz4InvalidTest {
    @Test
    fun `decode LZ4Block with wrong checksum fails`() {
        val payload = "hello world".toByteArray()
        val lz4 = LZ4Factory.safeInstance().fastCompressor()
        val maxLen = lz4.maxCompressedLength(payload.size)
        val comp = ByteArray(maxLen)
        val compLen = lz4.compress(payload, 0, payload.size, comp, 0, maxLen)
        val block = comp.copyOfRange(0, compLen)

        val magic = "LZ4Block".toByteArray()
        val token = 0x20.toByte() // LZ4 method
        val decompLen = payload.size
        val checksumLe = 0 // wrong on purpose

        val header = ByteArray(8 + 1 + 4 + 4 + 4)
        System.arraycopy(magic, 0, header, 0, 8)
        header[8] = token
        ByteBuffer.wrap(header, 9, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(block.size)
        ByteBuffer.wrap(header, 13, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(decompLen)
        ByteBuffer.wrap(header, 17, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(checksumLe)

        val buf = ByteArray(header.size + block.size)
        System.arraycopy(header, 0, buf, 0, header.size)
        System.arraycopy(block, 0, buf, header.size, block.size)

        assertThrows(IllegalArgumentException::class.java) {
            McaEntry.decodeLZ4BlocksForTest(buf)
        }
    }
}
