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

    @Test
    fun `toJson round-trips through a real JSON parser`() {
        val r =
            MergeReport(
                mergedRegions = 3,
                copiedFiles = 7,
                patchSlots = 233021,
                baseSlots = 463657,
                linkedEntities = 98384,
                linkedPoi = 10022,
                overlayFiles = 7353,
                errors =
                    listOf(
                        OptimizeError("world/region/r.-1.0.mca", "MCA", "boom"),
                        OptimizeError("a\"b\\c\n", "Kind", "tab\there"),
                    ),
            )
        val parsed = JsonTestParser.parse(MergeReportIO.toJson(r)) as JValue.JObject

        assertEquals("3", (parsed.fields["mergedRegions"] as JValue.JNumber).raw)
        assertEquals("7", (parsed.fields["copiedFiles"] as JValue.JNumber).raw)
        assertEquals("233021", (parsed.fields["patchSlots"] as JValue.JNumber).raw)
        assertEquals("463657", (parsed.fields["baseSlots"] as JValue.JNumber).raw)
        assertEquals("98384", (parsed.fields["linkedEntities"] as JValue.JNumber).raw)
        assertEquals("10022", (parsed.fields["linkedPoi"] as JValue.JNumber).raw)
        assertEquals("7353", (parsed.fields["overlayFiles"] as JValue.JNumber).raw)

        val errors = parsed.fields["errors"] as JValue.JArray
        assertEquals(2, errors.items.size)
        val second = errors.items[1] as JValue.JObject
        assertEquals("a\"b\\c\n", (second.fields["path"] as JValue.JString).value)
        assertEquals("tab\there", (second.fields["message"] as JValue.JString).value)
    }

    @Test
    fun `toJson emits real merge counts and escaped errors`() {
        val r =
            MergeReport(
                mergedRegions = 3,
                copiedFiles = 7,
                patchSlots = 233021,
                baseSlots = 463657,
                linkedEntities = 98384,
                linkedPoi = 10022,
                overlayFiles = 7353,
                errors = listOf(OptimizeError("a\"b", "MCA", "line1\nline2")),
            )
        val json = MergeReportIO.toJson(r)
        assertTrue(json.contains("\"mergedRegions\":3"))
        assertTrue(json.contains("\"copiedFiles\":7"))
        assertTrue(json.contains("\"patchSlots\":233021"))
        assertTrue(json.contains("\"baseSlots\":463657"))
        assertTrue(json.contains("\"linkedEntities\":98384"))
        assertTrue(json.contains("\"linkedPoi\":10022"))
        assertTrue(json.contains("\"overlayFiles\":7353"))
        assertTrue(json.contains("\"path\":\"a\\\"b\""))
        assertTrue(json.contains("\"message\":\"line1\\nline2\""))
    }

    @Test
    fun `toCsv writes stats header and error table`() {
        val r =
            MergeReport(
                mergedRegions = 2,
                copiedFiles = 1,
                patchSlots = 100,
                baseSlots = 50,
                errors = listOf(OptimizeError("x", "y", "z")),
            )
        val csv = MergeReportIO.toCsv(r)
        assertTrue(
            csv.startsWith(
                "mergedRegions,copiedFiles,patchSlots,baseSlots,linkedEntities,linkedPoi,overlayFiles,errorsCount\n" +
                    "2,1,100,50,0,0,0,1",
            ),
        )
        assertTrue(csv.contains("\"x\",\"y\",\"z\""))
    }

    @Test
    fun `write persists report to file`() {
        val r = MergeReport(mergedRegions = 1)
        val tmp =
            java.nio.file.Files
                .createTempFile("merge-report-", ".json")
        try {
            MergeReportIO.write(r, tmp, "json")
            val text =
                java.nio.file.Files
                    .readString(tmp)
            assertTrue(text.contains("\"mergedRegions\":1"))
        } finally {
            java.nio.file.Files
                .deleteIfExists(tmp)
        }
    }

    @Test
    fun `write persists csv report to file`() {
        val r =
            MergeReport(
                mergedRegions = 2,
                copiedFiles = 1,
                patchSlots = 100,
                baseSlots = 50,
                errors = listOf(OptimizeError("x", "y", "z")),
            )
        val tmp =
            java.nio.file.Files
                .createTempFile("merge-report-", ".csv")
        try {
            MergeReportIO.write(r, tmp, "csv")
            val text =
                java.nio.file.Files
                    .readString(tmp)
            val header =
                "mergedRegions,copiedFiles,patchSlots,baseSlots,linkedEntities," +
                    "linkedPoi,overlayFiles,errorsCount\n"
            assertTrue(text.startsWith(header))
            assertTrue(text.startsWith(header + "2,1,100,50,0,0,0,1"))
            assertTrue(text.contains("\"x\",\"y\",\"z\""))
        } finally {
            java.nio.file.Files
                .deleteIfExists(tmp)
        }
    }

    @Test
    fun `write creates missing parent directories`() {
        val r = MergeReport(mergedRegions = 1)
        val root =
            java.nio.file.Files
                .createTempDirectory("merge-parent-")
        val target = root.resolve("sub").resolve("deep").resolve("report.json")
        try {
            MergeReportIO.write(r, target, "json")
            assertTrue(
                java.nio.file.Files
                    .exists(target),
            )
            assertTrue(
                java.nio.file.Files
                    .readString(target)
                    .contains("\"mergedRegions\":1"),
            )
        } finally {
            Cleaner.deleteTreeWithRetry(root, 5, 10)
        }
    }
}
