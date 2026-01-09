package com.jokerhub.orzmc.world

data class OptimizeError(
    val path: String,
    val kind: String,
    val message: String
)

data class OptimizeReport(
    val processedChunks: Long,
    val removedChunks: Long,
    val errors: List<OptimizeError>
)

object ReportIO {
    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun toJson(r: OptimizeReport): String {
        val sb = StringBuilder()
        sb.append("{\"processedChunks\":").append(r.processedChunks)
            .append(",\"removedChunks\":").append(r.removedChunks)
            .append(",\"errors\":[")
        r.errors.forEachIndexed { i, e ->
            if (i > 0) sb.append(",")
            sb.append("{\"path\":\"").append(esc(e.path)).append("\",")
                .append("\"kind\":\"").append(esc(e.kind)).append("\",")
                .append("\"message\":\"").append(esc(e.message)).append("\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun toCsv(r: OptimizeReport): String {
        val sb = StringBuilder()
        sb.append("processedChunks,removedChunks,errorsCount\n")
            .append(r.processedChunks).append(",").append(r.removedChunks).append(",").append(r.errors.size).append("\n")
        sb.append("path,kind,message\n")
        r.errors.forEach { e ->
            val path = e.path.replace("\"", "\"\"")
            val kind = e.kind.replace("\"", "\"\"")
            val message = e.message.replace("\"", "\"\"")
            sb.append("\"").append(path).append("\",")
                .append("\"").append(kind).append("\",")
                .append("\"").append(message).append("\"\n")
        }
        return sb.toString()
    }

    fun toText(r: OptimizeReport): String {
        val sb = StringBuilder()
        sb.append("处理统计：processed=").append(r.processedChunks)
            .append(" removed=").append(r.removedChunks)
            .append(" errors=").append(r.errors.size).append("\n")
        if (r.errors.isNotEmpty()) {
            sb.append("错误列表：\n")
            r.errors.forEach { e ->
                sb.append("[").append(e.kind).append("] ").append(e.path).append(" - ").append(e.message).append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    fun write(r: OptimizeReport, path: java.nio.file.Path, format: String) {
        val fmt = format.lowercase()
        val content = when (fmt) {
            "csv" -> toCsv(r)
            else -> toJson(r)
        }
        val parent = path.parent
        if (parent != null && !java.nio.file.Files.isDirectory(parent)) {
            java.nio.file.Files.createDirectories(parent)
        }
        java.nio.file.Files.writeString(path, content, java.nio.charset.StandardCharsets.UTF_8)
    }
}
