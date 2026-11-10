package io.github.cdsap.daemonitor.gradle

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal class ObservationReportSink(
    private val projectDirectory: Path,
) {
    fun writeIfPresent(authoritativeSummary: String?) {
        authoritativeSummary?.takeIf { it.isNotBlank() } ?: return
        val report = projectDirectory.resolve(REPORT_RELATIVE_PATH)
        Files.createDirectories(report.parent)
        Files.writeString(report, authoritativeSummary, StandardCharsets.UTF_8)
    }

    companion object {
        private val REPORT_RELATIVE_PATH = Path.of("build", "daemonitor", "observation-summary.json")
    }
}
