package com.example.thanos

import com.example.thanos.mca.McaReader
import com.example.thanos.patterns.ListPattern
import com.example.thanos.world.NbtForceLoader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import org.junit.jupiter.api.Assumptions.assumeTrue

class ForceLoadedListTest {
    @Test
    fun `forced coords should match some entries`() {
        val url = this::class.java.classLoader.getResource("Fixtures/world/data/chunks.dat")
        assumeTrue(url != null, "fixtures missing: src/test/resources/Fixtures")
        val forced = NbtForceLoader.parse(Paths.get(url!!.toURI()).toFile())
        val pattern = ListPattern(forced.map { it.first to it.second })
        val regionUrl = this::class.java.classLoader.getResource("Fixtures/world/region/r.0.0.mca")
        assumeTrue(regionUrl != null, "fixtures missing: src/test/resources/Fixtures")
        val r = McaReader.open(Paths.get(regionUrl!!.toURI()).toString())
        val entries = r.entries()
        val anyMatch = entries.any { pattern.matches(it) }
        assertTrue(anyMatch, "expected at least one entry to be forced-loaded")
    }
}
