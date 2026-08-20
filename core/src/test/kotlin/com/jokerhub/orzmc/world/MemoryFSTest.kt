package com.jokerhub.orzmc.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class MemoryFSTest {
    @Test
    fun `walk does not cross into sibling directories with a common prefix`() {
        val fs = MemoryFS()
        val world = Paths.get("/mem/world")
        val world2 = Paths.get("/mem/world2")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        fs.createDirectories(world2)
        fs.createDirectories(world2.resolve("region"))
        fs.write(world.resolve("region").resolve("r.0.0.mca"), ByteArray(8))
        fs.write(world2.resolve("region").resolve("r.0.0.mca"), ByteArray(8))

        val expected =
            setOf(
                world,
                world.resolve("region"),
                world.resolve("region").resolve("r.0.0.mca"),
            )
        assertEquals(expected, fs.walk(world).toSet())
    }

    @Test
    fun `list returns only direct children`() {
        val fs = MemoryFS()
        val region = Paths.get("/mem/world").resolve("region")
        fs.createDirectories(region)
        fs.createDirectories(region.resolve("sub"))
        fs.write(region.resolve("r.0.0.mca"), ByteArray(8))
        fs.write(region.resolve("sub").resolve("r.0.1.mca"), ByteArray(8))

        assertEquals(setOf(region.resolve("sub"), region.resolve("r.0.0.mca")), fs.list(region).toSet())
    }

    @Test
    fun `walk includes the root and walk of a file returns only the file`() {
        val fs = MemoryFS()
        val file = Paths.get("/mem/world").resolve("level.dat")
        fs.createDirectories(file.parent)
        fs.write(file, ByteArray(4))
        assertEquals(setOf(file.parent, file), fs.walk(file.parent).toSet())
        assertEquals(listOf(file), fs.walk(file))
    }
}
