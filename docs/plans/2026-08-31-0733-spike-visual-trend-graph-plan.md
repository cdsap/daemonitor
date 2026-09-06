---
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-plan-bootstrap
created: 2026-08-31
title: Visual Trend Graph Spike
---

# Visual Trend Graph Spike

## Goal Capsule

Make the Visual tab feel like a process memory profiler instead of a static snapshot. The tab should show smooth, readable RSS and configured heap-limit trends over selectable historical windows, starting with short retained process history such as 15 minutes and 1 hour.

## Product Contract

### Requirements

- **R1 · Smooth trend graph.** Replace the current point-to-point visual treatment with a smoother trend rendering for RSS and configured heap-limit series.
- **R2 · Time-window control.** Add a visible range control for recent process history. Initial ranges: `Live`, `15 min`, `1 hour`, and `All retained`.
- **R3 · Persisted history source.** Ranges longer than the current live buffer must load from persisted `process_samples`, not from tab-local state.
- **R4 · Process selection remains first-class.** Selecting a process/series should keep the selected process visually emphasized and keep legend toggles useful when many processes exist.
- **R5 · Honest heap semantics.** Continue labeling heap as configured heap limit / `-Xmx`, not live heap occupancy, unless a future attach-based collector is explicitly added.
- **R6 · Good empty/degraded states.** If the selected range has no persisted samples, show an explicit empty trend state; if a process has no heap limit, omit or mute its heap line rather than drawing a false zero.

### Non-Goals

- Do not attach to JVMs or collect live heap occupancy in this spike.
- Do not add alerting/anomaly detection yet.
- Do not replace the Live table or Build history views.
- Do not add remote telemetry.

### Acceptance Examples

- **AE1.** With four live Gradle-related processes and 90 retained samples, `Live` shows a smooth total RSS trend plus selectable per-process RSS/heap series.
- **AE2.** Switching to `1 hour` reloads the chart from SQLite and preserves the selected process when that PID appears in the range.
- **AE3.** A process with `max_heap_mb = NULL` shows RSS but no heap-limit line.
- **AE4.** A range with no samples renders a compact "No samples in this range" state rather than an empty chart.

## Current Repo Grounding

- `src/main/kotlin/io/github/cdsap/daemonitor/ui/common/AppScaffold.kt` already exposes a `Visual` tab.
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/ProcessVisualScreen.kt` renders `OverallRssTimelineChart` and refreshes tab panel state every 30 seconds.
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/LiveViewModel.kt` keeps an in-memory `rssTimeline` with `DEFAULT_TIMELINE_CAPACITY = 90`.
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/charts/OverallRssTimelineChart.kt` draws each series with a Canvas `Path` built by straight `lineTo` segments.
- `src/main/sqldelight/io/github/cdsap/daemonitor/store/db/Watcher.sq` already persists `process_samples` with `timestamp`, `pid`, `process_type`, `project_path`, `cpu_percent`, `rss_memory_mb`, and `max_heap_mb`.
- `src/main/kotlin/io/github/cdsap/daemonitor/store/WatcherDatabase.kt` exposes recent samples and PID-specific samples, but not range/group queries shaped for the Visual tab.

## Technical Design

### Decision 1: Treat `Live` and historic ranges as different data sources

`Live` should continue using `LiveUiState.rssTimeline` for fast in-memory updates. Historic ranges should query `process_samples` from persistence through an application-level repository method.

This avoids making the live ViewModel own retention-scale history and keeps the UI honest when a tab is opened after the app has already been running.

### Decision 2: Add a Visual-specific state holder

Add a `VisualViewModel` or equivalent application state holder rather than overloading `LiveViewModel`.

Responsibilities:

- Track selected range.
- Track visible/selected series.
- Load range samples from the process-sample repository.
- Merge current live process metadata into historic samples for labels when available.
- Expose loading, empty, and error states.

### Decision 3: Downsample for smoothness and stability

Add a model-layer downsampling step before drawing:

- `Live`: no downsampling unless point count exceeds the plot width.
- `15 min`: bucket by about 10 seconds.
- `1 hour`: bucket by about 30 seconds.
- `All retained`: bucket to a target max of roughly 240 points.

For each bucket, preserve max RSS, average CPU if shown later, and latest heap limit.

### Decision 4: Draw a smoothed path without hiding spikes

Use monotone cubic interpolation or Catmull-Rom-to-Bezier conversion with clamped control points. Smooth the visual path, but keep hover points and bucket values exact. Do not use a moving average as the default because it can hide real peak RSS spikes.

### Decision 5: Keep controls compact and profiler-like

The Visual tab should have:

- A range segmented control near the chart title.
- Optional refresh/status text only when useful: last sample time, loading, or no samples.
- Legend chips that can be toggled, but with process rows collapsed if there are too many series.
- A selected-process summary strip beside or below the chart with current RSS, peak RSS in range, heap limit, and sample count.

## Implementation Units

### U1 · Range Queries

Files:

- `src/main/sqldelight/io/github/cdsap/daemonitor/store/db/Watcher.sq`
- `src/main/kotlin/io/github/cdsap/daemonitor/store/WatcherDatabase.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/application/ProcessSampleRepository.kt`
- Tests: `src/test/kotlin/io/github/cdsap/daemonitor/store/WatcherDatabaseTest.kt`

Plan:

- Add a query for process samples between `fromMs` and `toMs`, ordered ascending by timestamp.
- Return full sample fields needed for labels, RSS, heap, and process type.
- Keep existing retention purge behavior unchanged.

Test Scenarios:

- Samples outside the selected range are excluded.
- Results are timestamp ascending.
- Null `max_heap_mb` stays null.
- Multiple PIDs in one range are preserved.

### U2 · Visual Range State

Files:

- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/VisualViewModel.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/VisualUiState.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/Main.kt`
- Tests: `src/test/kotlin/io/github/cdsap/daemonitor/ui/live/VisualViewModelTest.kt`

Plan:

- Define `VisualRange` values: `LIVE`, `FIFTEEN_MINUTES`, `ONE_HOUR`, `ALL_RETAINED`.
- Use `LiveUiState.rssTimeline` for `LIVE`.
- Query persisted samples for the other ranges.
- Preserve selection when possible; otherwise select the highest RSS process in the visible range.

Test Scenarios:

- Selecting `1 hour` calls the repository with the expected time window.
- Switching back to `Live` uses in-memory samples.
- Selection survives range changes when the PID exists.
- Empty repository result produces an empty state.

### U3 · Chart Model Downsampling

Files:

- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/VisualChartModel.kt`
- Tests: `src/test/kotlin/io/github/cdsap/daemonitor/ui/live/VisualChartModelTest.kt`

Plan:

- Convert persisted process samples into `RssTimelineChartData`.
- Add bucketed downsampling by range.
- Include total RSS per bucket and per-PID RSS/heap series.
- Preserve peak RSS in each bucket to keep spikes visible.

Test Scenarios:

- One bucket with multiple points keeps max RSS.
- Heap limit uses the latest non-null value in the bucket.
- Total RSS aggregates PIDs by timestamp bucket.
- Downsampling never returns more than the target point count for long ranges.

### U4 · Smooth Chart Rendering

Files:

- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/charts/OverallRssTimelineChart.kt`
- Tests: `src/test/kotlin/io/github/cdsap/daemonitor/ui/live/VisualChartModelTest.kt`
- Optional screenshot: `src/test/kotlin/io/github/cdsap/daemonitor/docs/ReadmeScreenshotCapture.kt`

Plan:

- Replace straight-line path generation with a smooth path helper.
- Keep exact point positions for hover/crosshair.
- Do not smooth dashed heap lines differently from RSS except for stroke style.
- Add model tests for generated path control behavior if the helper is pure enough; otherwise keep rendering covered by UI/screenshot tests.

Test Scenarios:

- Single-point and two-point series render without path errors.
- Multi-point series produces a continuous path.
- Hover still resolves the nearest exact point.

### U5 · Visual Tab Controls and Polish

Files:

- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/ProcessVisualScreen.kt`
- `src/test/kotlin/io/github/cdsap/daemonitor/ui/live/ProcessVisualScreenUiTest.kt`
- `src/test/kotlin/io/github/cdsap/daemonitor/docs/ReadmeScreenshotCapture.kt`

Plan:

- Add the range segmented control.
- Add selected-process summary metrics.
- Add no-samples and loading states.
- Update screenshot fixtures so the Visual tab demonstrates a real historic range.

Test Scenarios:

- The range control renders all expected options.
- Clicking `1 hour` changes chart title/status and uses historic state.
- Empty historic samples show "No samples in this range".
- Screenshot rendering includes the Visual tab without clipped labels.

## Risks

- Long retained ranges can create too many series if many short-lived test workers exist. Mitigate with top-N visible series plus total RSS by default.
- PID reuse can confuse historic labels. Mitigate by including process type, project path, and start-time-like grouping when available; document that PID-only identity is best-effort.
- Smooth curves can visually overshoot. Use monotone/clamped interpolation and keep exact hover values.
- `max_heap_mb` is configured allocation limit, not live heap usage. Keep label text explicit.

## Validation Plan

- Run focused model/database/UI tests for the Visual tab.
- Run `./gradlew test --no-daemon --stacktrace`.
- Regenerate README screenshots if the visible navigation or chart screenshot changes.
- Manually run the app and verify `Live`, `15 min`, and `1 hour` controls against a real Gradle build workload.

