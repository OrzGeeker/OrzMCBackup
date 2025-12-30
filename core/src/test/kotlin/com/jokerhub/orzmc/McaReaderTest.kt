package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaReader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.nio.file.Files
import org.junit.jupiter.api.Assumptions.assumeTrue

class McaReaderTest {
    @Test
    fun `read entries from fixture`() {
        val url = this::class.java.classLoader.getResource("Fixtures/world/region/r.0.0.mca")
        assumeTrue(url != null, "fixtures missing: src/test/resources/Fixtures")
        val p = Paths.get(url!!.toURI())
        val r = McaReader.open(p.toString())
        val entries = r.entries()
        assertTrue(entries.size > 0)
    }
}
