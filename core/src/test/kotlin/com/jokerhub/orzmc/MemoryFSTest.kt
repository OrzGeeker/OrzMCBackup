package com.jokerhub.orzmc

import com.jokerhub.orzmc.world.MemoryFS
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class MemoryFSTest {
    @Test
    fun `memory fs basic ops`() {
        val fs = MemoryFS()
        val root = java.nio.file.Paths.get("/mem/world")
        fs.createDirectories(root)
        fs.createDirectories(root.resolve("region"))
        val file = root.resolve("region").resolve("r.0.0.mca")
        fs.write(file, byteArrayOf(0, 1, 2))
        assertTrue(fs.isDirectory(root))
        assertTrue(fs.exists(file))
        val listed = fs.list(root.resolve("region"))
        assertTrue(listed.any { it.toString().endsWith(".mca") })
        fs.deleteTreeWithRetry(root, 3, 10)
        assertTrue(!fs.exists(root))
    }
}
