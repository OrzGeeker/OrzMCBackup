package com.jokerhub.orzmc.world

/** Serializes [MergeReport] to text or into the existing [OptimizeReport] shape. */
object MergeReportIO {
    fun toOptimizeReport(r: MergeReport): OptimizeReport =
        OptimizeReport(
            processedChunks = r.patchSlots,
            removedChunks = r.baseSlots,
            errors = r.errors,
        )

    fun toText(r: MergeReport): String {
        val sb = StringBuilder()
        sb.append("Statistics: mergedRegions=")
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
                sb.append("[").append(e.kind).append("] ")
                    .append(e.path)
                    .append(" - ")
                    .append(e.message)
                    .append("\n")
            }
        }
        return sb.toString().trimEnd()
    }
}
