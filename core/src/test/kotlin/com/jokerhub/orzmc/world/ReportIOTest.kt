package com.jokerhub.orzmc.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportIOTest {
    @Test
    fun `toJson with no errors`() {
        val report = OptimizeReport(processedChunks = 100, removedChunks = 25, errors = emptyList())
        val json = ReportIO.toJson(report)
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"processedChunks\":100"))
        assertTrue(json.contains("\"removedChunks\":25"))
        assertTrue(json.contains("\"errors\":[]"))
    }

    @Test
    fun `toJson with errors`() {
        val report =
            OptimizeReport(
                processedChunks = 50,
                removedChunks = 10,
                errors =
                    listOf(
                        OptimizeError("/path/file.mca", "MCA", "corrupted"),
                        OptimizeError("/path/other.mca", "Write", "write failed"),
                    ),
            )
        val json = ReportIO.toJson(report)
        assertTrue(json.contains("\"kind\":\"MCA\""))
        assertTrue(json.contains("\"kind\":\"Write\""))
        assertTrue(json.contains("\"corrupted\""))
        assertTrue(json.contains("\"write failed\""))
    }

    @Test
    fun `toJson escapes special characters`() {
        val report =
            OptimizeReport(
                processedChunks = 1,
                removedChunks = 0,
                errors =
                    listOf(
                        OptimizeError("path\"with\"quotes", "kind\\with\\backslash", "msg"),
                    ),
            )
        val json = ReportIO.toJson(report)
        // Verify the value portion has backslash-escaped quotes: path\"with\"quotes
        val valueStart = json.indexOf("\"path\":\"") + "\"path\":\"".length
        val valueEnd = json.indexOf("\",", valueStart)
        val valuePart = json.substring(valueStart, valueEnd)
        assertTrue(valuePart.contains("\\\""), "quotes in path value should be escaped: $valuePart")
        assertTrue(valuePart != "path\"with\"quotes", "value should not contain raw quotes")
        assertTrue(valuePart == "path\\\"with\\\"quotes", "value should have escaped quotes: $valuePart")
    }

    @Test
    fun `toCsv with no errors`() {
        val report = OptimizeReport(processedChunks = 200, removedChunks = 50, errors = emptyList())
        val csv = ReportIO.toCsv(report)
        val lines = csv.trimEnd().lines()
        assertTrue(lines[0].contains("processedChunks,removedChunks,errorsCount"))
        assertTrue(lines[1].contains("200,50,0"))
        assertEquals(3, lines.size) // header1 + data1 + header2 (path,kind,message)
    }

    @Test
    fun `toCsv with errors`() {
        val report =
            OptimizeReport(
                processedChunks = 10,
                removedChunks = 5,
                errors =
                    listOf(
                        OptimizeError("/path/a.mca", "ERR", "msg1"),
                        OptimizeError("/path/b.mca", "ERR", "msg2"),
                    ),
            )
        val csv = ReportIO.toCsv(report)
        val lines = csv.trimEnd().lines()
        assertEquals(5, lines.size) // header1 + data1 + header2 + 2 error rows
        assertTrue(lines[2].contains("path,kind,message"))
        assertTrue(lines[3].contains("msg1"))
        assertTrue(lines[4].contains("msg2"))
    }

    @Test
    fun `toText renders correctly`() {
        val report =
            OptimizeReport(
                processedChunks = 30,
                removedChunks = 15,
                errors =
                    listOf(
                        OptimizeError("/path/e.mca", "ERR", "something went wrong"),
                    ),
            )
        val text = ReportIO.toText(report)
        assertTrue(text.contains("processed=30"))
        assertTrue(text.contains("removed=15"))
        assertTrue(text.contains("errors=1"))
        assertTrue(text.contains("[ERR]"))
        assertTrue(text.contains("/path/e.mca"))
        assertTrue(text.contains("something went wrong"))
    }

    @Test
    fun `write method produces valid json file`() {
        val report = OptimizeReport(processedChunks = 5, removedChunks = 2, errors = emptyList())
        val tmpFile =
            java.nio.file.Files.createTempFile("report", ".json").also {
                it.toFile().deleteOnExit()
            }
        ReportIO.write(report, tmpFile, "json")
        val content =
            String(
                java.nio.file.Files
                    .readAllBytes(tmpFile),
                Charsets.UTF_8,
            )
        assertTrue(content.contains("\"processedChunks\":5"))
    }

    @Test
    fun `write method produces valid csv file`() {
        val report = OptimizeReport(processedChunks = 5, removedChunks = 2, errors = emptyList())
        val tmpFile =
            java.nio.file.Files.createTempFile("report", ".csv").also {
                it.toFile().deleteOnExit()
            }
        ReportIO.write(report, tmpFile, "csv")
        val content =
            String(
                java.nio.file.Files
                    .readAllBytes(tmpFile),
                Charsets.UTF_8,
            )
        assertTrue(content.contains("5,2,0"))
    }

    @Test
    fun `toJson round-trips through a real JSON parser`() {
        val report =
            OptimizeReport(
                processedChunks = 42,
                removedChunks = 7,
                errors =
                    listOf(
                        OptimizeError("/world/region/r.0.0.mca", "MCA", "corrupted"),
                        OptimizeError("/world/region/r.1.0.mca", "Write", "write failed"),
                    ),
            )
        val parsed = JsonTestParser.parse(ReportIO.toJson(report)) as JValue.JObject

        assertEquals("42", (parsed.fields["processedChunks"] as JValue.JNumber).raw)
        assertEquals("7", (parsed.fields["removedChunks"] as JValue.JNumber).raw)
        val errors = parsed.fields["errors"] as JValue.JArray
        assertEquals(2, errors.items.size)
        val first = errors.items[0] as JValue.JObject
        assertEquals("/world/region/r.0.0.mca", (first.fields["path"] as JValue.JString).value)
        assertEquals("MCA", (first.fields["kind"] as JValue.JString).value)
        assertEquals("corrupted", (first.fields["message"] as JValue.JString).value)
    }

    @Test
    fun `toJson escapes every special character and round-trips byte-identical`() {
        // Every escape the serializer emits must survive a parse round-trip losslessly.
        // U+0001 is appended from a computed char so the source never needs the \uXXXX
        // escape (which tooling may pre-decode).
        // \f (form feed) is not a Kotlin escape sequence, so it is appended as a computed
        // char together with the U+0001 control char.
        val message = "line1\nline2\t\"quoted\"\\back\\slash\b\r" + 12.toChar() + 1.toChar() + "end"
        val report =
            OptimizeReport(
                processedChunks = 1,
                removedChunks = 0,
                errors = listOf(OptimizeError("path\"with\\chars\n", "kind", message)),
            )
        val parsed = JsonTestParser.parse(ReportIO.toJson(report)) as JValue.JObject
        val errors = parsed.fields["errors"] as JValue.JArray
        val err = errors.items[0] as JValue.JObject

        assertEquals(message, (err.fields["message"] as JValue.JString).value)
        assertEquals("path\"with\\chars\n", (err.fields["path"] as JValue.JString).value)
        assertEquals(1, errors.items.size)
    }
}
