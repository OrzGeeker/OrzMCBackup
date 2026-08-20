package com.jokerhub.orzmc

import com.jokerhub.orzmc.world.NbtForceLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

class NbtForceLoaderTest {
    /** Helper: write a UTF string as NBT string prefix (short length + bytes) */
    private fun DataOutputStream.writeNbtString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }

    /** Build a GZIP-compressed NBT byte array representing chunks.dat content */
    private fun buildChunksDat(builder: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            DataOutputStream(gz).use { dos ->
                dos.apply(builder)
            }
        }
        return bos.toByteArray()
    }

    /** Write compound entries without a wrapping tag header — for list element compounds */
    private fun DataOutputStream.writeCompoundEntries(
        vararg entries: Pair<String, Pair<Byte, DataOutputStream.() -> Unit>>,
    ) {
        for (entry in entries) {
            val name = entry.first
            val (tagType, payload) = entry.second
            writeByte(tagType.toInt())
            writeNbtString(name)
            payload()
        }
        writeByte(0) // TAG_End
    }

    @Test
    fun `parse legacy Forced long array format`() {
        val data =
            buildChunksDat {
                writeByte(0x0A) // TAG_Compound (root)
                writeNbtString("") // root name
                writeByte(0x0A) // TAG_Compound "data"
                writeNbtString("data")
                writeByte(0x0C) // TAG_Long_Array "Forced"
                writeNbtString("Forced")
                writeInt(4) // 4 longs = 2 chunk positions
                writeLong(1) // chunk x=1
                writeLong(2) // chunk z=2
                writeLong(10) // chunk x=10
                writeLong(20) // chunk z=20
                writeByte(0) // TAG_End (data)
                writeByte(0) // TAG_End (root)
            }

        val result =
            NbtForceLoader.parse(
                java.io.File.createTempFile("chunks", ".dat").apply {
                    writeBytes(data)
                    deleteOnExit()
                },
            )

        assertEquals(2, result.size, "should parse 2 forced chunks")
        assertTrue(result.contains(1 to 2), "should contain (1, 2)")
        assertTrue(result.contains(10 to 20), "should contain (10, 20)")
    }

    @Test
    fun `parse new tickets format`() {
        val tagString = 0x08.toByte()
        val tagIntArray = 0x0B.toByte()
        val tagCompound = 0x0A
        val tagList = 0x09
        val tagEnd = 0x00

        val data =
            buildChunksDat {
                writeByte(tagCompound) // TAG_Compound (root)
                writeNbtString("")
                writeByte(tagCompound) // TAG_Compound "data"
                writeNbtString("data")
                writeByte(tagList) // TAG_List "tickets"
                writeNbtString("tickets")
                writeByte(tagCompound) // element type: TAG_Compound
                writeInt(3) // 3 tickets

                // Ticket 1: forced chunk (3, 4)
                writeCompoundEntries(
                    "type" to (tagString to { writeNbtString("minecraft:forced") }),
                    "chunk_pos" to (
                        tagIntArray to {
                            writeInt(2)
                            writeInt(3)
                            writeInt(4)
                        }
                    ),
                )

                // Ticket 2: not forced (different type)
                writeCompoundEntries(
                    "type" to (tagString to { writeNbtString("minecraft:other") }),
                    "chunk_pos" to (
                        tagIntArray to {
                            writeInt(2)
                            writeInt(5)
                            writeInt(6)
                        }
                    ),
                )

                // Ticket 3: forced chunk (5, 6)
                writeCompoundEntries(
                    "type" to (tagString to { writeNbtString("minecraft:forced") }),
                    "chunk_pos" to (
                        tagIntArray to {
                            writeInt(2)
                            writeInt(5)
                            writeInt(6)
                        }
                    ),
                )

                writeByte(tagEnd) // TAG_End (data)
                writeByte(tagEnd) // TAG_End (root)
            }

        val result =
            NbtForceLoader.parse(
                java.io.File.createTempFile("chunks", ".dat").apply {
                    writeBytes(data)
                    deleteOnExit()
                },
            )

        assertTrue(result.contains(3 to 4), "should contain forced ticket (3, 4)")
        assertTrue(result.contains(5 to 6), "should contain forced ticket (5, 6)")
        assertEquals(2, result.size, "should have exactly 2 forced entries")
    }

    @Test
    fun `empty file throws exception in NbtForceLoader`() {
        assertThrows(Exception::class.java) {
            NbtForceLoader.parse(
                java.io.File.createTempFile("empty", ".dat").apply {
                    deleteOnExit()
                },
            )
        }
    }

    @Test
    fun `file with no data compound returns empty list`() {
        val data =
            buildChunksDat {
                writeByte(0x0A) // TAG_Compound (root)
                writeNbtString("")
                writeByte(0x0A) // TAG_Compound "other"
                writeNbtString("other")
                writeByte(0) // TAG_End (other)
                writeByte(0) // TAG_End (root)
            }

        val result =
            NbtForceLoader.parse(
                java.io.File.createTempFile("chunks", ".dat").apply {
                    writeBytes(data)
                    deleteOnExit()
                },
            )

        assertTrue(result.isEmpty(), "no data compound should return empty list")
    }

    @Test
    fun `file with empty Forced array returns empty list`() {
        val data =
            buildChunksDat {
                writeByte(0x0A) // TAG_Compound (root)
                writeNbtString("")
                writeByte(0x0A) // TAG_Compound "data"
                writeNbtString("data")
                writeByte(0x0C) // TAG_Long_Array "Forced"
                writeNbtString("Forced")
                writeInt(0) // empty long array
                writeByte(0) // TAG_End (data)
                writeByte(0) // TAG_End (root)
            }

        val result =
            NbtForceLoader.parse(
                java.io.File.createTempFile("chunks", ".dat").apply {
                    writeBytes(data)
                    deleteOnExit()
                },
            )

        assertTrue(result.isEmpty(), "empty forced array should return empty list")
    }

    @Test
    fun `oversized byte array is rejected with custom limit`() {
        val data =
            buildChunksDat {
                writeByte(0x0A) // TAG_Compound (root)
                writeNbtString("")
                writeByte(0x0A) // TAG_Compound "data"
                writeNbtString("data")
                writeByte(0x07) // TAG_Byte_Array "oversized"
                writeNbtString("oversized")
                writeInt(101) // exceeds custom maxArraySize=100
                writeByte(0) // TAG_End (data)
                writeByte(0) // TAG_End (root)
            }
        assertThrows(
            IllegalArgumentException::class.java,
            {
                NbtForceLoader.parse(
                    java.io.File.createTempFile("chunks", ".dat").apply {
                        writeBytes(data)
                        deleteOnExit()
                    },
                    maxArraySize = 100,
                )
            },
            "oversized byte array should be rejected",
        )
    }

    @Test
    fun `parse chunk_tickets_dat with tickets format`() {
        val data =
            buildChunksDat {
                val tagString: Byte = 0x08
                val tagIntArray: Byte = 0x0B
                val tagCompound: Byte = 0x0A
                val tagList: Byte = 0x09
                val tagEnd: Byte = 0x00

                writeByte(tagCompound.toInt()) // TAG_Compound (root)
                writeNbtString("")
                writeByte(tagCompound.toInt()) // TAG_Compound "data"
                writeNbtString("data")
                writeByte(tagList.toInt()) // TAG_List "tickets"
                writeNbtString("tickets")
                writeByte(tagCompound.toInt()) // element type
                writeInt(3) // 3 tickets

                // Ticket 1: forced (100, 200)
                writeCompoundEntries(
                    "type" to (tagString to { writeNbtString("minecraft:forced") }),
                    "chunk_pos" to (
                        tagIntArray to {
                            writeInt(2)
                            writeInt(100)
                            writeInt(200)
                        }
                    ),
                )
                // Ticket 2: not forced (should be skipped)
                writeCompoundEntries(
                    "type" to (tagString to { writeNbtString("minecraft:other") }),
                    "chunk_pos" to (
                        tagIntArray to {
                            writeInt(2)
                            writeInt(300)
                            writeInt(400)
                        }
                    ),
                )
                // Ticket 3: forced (500, 600)
                writeCompoundEntries(
                    "type" to (tagString to { writeNbtString("minecraft:forced") }),
                    "chunk_pos" to (
                        tagIntArray to {
                            writeInt(2)
                            writeInt(500)
                            writeInt(600)
                        }
                    ),
                )

                writeByte(tagEnd.toInt()) // TAG_End (data)
                writeByte(tagEnd.toInt()) // TAG_End (root)
            }

        val result =
            NbtForceLoader.parse(
                java.io.File.createTempFile("chunk_tickets", ".dat").apply {
                    writeBytes(data)
                    deleteOnExit()
                },
            )

        assertTrue(result.contains(100 to 200), "should contain forced ticket (100, 200)")
        assertTrue(result.contains(500 to 600), "should contain forced ticket (500, 600)")
        assertEquals(2, result.size, "should have exactly 2 forced entries, skipping non-forced ticket")
    }

    @Test
    fun `oversized list is rejected with custom limit`() {
        val maxListLength = 5
        val data =
            buildChunksDat {
                writeByte(0x0A) // TAG_Compound (root)
                writeNbtString("")
                writeByte(0x0A) // TAG_Compound "data"
                writeNbtString("data")
                writeByte(0x09) // TAG_List "tickets"
                writeNbtString("tickets")
                writeByte(0x0A) // element type: TAG_Compound
                writeInt(maxListLength + 1) // exceeds custom limit
                writeByte(0) // TAG_End (data)
                writeByte(0) // TAG_End (root)
            }
        assertThrows(
            IllegalArgumentException::class.java,
            {
                NbtForceLoader.parse(
                    java.io.File.createTempFile("chunks", ".dat").apply {
                        writeBytes(data)
                        deleteOnExit()
                    },
                    maxListLength = maxListLength,
                )
            },
            "oversized list should be rejected",
        )
    }

    @Test
    fun `nested compound exceeding max depth is rejected`() {
        val data =
            buildChunksDat {
                writeByte(0x0A) // TAG_Compound (root)
                writeNbtString("")
                // root(0) > data(1) > lvl1(2) > lvl2(3); limit 2 allows up to lvl1.
                writeByte(0x0A) // TAG_Compound "data"
                writeNbtString("data")
                writeByte(0x0A) // TAG_Compound "lvl1"
                writeNbtString("lvl1")
                writeByte(0x0A) // TAG_Compound "lvl2" — depth 3 exceeds the limit
                writeNbtString("lvl2")
                writeByte(0) // TAG_End (lvl2)
                writeByte(0) // TAG_End (lvl1)
                writeByte(0) // TAG_End (data)
                writeByte(0) // TAG_End (root)
            }
        assertThrows(
            IllegalArgumentException::class.java,
            {
                NbtForceLoader.parse(
                    java.io.File.createTempFile("chunks", ".dat").apply {
                        writeBytes(data)
                        deleteOnExit()
                    },
                    maxCompoundDepth = 2,
                )
            },
            "compound nesting beyond maxCompoundDepth should be rejected",
        )
    }

    @Test
    fun `compound nesting within max depth parses normally`() {
        val data =
            buildChunksDat {
                writeByte(0x0A) // TAG_Compound (root)
                writeNbtString("")
                writeByte(0x0A) // TAG_Compound "data"
                writeNbtString("data")
                writeByte(0x0C) // TAG_Long_Array "Forced"
                writeNbtString("Forced")
                writeInt(2)
                writeLong(7)
                writeLong(8)
                writeByte(0x0A) // TAG_Compound "lvl1" — depth 2, at the limit
                writeNbtString("lvl1")
                writeByte(0) // TAG_End (lvl1)
                writeByte(0) // TAG_End (data)
                writeByte(0) // TAG_End (root)
            }
        val result =
            NbtForceLoader.parse(
                java.io.File.createTempFile("chunks", ".dat").apply {
                    writeBytes(data)
                    deleteOnExit()
                },
                maxCompoundDepth = 2,
            )
        assertTrue(result.contains(7 to 8), "forced chunk nested within depth limit should parse")
    }
}
