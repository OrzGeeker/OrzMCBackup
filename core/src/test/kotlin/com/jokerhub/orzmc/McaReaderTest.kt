package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class McaReaderTest {
    @Test
    fun `read entries from fixture`() {
        val url = this::class.java.classLoader.getResource("Fixtures/world/region/r.0.0.mca")
        assertTrue(url != null, "fixtures missing: src/test/resources/Fixtures")
        val p = Paths.get(url!!.toURI())
        McaReader.open(p.toString()).use { r ->
            val entries = r.entries()
            assertTrue(entries.isNotEmpty())
            val first = entries.first()
            val byIndex = r.get(first.regionIndex())
            assertTrue(byIndex != null)
            assertEquals(first.regionIndex(), byIndex!!.regionIndex())
        }
    }
}
