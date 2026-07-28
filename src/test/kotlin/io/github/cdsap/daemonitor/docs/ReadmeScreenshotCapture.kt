@file:OptIn(ExperimentalTestApi::class)

package io.github.cdsap.daemonitor.docs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.cdsap.daemonitor.BuildInfo
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.ui.common.AppScaffold
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import io.github.cdsap.daemonitor.ui.history.HistoryScreen
import io.github.cdsap.daemonitor.ui.history.HistoryUiState
import io.github.cdsap.daemonitor.ui.live.DetailState
import io.github.cdsap.daemonitor.ui.live.LiveMonitorScreen
import io.github.cdsap.daemonitor.ui.live.LiveSummary
import io.github.cdsap.daemonitor.ui.live.LiveUiState
import io.github.cdsap.daemonitor.ui.settings.SettingsScreen
import io.github.cdsap.daemonitor.ui.settings.SettingsUiState
import org.junit.jupiter.api.Tag
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Manual documentation renderer. Run `./gradlew captureReadmeScreenshots` after visible UI changes. */
@Tag("documentation")
class ReadmeScreenshotCapture {
    private val outputDirectory = Path.of("docs", "images")
    private val buildInfo = BuildInfo(version = "0.1.1", commit = "preview")

    @Test
    fun captureLive() = capture("live-monitor.png") {
        mainClock.autoAdvance = false
        val state = SampleUi.liveState()
        setContent {
            App(state, HistoryUiState())
        }
    }

    @Test
    fun captureHistory() = capture("build-history.png") {
        // The scaffold initially composes Live, whose uptime ticker is intentionally infinite.
        mainClock.autoAdvance = false
        setContent {
            App(LiveUiState(), SampleUi.historyState())
        }
        onNodeWithText("History").performClick()
        // History has no infinite animation, so let all tab and selection redraws settle.
        mainClock.autoAdvance = true
        onAllNodesWithText("checkout-service")[0].performClick()
        waitForIdle()
    }

    private fun capture(fileName: String, content: androidx.compose.ui.test.SkikoComposeUiTest.() -> Unit) {
        Files.createDirectories(outputDirectory)
        runSkikoComposeUiTest(Size(1180f, 760f), Density(1f)) {
            content()
            waitForIdle()
            val bitmap = onRoot().captureToImage().asSkiaBitmap()
            val bytes = Image.makeFromBitmap(bitmap)
                .encodeToData(EncodedImageFormat.PNG, 90)
                ?.bytes
                ?: error("Unable to encode $fileName")
            Files.write(outputDirectory.resolve(fileName), bytes)
        }
    }

    @androidx.compose.runtime.Composable
    private fun App(liveState: LiveUiState, historyState: HistoryUiState) {
        WatcherTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppScaffold(
                    liveContent = {
                        LiveMonitorScreen(liveState, onSelect = {}, onClearSelection = {})
                    },
                    historyContent = {
                        HistoryScreen(historyState, onProject = {}, onTimeRange = {})
                    },
                    settingsContent = {
                        SettingsScreen(SettingsUiState(retentionDays = 15), onRetentionDays = {})
                    },
                    buildInfo = buildInfo,
                )
            }
        }
    }
}

internal object SampleUi {
    private const val SAFE_ROOT = "/workspace/samples"

    fun liveState(): LiveUiState {
        val now = System.currentTimeMillis()
        val daemon = process(
            pid = 4821,
            type = ProcessType.GRADLE_DAEMON,
            project = "checkout-service",
            rss = 2340,
            cpu = 38.0,
            startedAt = now - 18 * 60 * 1_000,
            command = "java -Xmx4096m org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0",
        )
        val processes = listOf(
            daemon,
            process(4914, ProcessType.GRADLE_WRAPPER, "checkout-service", 612, 14.0, now - 4 * 60 * 1_000, "java org.gradle.wrapper.GradleWrapperMain test", automated = true),
            process(4930, ProcessType.TEST_WORKER, "checkout-service", 768, 71.0, now - 75 * 1_000, "java -Xmx1024m GradleWorkerMain 'Test Executor 2'"),
            process(5077, ProcessType.KOTLIN_DAEMON, "design-system", 544, 9.0, now - 11 * 60 * 1_000, "java -Xmx1536m org.jetbrains.kotlin.daemon.KotlinCompileDaemon"),
        )
        return LiveUiState(
            processes = processes,
            summary = LiveSummary(4, processes.sumOf { it.rssMemoryMb }, daemon.pid, 2),
            detail = DetailState.Selected(daemon),
            tail = listOf(
                "> Task :checkout:compileKotlin",
                "> Task :checkout:test",
                "CheckoutRepositoryTest > creates order successfully PASSED",
                "BUILD SUCCESSFUL in 1m 14s",
            ),
            isLoading = false,
            isEmpty = false,
        )
    }

    fun historyState(): HistoryUiState {
        val builds = listOf(
            build("build-1042", "checkout-service", 1_782_904_930_000, 74.2, 2380, 86.0, FinalStatus.SUCCESS, Source.IDE, "Codex", "OpenAI", "> Task :checkout:test\n42 tests completed, 0 failed\nBUILD SUCCESSFUL"),
            build("build-1041", "design-system", 1_782_904_280_000, 31.8, 1210, 64.0, FinalStatus.FAILED, Source.TERMINAL, null, null, "> Task :tokens:generate\nTokenValidationTest FAILED\nBUILD FAILED"),
            build("build-1040", "mobile-app", 1_782_903_510_000, 128.5, 3150, 92.0, FinalStatus.INTERRUPTED, Source.IDE, "Claude Code", "Anthropic", null),
            build("build-1039", "checkout-service", 1_782_902_820_000, 18.4, 980, 43.0, FinalStatus.COMPLETED_NO_OUTCOME, Source.TERMINAL, "Cursor", "Anysphere", null),
        )
        return HistoryUiState(
            builds = builds,
            projects = builds.mapNotNull { it.projectPath }.distinct(),
            isEmptyResult = false,
        )
    }

    private fun process(
        pid: Long,
        type: ProcessType,
        project: String,
        rss: Long,
        cpu: Double,
        startedAt: Long,
        command: String,
        automated: Boolean = false,
    ) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = type,
        commandLine = command,
        workingDirectory = "$SAFE_ROOT/$project",
        projectPath = "$SAFE_ROOT/$project",
        cpuPercent = cpu,
        rssMemoryMb = rss,
        maxHeapMb = if (type == ProcessType.GRADLE_DAEMON) 4096 else 1536,
        minHeapMb = 256,
        gc = "G1",
        startTimeMs = startedAt,
        status = "RUNNING",
        automated = automated,
    )

    private fun build(
        id: String,
        project: String,
        startedAt: Long,
        duration: Double,
        peakMemory: Long,
        peakCpu: Double,
        status: FinalStatus,
        source: Source,
        agent: String?,
        provider: String?,
        log: String?,
    ) = Build(
        buildId = id,
        daemonPid = 4821,
        daemonIdentity = "sample-daemon",
        commandLine = "./gradlew test",
        workingDirectory = "$SAFE_ROOT/$project",
        projectPath = "$SAFE_ROOT/$project",
        startTimeMs = startedAt,
        endTimeMs = startedAt + (duration * 1_000).toLong(),
        durationSeconds = duration,
        peakMemoryMb = peakMemory,
        avgMemoryMb = peakMemory - 240,
        peakCpuPercent = peakCpu,
        inferredSource = source,
        finalStatus = status,
        logSnippet = log,
        agent = agent,
        agentProvider = provider,
    )
}
