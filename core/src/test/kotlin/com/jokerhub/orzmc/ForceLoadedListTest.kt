package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.patterns.ListPattern
import com.jokerhub.orzmc.world.NbtForceLoader
import com.jokerhub.orzmc.util.TestPaths
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import org.junit.jupiter.api.Assumptions.assumeTrue

class ForceLoadedListTest {
    @Test
    fun `forced coords should match some entries`() {
        val forced = NbtForceLoader.parse(TestPaths.worldDataChunks().toFile())
        val pattern = ListPattern(forced.map { it.first to it.second })
        val entries = McaReader.open(TestPaths.worldRegion("r.0.0.mca").toString()).use { it.entries() }
        val anyMatch = entries.any { pattern.matches(it) }
        assertTrue(anyMatch, "expected at least one entry to be forced-loaded")
    }
}
