package com.jokerhub.orzmc.world

import java.nio.file.Path

interface ReportSink {
    fun write(report: OptimizeReport)
}

class FileReportSink(
    private val path: Path,
    private val format: String = "json"
) : ReportSink {
    override fun write(report: OptimizeReport) {
        ReportIO.write(report, path, format)
    }
}

class NoopReportSink : ReportSink {
    override fun write(report: OptimizeReport) {}
}
