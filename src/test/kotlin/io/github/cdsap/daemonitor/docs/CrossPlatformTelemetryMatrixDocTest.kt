package io.github.cdsap.daemonitor.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class CrossPlatformTelemetryMatrixDocTest {
    private val documentPath = Path.of("cored/docs/cross-platform-telemetry-matrix.md")
    private val document = Files.readString(documentPath)
    private val workflow = Files.readString(Path.of(".github/workflows/ci.yml"))

    @Test
    fun `matrix documents every required environment and gate`() {
        assertTrue(Files.isRegularFile(documentPath), "$documentPath should be checked in")
        listOf(
            "TEL-01",
            "TEL-02",
            "TEL-03",
            "TEL-04",
            "TEL-05",
            "TEL-06",
            "TEL-07",
            "macOS Intel",
            "macOS Apple Silicon",
            "Ubuntu x64",
            "Ubuntu ARM64",
            "Linux cgroup v2",
            "Windows x64",
            "Windows ARM64",
            "JDK 17 / 21 / 25",
            "baseline",
            "manual/integration checklist",
            "unsupported",
        ).forEach { required ->
            assertTrue(document.contains(required), "$documentPath should include: $required")
        }
    }

    @Test
    fun `ci references hosted runners and jdk compatibility matrix`() {
        listOf("macos-latest", "ubuntu-24.04-arm", "windows-latest").forEach { runner ->
            assertTrue(workflow.contains(runner), "CI should reference $runner")
        }
        assertTrue(workflow.contains("jdk: ['17', '21', '25']"), "CI should test JDK 17, 21, and 25")
    }
}
