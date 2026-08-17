package com.jokerhub.orzmc.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MergeReportIOTest {
    @Test
    fun `toText prints statistics and error list`() {
        val r =
            MergeReport(
                mergedRegions = 3,
                copiedFiles = 7,
                patchSlots = 233021,
                baseSlots = 463657,
                linkedEntities = 98384,
                linkedPoi = 10022,
                overlayFiles = 7353,
                errors = listOf(OptimizeError("world/region/r.-1.0.mca", "MCA", "boom")),
            )
        val text = MergeReportIO.toText(r)
        assertTrue(text.startsWith("Statistics: mergedRegions=3"))
        assertTrue(text.contains("patchSlots=233021"))
        assertTrue(text.contains("baseSlots=463657"))
        assertTrue(text.contains("linkedEntities=98384"))
        assertTrue(text.contains("linkedPoi=10022"))
        assertTrue(text.contains("copiedFiles=7"))
        assertTrue(text.contains("overlayFiles=7353"))
        assertTrue(text.contains("errors=1"))
        assertTrue(text.contains("[MCA] world/region/r.-1.0.mca - boom"))
    }

    @Test
    fun `toText omits error list when empty`() {
        val r = MergeReport(mergedRegions = 1)
        val text = MergeReportIO.toText(r)
        assertEquals(
            "Statistics: mergedRegions=1 patchSlots=0 baseSlots=0 linkedEntities=0 " +
                "linkedPoi=0 copiedFiles=0 overlayFiles=0 errors=0",
            text,
        )
        assertFalse(text.contains("Error list"))
    }

    @Test
    fun `toOptimizeReport maps slots and errors to optimize shapes`() {
        val err = OptimizeError("x", "y", "z")
        val r = MergeReport(patchSlots = 100, baseSlots = 50, errors = listOf(err))
        val o = MergeReportIO.toOptimizeReport(r)
        assertEquals(100, o.processedChunks)
        assertEquals(50, o.removedChunks)
        assertEquals(listOf(err), o.errors)
    }
}
