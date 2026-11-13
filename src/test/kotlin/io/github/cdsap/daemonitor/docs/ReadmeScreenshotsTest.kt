package io.github.cdsap.daemonitor.docs

import io.github.cdsap.daemonitor.domain.model.BuildTool
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadmeScreenshotsTest {
    private val readme = Files.readString(Path.of("README.md"))

    @Test
    fun `README screenshots are native-size PNGs with meaningful alt text`() {
        val screenshots = mapOf(
            "docs/images/live-monitor.png" to "Daemonitor Live monitor showing Gradle and Bazel processes, metrics, status badges, process details, MCP status, and the headless toolbar action",
            "docs/images/process-visual.png" to "Daemonitor Visual tab showing Memory/CPU/I/O metric modes, time-window controls, and per-process RSS with live heap and configured heap (-Xmx) timelines for Gradle and Bazel",
            "docs/images/build-history.png" to "Daemonitor build history showing Gradle and Bazel rows with status and source tags, BEP source, agent attribution, metrics, and build details",
        )

        screenshots.forEach { (relativePath, altText) ->
            val path = Path.of(relativePath)
            assertTrue(Files.isRegularFile(path), "$relativePath should be checked in")
            val image: BufferedImage = assertNotNull(ImageIO.read(path.toFile()), "$relativePath should be a readable image")
            assertEquals(1180, image.width)
            assertEquals(760, image.height)
            assertTrue(readme.contains("![$altText]($relativePath)"), "$relativePath should be embedded with meaningful alt text")
        }

        val tui = Path.of("docs/images/cli-monitor.png")
        assertTrue(Files.isRegularFile(tui), "docs/images/cli-monitor.png should be checked in")
        val tuiImage: BufferedImage = assertNotNull(ImageIO.read(tui.toFile()), "TUI screenshot should be readable")
        assertEquals(860, tuiImage.width)
        assertEquals(466, tuiImage.height)
        assertTrue(
            readme.contains(
                "![Daemonitor CLI process details for a Bazel server showing OS activity, JVM memory pools, native memory, and recent BEP builds](docs/images/cli-monitor.png)",
            ),
        )
    }

    @Test
    fun `sample screenshot data uses only synthetic workspace paths`() {
        val states = listOf(SampleUi.liveState(), SampleUi.historyState())
        val renderedData = states.toString()
        assertTrue(renderedData.contains("/workspace/samples/"))
        assertTrue("/Users/" !in renderedData)
        assertTrue("/home/" !in renderedData)
        assertTrue("token=" !in renderedData.lowercase())
        assertTrue("password=" !in renderedData.lowercase())
    }

    @Test
    fun `sample live state includes Bazel server test and worker rows`() {
        val types = SampleUi.liveState().processes.map { it.type }.toSet()
        assertTrue(ProcessType.BAZEL_SERVER in types)
        assertTrue(ProcessType.BAZEL_TEST in types)
        assertTrue(ProcessType.BAZEL_WORKER in types)
        assertTrue(ProcessType.GRADLE_DAEMON in types)
    }

    @Test
    fun `sample history includes opportunistic Bazel BEP rows`() {
        val bazel = SampleUi.historyState().builds.filter { it.tool == BuildTool.BAZEL }
        assertTrue(bazel.isNotEmpty())
        assertTrue(bazel.all { it.inferredSource == Source.BEP })
    }

    @Test
    fun `sample timeline distinguishes live heap used from configured heap limit`() {
        val state = SampleUi.liveState()
        val sample = state.rssTimeline.last()

        assertEquals(1480L, sample.heapUsedByPid[6120L])
        assertEquals(4096L, sample.heapLimitByPid[6120L])
        assertEquals(190L, sample.heapUsedByPid[6204L])
        assertEquals(480L, sample.heapUsedByPid[6218L])
        assertEquals(360L, sample.heapUsedByPid[6301L])
        assertEquals(1680L, sample.heapUsedByPid[7102L])
        assertEquals(4096L, sample.heapLimitByPid[7102L])

        state.processes.forEach { process ->
            val used = sample.heapUsedByPid[process.pid]
            val limit = sample.heapLimitByPid[process.pid]
            assertNotNull(used, "pid ${process.pid} should contribute live heap used")
            assertNotNull(limit, "pid ${process.pid} should contribute heap limit")
            assertTrue(used != limit, "pid ${process.pid} live heap must stay distinct from -Xmx")
            assertEquals(process.liveHeap?.usedMb, used)
            assertEquals(process.maxHeapMb, limit)
        }
    }
}
