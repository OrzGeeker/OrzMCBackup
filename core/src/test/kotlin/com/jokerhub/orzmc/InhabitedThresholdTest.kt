package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.patterns.InhabitedTimePattern
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class InhabitedThresholdTest {
    @Test
    fun `lower threshold should keep at least as many chunks as higher threshold`() {
        val url = this::class.java.classLoader.getResource("Fixtures/world/region/r.0.0.mca")
        assertTrue(url != null, "fixtures missing: src/test/resources/Fixtures")
        McaReader.open(Paths.get(url!!.toURI()).toString()).use { r ->
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

    @Test
    fun `threshold minus one keeps every chunk including never visited`() {
        // 插件备份模式必须完整保留世界：threshold=-1 → 保留所有 chunk
        // （InhabitedTimePattern 语义「保留 InhabitedTime > threshold」，-1 恒真；
        //   含 InhabitedTime=0 的生成后从未访问 chunk。2026-08-20 备份丢数据修复的回归护栏）
        val url = this::class.java.classLoader.getResource("Fixtures/world/region/r.0.0.mca")
        assertTrue(url != null, "fixtures missing: src/test/resources/Fixtures")
        McaReader.open(Paths.get(url!!.toURI()).toString()).use { r ->
            val entries = r.entries()
            val patAll = InhabitedTimePattern(threshold = -1, removeUnknown = false)
            val keepAll = entries.count { patAll.matches(it) }
            assertTrue(
                keepAll == entries.count(),
                "threshold=-1 必须保留全部 ${entries.count()} 个 chunk，实际只保留 $keepAll",
            )
        }
    }
}
