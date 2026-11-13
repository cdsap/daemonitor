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
import io.github.cdsap.daemonitor.domain.model.BuildTool
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.ui.common.AppScaffold
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import io.github.cdsap.daemonitor.ui.history.HistoryScreen
import io.github.cdsap.daemonitor.ui.history.HistoryUiState
import io.github.cdsap.daemonitor.ui.live.DetailState
import io.github.cdsap.daemonitor.ui.live.LiveMonitorScreen
import io.github.cdsap.daemonitor.ui.live.LiveSummary
import io.github.cdsap.daemonitor.ui.live.LogTailState
import io.github.cdsap.daemonitor.ui.live.LiveUiState
import io.github.cdsap.daemonitor.ui.live.ProcessMetricSample
import io.github.cdsap.daemonitor.ui.live.ProcessVisualScreen
import io.github.cdsap.daemonitor.ui.live.RecentBuildsState
import io.github.cdsap.daemonitor.ui.live.RssTimelineSample
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
    private val buildInfo = BuildInfo(version = "1.3.0", commit = "preview")

    @Test
    fun captureLive() = capture("live-monitor.png") {
        mainClock.autoAdvance = false
        val state = SampleUi.liveState()
        setContent {
            App(state, HistoryUiState())
        }
    }

    @Test
    fun captureVisual() = capture("process-visual.png") {
        // The scaffold initially composes Live, whose uptime ticker is intentionally infinite.
        mainClock.autoAdvance = false
        setContent {
            App(SampleUi.liveState(), HistoryUiState())
        }
        onNodeWithText("Visual").performClick()
        waitForIdle()
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
        onAllNodesWithText("maps-service")[0].performClick()
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
                    visualContent = {
                        ProcessVisualScreen(liveState)
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
            pid = 6120,
            type = ProcessType.GRADLE_DAEMON,
            project = "checkout-service",
            rss = 2680,
            heapUsedMb = 1480,
            cpu = 52.0,
            startedAt = now - 18 * 60 * 1_000,
            command = "java -Xmx4096m org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0",
        )
        val bazelServer = process(
            pid = 7102,
            type = ProcessType.BAZEL_SERVER,
            project = "maps-service",
            rss = 3120,
            heapUsedMb = 1680,
            cpu = 41.0,
            startedAt = now - 26 * 60 * 1_000,
            command = "java -Xmx4096m -jar /workspace/samples/bazel/A-server.jar --product_name=Bazel --workspace_directory=/workspace/samples/maps-service",
        )
        val processes = listOf(
            daemon,
            process(6204, ProcessType.GRADLE_WRAPPER, "checkout-service", 540, 190, 11.0, now - 4 * 60 * 1_000, "java org.gradle.wrapper.GradleWrapperMain test", automated = true),
            process(6218, ProcessType.GRADLE_WORKER, "checkout-service", 920, 480, 64.0, now - 75 * 1_000, "java -Xmx1024m GradleWorkerMain 'Test Executor 2'", parentPid = 6120),
            process(6301, ProcessType.KOTLIN_DAEMON, "design-system", 710, 360, 18.0, now - 11 * 60 * 1_000, "java -Xmx1536m org.jetbrains.kotlin.daemon.KotlinCompileDaemon"),
            bazelServer,
            process(
                7120,
                ProcessType.BAZEL_WORKER,
                "maps-service",
                880,
                420,
                37.0,
                now - 3 * 60 * 1_000,
                "java -Xmx1536m com.google.devtools.build.buildjar.bazel.JavaBuilder --persistent_worker",
                parentPid = 7102,
            ),
            process(
                7134,
                ProcessType.BAZEL_TEST,
                "maps-service",
                640,
                280,
                72.0,
                now - 90 * 1_000,
                "bash tools/test/test-setup.sh MapRoutingTest",
                parentPid = 7102,
            ),
        )
        val endMs = 1_700_000_060_000L
        fun sample(offsetMs: Long, rssByPid: Map<Long, Long>, cpuByPid: Map<Long, Long>) = RssTimelineSample(
            atMs = endMs + offsetMs,
            totalRssMb = rssByPid.values.sum(),
            byPid = rssByPid,
            heapLimitByPid = processes.mapNotNull { process ->
                process.maxHeapMb?.let { heap -> process.pid to heap }
            }.toMap(),
            heapUsedByPid = processes.mapNotNull { process ->
                process.liveHeap?.takeIf { it.available }?.usedMb?.let { used -> process.pid to used }
            }.toMap(),
            extendedByPid = cpuByPid.mapValues { (_, cpu) -> ProcessMetricSample(cpuPercent = cpu) },
        )
        val tail = listOf(
            "> Task :checkout:compileKotlin",
            "> Task :checkout:test",
            "CheckoutRepositoryTest > creates order successfully PASSED",
            "BUILD SUCCESSFUL in 1m 14s",
        )
        return LiveUiState(
            processes = processes,
            summary = LiveSummary(
                processes.size,
                processes.sumOf { it.rssMemoryMb },
                processes.maxByOrNull { it.rssMemoryMb }?.pid,
                processes.mapNotNull { it.projectPath }.distinct().size,
            ),
            detail = DetailState.Selected(daemon),
            recentBuilds = RecentBuildsState.Loaded(
                listOf(
                    build(
                        "build-1108",
                        "checkout-service",
                        1_782_904_930_000,
                        74.2,
                        2380,
                        86.0,
                        FinalStatus.SUCCESS,
                        Source.IDE,
                        "Codex",
                        "OpenAI",
                        "> Task :checkout:test\n42 tests completed, 0 failed\nBUILD SUCCESSFUL",
                    ),
                ),
            ),
            tail = tail,
            tailState = LogTailState.Available(tail),
            isLoading = false,
            isEmpty = false,
            rssTimeline = listOf(
                sample(
                    -24_000,
                    mapOf(6120L to 2210L, 6204L to 480L, 6218L to 740L, 6301L to 620L, 7102L to 2780L, 7120L to 710L, 7134L to 520L),
                    mapOf(6120L to 44L, 6204L to 8L, 6218L to 58L, 6301L to 14L, 7102L to 36L, 7120L to 28L, 7134L to 55L),
                ),
                sample(
                    -18_000,
                    mapOf(6120L to 2390L, 6204L to 510L, 6218L to 820L, 6301L to 660L, 7102L to 2910L, 7120L to 760L, 7134L to 560L),
                    mapOf(6120L to 48L, 6204L to 9L, 6218L to 61L, 6301L to 16L, 7102L to 38L, 7120L to 31L, 7134L to 61L),
                ),
                sample(
                    -12_000,
                    mapOf(6120L to 2510L, 6204L to 525L, 6218L to 880L, 6301L to 685L, 7102L to 3010L, 7120L to 810L, 7134L to 590L),
                    mapOf(6120L to 50L, 6204L to 10L, 6218L to 63L, 6301L to 17L, 7102L to 39L, 7120L to 34L, 7134L to 66L),
                ),
                sample(
                    -6_000,
                    mapOf(6120L to 2610L, 6204L to 535L, 6218L to 905L, 6301L to 700L, 7102L to 3080L, 7120L to 850L, 7134L to 620L),
                    mapOf(6120L to 51L, 6204L to 10L, 6218L to 64L, 6301L to 18L, 7102L to 40L, 7120L to 36L, 7134L to 70L),
                ),
                sample(
                    0,
                    processes.associate { it.pid to it.rssMemoryMb },
                    processes.associate { it.pid to (it.cpuPercent?.toLong() ?: 0L) },
                ),
            ),
        )
    }

    fun historyState(): HistoryUiState {
        val builds = listOf(
            build(
                "bep-2201",
                "maps-service",
                1_782_905_100_000,
                96.4,
                2840,
                78.0,
                FinalStatus.SUCCESS,
                Source.BEP,
                null,
                null,
                "INFO: Build completed successfully, 214 actions",
                tool = BuildTool.BAZEL,
                daemonPid = 7102,
                command = "bazel build //maps/...",
            ),
            build("build-1108", "checkout-service", 1_782_904_930_000, 74.2, 2380, 86.0, FinalStatus.SUCCESS, Source.IDE, "Codex", "OpenAI", "> Task :checkout:test\n42 tests completed, 0 failed\nBUILD SUCCESSFUL"),
            build(
                "bep-2198",
                "maps-service",
                1_782_904_610_000,
                42.1,
                1960,
                61.0,
                FinalStatus.FAILED,
                Source.BEP,
                null,
                null,
                "ERROR: //maps/routing:test failed",
                tool = BuildTool.BAZEL,
                daemonPid = 7102,
                command = "bazel test //maps/routing:test",
            ),
            build("build-1107", "design-system", 1_782_904_280_000, 31.8, 1210, 64.0, FinalStatus.FAILED, Source.TERMINAL, null, null, "> Task :tokens:generate\nTokenValidationTest FAILED\nBUILD FAILED"),
            build("build-1106", "mobile-app", 1_782_903_510_000, 128.5, 3150, 92.0, FinalStatus.INTERRUPTED, Source.IDE, "Claude Code", "Anthropic", null),
            build("build-1105", "checkout-service", 1_782_902_820_000, 18.4, 980, 43.0, FinalStatus.COMPLETED_NO_OUTCOME, Source.TERMINAL, "Cursor", "Anysphere", null),
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
        heapUsedMb: Long,
        cpu: Double,
        startedAt: Long,
        command: String,
        automated: Boolean = false,
        parentPid: Long = 1,
    ): GradleProcess {
        val maxHeap = when (type) {
            ProcessType.GRADLE_DAEMON, ProcessType.BAZEL_SERVER -> 4096L
            ProcessType.GRADLE_WORKER, ProcessType.BAZEL_WORKER -> 1024L
            ProcessType.BAZEL_TEST -> 768L
            else -> 1536L
        }
        return GradleProcess(
            pid = pid,
            parentPid = parentPid,
            type = type,
            commandLine = command,
            workingDirectory = "$SAFE_ROOT/$project",
            projectPath = "$SAFE_ROOT/$project",
            cpuPercent = cpu,
            rssMemoryMb = rss,
            maxHeapMb = maxHeap,
            minHeapMb = 256,
            gc = "G1",
            startTimeMs = startedAt,
            status = "RUNNING",
            automated = automated,
            liveHeap = LiveJvmHeap(
                usedMb = heapUsedMb,
                committedMb = (heapUsedMb * 1.2).toLong(),
                maxMb = maxHeap,
                sampledAtMs = startedAt,
                available = true,
            ),
        )
    }

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
        tool: BuildTool = BuildTool.GRADLE,
        daemonPid: Long = 6120,
        command: String = "./gradlew test",
    ) = Build(
        buildId = id,
        daemonPid = daemonPid,
        daemonIdentity = if (tool == BuildTool.GRADLE) "sample-daemon" else "sample-bazel-server",
        commandLine = command,
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
        tool = tool,
    )
}
