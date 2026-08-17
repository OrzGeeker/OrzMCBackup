package com.jokerhub.orzmc.world

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * An error recorded during optimization.
 * @property path the file or directory that caused the error
 * @property kind error category (e.g. "MCA", "Entities", "Output")
 * @property message human-readable error description
 */
data class OptimizeError(
    val path: String,
    val kind: String,
    val message: String,
)

/**
 * Summary report for an optimization run.
 * @property processedChunks total chunks that were kept after filtering
 * @property removedChunks total chunks that were removed
 * @property errors list of non-fatal errors encountered
 */
data class OptimizeReport(
    val processedChunks: Long,
    val removedChunks: Long,
    val errors: List<OptimizeError>,
)

/** Serializes [OptimizeReport] to JSON, CSV, or plain text formats. */
object ReportIO {
    internal fun esc(s: String): String {
        val out = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                else ->
                    if (c.code in 0..0x1F) {
                        out.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        out.append(c)
                    }
            }
        }
        return out.toString()
    }

    fun toJson(r: OptimizeReport): String {
        val sb = StringBuilder()
        sb
            .append("{\"processedChunks\":")
            .append(r.processedChunks)
            .append(",\"removedChunks\":")
            .append(r.removedChunks)
            .append(",\"errors\":[")
        r.errors.forEachIndexed { i, e ->
            if (i > 0) sb.append(",")
            sb
                .append("{\"path\":\"")
                .append(esc(e.path))
                .append("\",")
                .append("\"kind\":\"")
                .append(esc(e.kind))
                .append("\",")
                .append("\"message\":\"")
                .append(esc(e.message))
                .append("\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun toCsv(r: OptimizeReport): String {
        val sb = StringBuilder()
        sb
            .append("processedChunks,removedChunks,errorsCount\n")
            .append(
                r.processedChunks,
            ).append(",")
            .append(r.removedChunks)
            .append(",")
            .append(r.errors.size)
            .append("\n")
        sb.append("path,kind,message\n")
        r.errors.forEach { e ->
            val path = e.path.replace("\"", "\"\"")
            val kind = e.kind.replace("\"", "\"\"")
            val message = e.message.replace("\"", "\"\"")
            sb
                .append("\"")
                .append(path)
                .append("\",")
                .append("\"")
                .append(kind)
                .append("\",")
                .append("\"")
                .append(message)
                .append("\"\n")
        }
        return sb.toString()
    }

    fun toText(r: OptimizeReport): String {
        val sb = StringBuilder()
        sb
            .append("Statistics: processed=")
            .append(r.processedChunks)
            .append(" removed=")
            .append(r.removedChunks)
            .append(" errors=")
            .append(r.errors.size)
            .append("\n")
        if (r.errors.isNotEmpty()) {
            sb.append("Error list:\n")
            r.errors.forEach { e ->
                sb
                    .append("[")
                    .append(e.kind)
                    .append("] ")
                    .append(e.path)
                    .append(" - ")
                    .append(e.message)
                    .append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    fun write(
        r: OptimizeReport,
        path: java.nio.file.Path,
        format: String,
    ) {
        val fmt = format.lowercase()
        val content =
            when (fmt) {
                "csv" -> toCsv(r)
                else -> toJson(r)
            }
        val parent = path.parent
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent)
        }
        Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
    }
}
