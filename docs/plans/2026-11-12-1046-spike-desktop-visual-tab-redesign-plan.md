---
title: Desktop Visual tab redesign + multi-metric surfaces
created: 2026-11-12
status: answered
execution: knowledge-work
surface: Compose Desktop Visual tab
issue: https://github.com/cdsap/daemonitor/issues/490
---

# Spike: Desktop Visual tab redesign + multi-metric surfaces

## Decision

Choose **layout A: one focus chart, a metric-family switcher, and a compact process
selection strip**.

Visual has one job: compare resource trends across active processes, with one process
clearly in focus. Live remains the current-process inventory, the Live inspector owns
attributes and diagnostics, and History remains the build browser. Visual must not
become another detail dump or a grid of unrelated dashboard cards.

Keep V1–V5, with the scope trims recorded below. Defer V6 as not planned because
layout A does not need small multiples or per-process sparklines.

This spike makes no UI or data-path changes.

## Repository evidence

- `ProcessVisualScreen` currently gives equal first-viewport weight to four stat tiles,
  then renders one `OverallRssTimelineChart`.
- `OverallRssTimelineChart` owns a horizontally scrolling chip for every series. It can
  toggle all metrics onto one canvas and falls back to the axis label `value` as soon
  as non-memory metrics are mixed.
- `VisualChartModel` creates RSS, heap-used, heap-limit, threads, I/O, page-fault,
  context-switch, open-file, metaspace, and GC series. Extended series are hidden with
  `defaultVisible = false`, so their presence in the model does not make them usable.
- `LiveViewModel` already samples most extended process metrics. Its live buffer holds
  90 poll samples; at the default two-second poll interval that is nominally about
  three minutes. The Visual panel currently takes a new snapshot every 30 seconds.
- CPU is present on process snapshots and in `process_samples`, but not in
  `RssTimelineSample` / `ProcessMetricSample`.
- `process_samples` persists the fields needed for longer windows. The repository has
  recent- and PID-oriented reads, but no Visual-shaped all-process range query.
- Under PreferGo with a shared database, cored owns sample writes. A retained-window
  implementation therefore needs an ownership-safe read source; the Compose screen
  must not improvise a second persistence path.
- The August trend spike established the useful `Live`, `15 min`, and `1 hour` ranges,
  explicit no-sample states, and persisted history as the source for longer ranges.

## Answers to the seven spike questions

### 1. What is Visual's one job?

**Show how resource use changes across active processes over a selected recent time
window, while keeping one process easy to follow.**

The surface boundaries are:

| Surface | Owns | Does not own |
| --- | --- | --- |
| Live table | Current process inventory, status, sorting, selection | Trend analysis |
| Live inspector (#486) | Selected-process attributes, diagnostics, logs, builds | Cross-process charts |
| Visual | Recent resource trends and process comparison | Attribute dump, alerts, host dashboard |
| History | Searchable completed-build records and build detail | Live process telemetry |

Visual may accept a selection handed off from Live, but it does not duplicate the
inspector.

### 2. Which layout wins?

**A wins: focus chart + metric mode + process strip.**

```text
Process visualizer                       Live | 15m | 1h
[Memory] [CPU] [I/O] [GC] [Threads]

selected: Gradle daemon · project · PID 1234
RSS 640 MB · heap 310 / 1024 MB · current/peak summary

┌──────────────── one primary chart ────────────────────┐
│ selected process emphasized; bounded context series   │
└────────────────────────────────────────────────────────┘

[Gradle daemon 1234] [Kotlin daemon 1288] [worker 1310]
```

Why not B: a permanent secondary chart competes for height before there is evidence
that two simultaneous questions are common. Why not C: sparklines repeat the same
trend at too little scale and make process cards the dominant visual hierarchy. Both
can be reconsidered from usage evidence, but neither belongs in V1.

The current four-tile row is removed from the primary hierarchy. A single compact
selected-process summary is useful because it explains the focused chart; it is not a
replacement tile dashboard.

### 3. How do other metrics enter without line-soup?

The switcher selects one **metric family**, not arbitrary individual series. A family
owns one unit and one Y-axis. There is no implicit dual axis and no catch-all "show
everything" mode.

| Mode | Default chart contents | Axis / transformation |
| --- | --- | --- |
| Memory | Total RSS, selected RSS, selected heap used and `-Xmx`; selected metaspace when available | MB |
| CPU | Selected CPU plus a bounded set of comparison processes | Percent; clamp/label according to the collector's existing CPU contract |
| I/O | Selected read and write activity | Bytes/second derived from counter deltas, never raw cumulative bytes |
| GC | Selected young/old collection activity | Events/second from count deltas; prefer pause-time rate when V5 supplies time fields |
| Threads | Selected thread count plus bounded process comparison | Count |

Heap limit is a configured limit, not occupancy, and remains visually distinct from
heap used. Missing samples create gaps or an unavailable state; they never become
zero. Counter resets produce a gap for that interval rather than a negative rate.

The selected process gets the strongest stroke. Other processes are muted and limited
to useful same-unit context (for example, the highest-current consumers); the process
strip is how the user changes focus. The legend explains the curated visible series
and is not the primary metric picker.

### 4. Which metrics are must, should, or defer?

Priority means priority for the Visual redesign, not whether a field exists elsewhere.

| Metric / capability | Decision | Reason and presentation |
| --- | --- | --- |
| Per-process and total RSS | **Must** | Core process-memory comparison; MB. |
| Heap used | **Must** | Live JVM occupancy when the probe reports it; MB; unavailable stays unavailable. |
| Heap limit (`-Xmx`) | **Must** | Important capacity context; dashed/configured-limit semantics, not usage. |
| Metaspace used | **Should** | Fits Memory's MB axis and is already sampled when available. |
| CPU over time | **Should** | Fundamental resource trend; requires V5 because the live timeline omits it. |
| Read/write I/O | **Should** | Already sampled as counters; V2 must display rates derived from deltas. |
| Young/old GC | **Should** | Useful selected-JVM activity; use count deltas, with GC time preferred when V5 adds it. |
| Thread count | **Should** | Useful selected/comparison gauge with a compatible count axis. |
| Retained `15m` / `1h` windows | **Should** | Needed to see more than the nominal three-minute live buffer; implement through V4's ownership-safe source. |
| Page faults / context switches | **Defer** | Specialist rate metrics that add modes before the core families are proven. |
| Open file descriptors | **Defer** | Useful diagnostic, but better in the inspector until demand for a trend is demonstrated. |
| Host metrics | **Defer** | Host-wide data cannot be honestly attributed to a selected process and would turn Visual into a host dashboard. |
| `All retained` range | **Defer** | Unbounded density and short-lived process identity need a separate retention/downsampling decision. |

CPU is **should**, not a V1/V2 launch blocker. V5 remains valid and should add CPU
after the shell and family contract are in place. V5 may also add GC time fields if
the collector contract can preserve unavailable values.

### 5. What is the selection and emphasis model?

- Default to the active process with the highest current RSS when there is no valid
  selection.
- Keep one selected process across metric-family changes and range changes whenever
  that process exists in the new data.
- Emphasize the selected process; mute bounded same-metric context series. Do not make
  selection synonymous with hiding every other process.
- Use the compact process strip as the main selection control. Legend interaction may
  select a process but must not become a second arbitrary visibility system.
- Preserve a stable live selection by process identity when available; do not assume a
  PID alone identifies a historical incarnation.
- Trim V3 to selected-process emphasis first. Live-to-Visual handoff is optional and
  deferred within V3 until shared selection can carry a safe process identity without
  coupling the inspector and Visual state holders.

### 6. Which time windows and data sources?

Ship the layout against the current **Live** buffer first. Keep its label honest: it is
the retained live sample buffer, not a guaranteed wall-clock duration.

V4 then adds **15 min** and **1 hour**. These ranges read persisted samples and must:

1. use an application-level source that works when the JVM owns persistence and when
   PreferGo/cored owns the shared database;
2. query all relevant processes for the bounded range in timestamp order;
3. preserve null/unavailable metric values and process-incarnation identity as far as
   the source provides it;
4. downsample with metric-aware aggregation (peak-preserving for gauges where useful,
   counter deltas before rate aggregation);
5. preserve selection when the same process identity exists; and
6. show loading, source error, and "No samples in this range" as different states.

Do not make Compose open an ad hoc database connection. If an ownership-safe source is
not available, V4 remains blocked rather than silently falling back to Live or showing
fabricated history. `All retained` remains deferred.

### 7. What visual-language constraints apply?

- One dominant chart and one clear control hierarchy in the first viewport.
- No four-card KPI strip, nested dashboard collage, glow treatment, or pill cluster.
- Color communicates metric/process identity consistently; emphasis also uses stroke,
  opacity, label weight, or markers so it is not color-only.
- Every mode names its unit. Incompatible units never share an unlabeled axis.
- Unavailable, loading, empty, stale, and error are distinct. Missing heap, GC, or I/O
  is not plotted as zero.
- Tooltips report exact sampled/derived values and timestamps even if rendering is
  smoothed or downsampled.
- Heap used, heap limit, cumulative counters, and derived rates use explicit labels.
- Controls and process labels remain keyboard reachable and do not rely on hover.
- Charts stay in Visual. The #486 inspector and #473 compact assessment must not grow
  a competing multi-series visualizer.

## Follow-on issue spine

The issues already exist. This spike settles their disposition as follows:

| Unit | Issue | Disposition after spike |
| --- | --- | --- |
| V1 | [#491](https://github.com/cdsap/daemonitor/issues/491) | **Keep.** Build layout A's shell, compact summary, process strip, and honest states. |
| V2 | [#492](https://github.com/cdsap/daemonitor/issues/492) | **Keep.** Add family modes and metric-specific transformations; Memory can ship before CPU. |
| V3 | [#493](https://github.com/cdsap/daemonitor/issues/493) | **Trim.** Ship emphasis/persistence; keep Live-to-Visual handoff optional pending safe shared identity. |
| V4 | [#494](https://github.com/cdsap/daemonitor/issues/494) | **Keep, gated.** Add 15m/1h only through an ownership-safe persisted-sample source. |
| V5 | [#495](https://github.com/cdsap/daemonitor/issues/495) | **Keep.** CPU is should; also evaluate GC pause-time fields. Preserve unavailable semantics. |
| V6 | [#496](https://github.com/cdsap/daemonitor/issues/496) | **Defer as not planned.** Layout A won, so no small multiples or sparklines are justified. |

Suggested delivery order: **V1 → V2 → V3 → V5**, with **V4** proceeding as soon as
its persistence-source prerequisite is satisfied. V6 needs new evidence and a new
layout decision before it is reopened as implementation work.

## Non-goals

- Implementing any UI or data-path change in #490
- Alerting, anomaly detection, or recommendations
- A host dashboard
- Develocity or GBOS projection
- Replacing Live or History
- Implementing the #486 inspector or #473 assessment here
- Pixel parity with the TUI

## Exit assessment

- The seven spike questions are answered above.
- Layout A is chosen and B/C are rejected for V1.
- The metric table makes must/should/defer decisions and defines units/transformations.
- V1–V5 have explicit retained/trimmed/gated scope; V6 is explicitly deferred.
- No implementation is part of this spike.

## Key files and prior art

- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/ProcessVisualScreen.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/VisualChartModel.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/charts/OverallRssTimelineChart.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/LiveViewModel.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/LiveUiState.kt`
- `core/src/main/sqldelight/io/github/cdsap/daemonitor/store/db/Watcher.sq`
- `cli/src/main/kotlin/io/github/cdsap/daemonitor/coreipc/GoCoreWiring.kt`
- `docs/plans/2026-08-31-0733-spike-visual-trend-graph-plan.md` (prior branch/history)
- `docs/plans/2026-11-10-1746-spike-desktop-process-details-from-tui-plan.md`
