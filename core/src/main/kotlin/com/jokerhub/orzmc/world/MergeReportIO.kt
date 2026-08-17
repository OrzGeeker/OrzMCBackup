package com.jokerhub.orzmc.world

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Serializes [MergeReport] to JSON, CSV, or plain text formats. */
object MergeReportIO {
    /**
     * Maps merge counters into the existing [OptimizeReport] shape for consumers that
     * expect the optimizer's report model. Note the mapping is lossy: `processedChunks`
     * reports [MergeReport.patchSlots] and `removedChunks` reports [MergeReport.baseSlots]
     * (a slot count, not data removed).
     */
    fun toOptimizeReport(r: MergeReport): OptimizeReport =
        OptimizeReport(
            processedChunks = r.patchSlots,
            removedChunks = r.baseSlots,
            errors = r.errors,
        )

    fun toJson(r: MergeReport): String {
        val sb = StringBuilder()
        sb
            .append("{\"mergedRegions\":")
            .append(r.mergedRegions)
            .append(",\"copiedFiles\":")
            .append(r.copiedFiles)
            .append(",\"patchSlots\":")
            .append(r.patchSlots)
            .append(",\"baseSlots\":")
            .append(r.baseSlots)
            .append(",\"linkedEntities\":")
            .append(r.linkedEntities)
            .append(",\"linkedPoi\":")
            .append(r.linkedPoi)
            .append(",\"overlayFiles\":")
            .append(r.overlayFiles)
            .append(",\"errors\":[")
        r.errors.forEachIndexed { i, e ->
            if (i > 0) sb.append(",")
            sb
                .append("{\"path\":\"")
                .append(ReportIO.esc(e.path))
                .append("\",\"kind\":\"")
                .append(ReportIO.esc(e.kind))
                .append("\",\"message\":\"")
                .append(ReportIO.esc(e.message))
                .append("\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun toCsv(r: MergeReport): String {
        val sb = StringBuilder()
        sb
            .append("mergedRegions,copiedFiles,patchSlots,baseSlots,linkedEntities,linkedPoi,")
            .append("overlayFiles,errorsCount\n")
            .append(r.mergedRegions)
            .append(",")
            .append(r.copiedFiles)
            .append(",")
            .append(r.patchSlots)
            .append(",")
            .append(r.baseSlots)
            .append(",")
            .append(r.linkedEntities)
            .append(",")
            .append(r.linkedPoi)
            .append(",")
            .append(r.overlayFiles)
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

    fun toText(r: MergeReport): String {
        val sb = StringBuilder()
        sb
            .append("Statistics: mergedRegions=")
            .append(r.mergedRegions)
            .append(" patchSlots=")
            .append(r.patchSlots)
            .append(" baseSlots=")
            .append(r.baseSlots)
            .append(" linkedEntities=")
            .append(r.linkedEntities)
            .append(" linkedPoi=")
            .append(r.linkedPoi)
            .append(" copiedFiles=")
            .append(r.copiedFiles)
            .append(" overlayFiles=")
            .append(r.overlayFiles)
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
        r: MergeReport,
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
