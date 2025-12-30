package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.patterns.InhabitedTimePattern
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import org.junit.jupiter.api.Assumptions.assumeTrue

class InhabitedThresholdTest {
    @Test
    fun `lower threshold should keep at least as many chunks as higher threshold`() {
        val url = this::class.java.classLoader.getResource("Fixtures/world/region/r.0.0.mca")
        assumeTrue(url != null, "fixtures missing: src/test/resources/Fixtures")
        val r = McaReader.open(Paths.get(url!!.toURI()).toString())
        val entries = r.entries()

        // 0 seconds (0 ticks) vs very high threshold
        val patLow = InhabitedTimePattern(threshold = 0, removeUnknown = false)
        val patHigh = InhabitedTimePattern(threshold = 1000000L * 20L, removeUnknown = false)

        val keepLow = entries.count { patLow.matches(it) }
        val keepHigh = entries.count { patHigh.matches(it) }

        assertTrue(keepLow >= keepHigh, "expected low threshold to keep >= high threshold")
        assertTrue(keepLow > 0, "expected some chunks to be kept with low threshold")
    }
}
