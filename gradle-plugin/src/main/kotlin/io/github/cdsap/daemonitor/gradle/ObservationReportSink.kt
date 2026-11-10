package io.github.cdsap.daemonitor.gradle

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal class ObservationReportSink(private val projectDirectory: Path) {
    fun writeIfPresent(summary: String?) {
        summary?.takeIf { it.isNotBlank() } ?: return
        val report = projectDirectory.resolve("build/daemonitor/observation-summary.json")
        Files.createDirectories(report.parent)
        Files.writeString(report, summary, StandardCharsets.UTF_8)
    }
}
