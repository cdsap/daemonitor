---
title: Desktop Visual tab redesign + multi-metric surfaces
created: 2026-11-12
status: answered
surface: Compose Desktop Visual tab
origin: PreferGo desktop testing — Visual feels sparse/cluttered; extended timeline metrics exist in model but are hard to use
---

# Spike: Desktop Visual tab redesign + multi-metric surfaces

## Goal

Decide how the Compose Desktop **Visual** tab should look and behave so it feels like a focused process visualizer (not four tiles + one overloaded canvas), and which **additional metrics** beyond RSS / heap used / heap limit belong as first-class chart surfaces.

This spike produces a prioritized issue spine and layout decision. It does **not** implement UI.

## Why now

- Live details are being restructured as a tabbed inspector (#486); Visual should complement that pane, not compete with another long dump.
- PreferGo testing feedback: Visual is “not very nice” and underuses metrics already collected.
- Code already models extended timeline metrics (`THREADS`, `READ_BYTES`, `WRITE_BYTES`, `PAGE_FAULTS`, `CONTEXT_SWITCHES`, `OPEN_FILES`, `METASPACE`, `YOUNG_GC`, `OLD_GC`) with `defaultVisible = false`, but the chart UX is still RSS/heap-primary (`OverallRssTimelineChart`) with a dense series legend.
- An older Visual trend spike (`docs/plans/2026-08-31-0733-spike-visual-trend-graph-plan.md`) covered smooth trends + retained windows; this spike absorbs what’s still open and adds **metric IA + visual composition**.

## Current grounding

| Piece | Today |
|---|---|
| Screen | `ProcessVisualScreen` — header, 4 `StatTile`s, one full-bleed timeline |
| Chart | Custom Canvas `OverallRssTimelineChart` — multi-series lines, hover legend, process select via legend |
| Live buffer | `LiveViewModel` appends `RssTimelineSample` (RSS + heap maps + `extendedByPid`) |
| Extended metrics | In `VisualChartModel.timelineChart` / `TimelineMetric`, mostly off by default |
| Memory bars | `VisualChartData` / `ProcessMemoryBars` still computed; **not shown** on the dashboard |
| Refresh | 30s panel snapshot while tab is composed |
| Related | Live details inspector spike/issue #486; optional D7 compact trends in details (#473) |

## Spike questions

1. **What is the Visual tab’s one job?**
   Propose: “see memory/resource *trends* across live processes over a short window,” while Live table = inventory and details inspector = per-process attributes. Confirm or rewrite.

2. **Layout composition (must feel nicer without becoming a dashboard collage)?**
   Candidates (pick one primary):
   - **A. Focus chart + metric mode** — one primary chart; metric family switcher (Memory / CPU-ish / I/O / GC); compact selection strip
   - **B. Split canvas** — primary memory timeline + secondary small multiples for selected process only
   - **C. Process-first strip** — selectable process cards with mini sparklines; detail chart below for selection
   Prefer A unless spike finds B/C clearer in PreferGo screenshots.

3. **How do “other metrics” enter without line-soup?**
   - Metric **family** or **mode** (Memory vs I/O vs GC) rather than toggling 10+ series at once
   - Never mix incompatible units on one Y-axis without an explicit dual-axis decision
   - Extended series stay available for power users only if the default view stays scannable

4. **Which metrics are must / should / defer?**

   | Metric | Source today | Tentative |
   |---|---|---|
   | RSS (per + total) | live buffer | **must** |
   | Heap used / `-Xmx` limit | live + probe | **must** |
   | Metaspace used | extended sample | **should** |
   | Threads | extended | **should** |
   | Read / write bytes | extended | **should** |
   | Young / Old GC counts | extended | **should** (or GC time if we extend sample) |
   | Page faults / context switches / open FDs | extended | **defer** or power-only |
   | CPU % over time | not in timeline sample today | **decide**: add to buffer vs defer |
   | Host-level charts | `/v1/host` | **defer** (details D6 / separate) |
   | Persisted 15m / 1h windows from SQLite | older trend spike | **should** if PreferGo shared DB is honest; else defer behind #484-class schema safety |

5. **Selection + emphasis model?**
   Keep process selection first-class (legend or strip). Selected process emphasized; others muted. Align selection with Live table when navigating from Live → Visual (optional follow-on).

6. **Time window?**
   Confirm Live (~30s buffer) vs add `15 min` / `1 hour` from `process_samples` (carry forward from Aug spike). PreferGo path must not regress when cored owns the DB.

7. **Visual language constraints?**
   - No generic multi-card dashboard in the first viewport
   - No purple glow / pill-cluster chrome
   - Honest empty / unavailable states (no fake zero heap)
   - Charts stay on Visual; details inspector (#486) does not grow multi-series charts

## Tracking

| Unit | Issue |
|---|---|
| Spike | [#490](https://github.com/cdsap/daemonitor/issues/490) |
| V1 Visual shell redesign | [#491](https://github.com/cdsap/daemonitor/issues/491) |
| V2 Metric family switcher | [#492](https://github.com/cdsap/daemonitor/issues/492) |
| V3 Selection emphasis (+ Live→Visual) | [#493](https://github.com/cdsap/daemonitor/issues/493) |
| V4 Time-window from retained samples | [#494](https://github.com/cdsap/daemonitor/issues/494) |
| V5 CPU / missing timeline fields | [#495](https://github.com/cdsap/daemonitor/issues/495) |
| V6 Small-multiples / sparklines (if B/C) | [#496](https://github.com/cdsap/daemonitor/issues/496) |

## Proposed issue breakdown

### Spike — Visual tab IA + metric surfaces (#490)

**Outcome:** Written answers to the seven questions; primary layout choice (A/B/C); must/should/defer metric table; confirm or trim V1–V6.

### V1 — Visual shell redesign (#491)

**Outcome:** Replace tile-row + opaque RSS chart chrome with the chosen composition; clearer hierarchy, selection strip, empty states.

### V2 — Metric family switcher (#492)

**Outcome:** First-class modes that show a curated series set; no default line-soup; unit axis labeled correctly per mode. Depends on V1.

### V3 — Selected-process emphasis + cross-tab selection (#493)

**Outcome:** Stronger selected-process styling; optional handoff from Live selection into Visual.

### V4 — Time-window control from retained samples (#494)

**Outcome:** Carry Aug spike R2–R3 if PreferGo/SQLite path is safe; otherwise document defer.

### V5 — CPU (and other missing) timeline sample fields (#495)

**Outcome:** Only if spike marks CPU as must/should — extend live buffer (+ cored fields if needed).

### V6 — Small-multiples or sparklines (#496)

**Outcome:** Implement secondary viz called by the layout decision; close as not planned if A wins.

## Non-goals

- Pixel-parity with TUI
- Alerting / anomaly detection
- Develocity / GBOS projection from Visual
- Replacing Live table or History
- Full host dashboard
- Implementing #486 inside this spike

## Spike answers (2026-11-12)

1. **One job:** short-window resource trends; Live = inventory; details inspector = attributes.
2. **Layout:** **A** (focus chart + metric mode + process strip).
3. **Other metrics:** Memory / I/O / GC / Threads modes; curated series only.
4. **Table:** must RSS+heap; should metaspace/threads/I/O/GC; defer faults/ctx/host; CPU→V5; windows→V4.
5. **Selection:** process strip + legend; default highest RSS.
6. **Window:** Live buffer in V1/V2; retained ranges deferred to V4.
7. **Language:** one composition; no tile collage; honest unavailable.

## Success criteria

- Spike answers all seven questions with evidence from `ProcessVisualScreen` / `VisualChartModel` / `OverallRssTimelineChart` / `LiveViewModel`
- Layout choice A/B/C is explicit
- Metric must/should/defer table is agreed
- Issues V1–V6 filed (trim if layout collapses some)
- Visual remains complementary to Live details inspector (#486)

## Suggested order after spike

`V1` → `V2` → (`V3` if cheap) → `V4` if DB window is greenlit → `V5` only if CPU is must → `V6` only if layout needs it.

## Key files

- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/ProcessVisualScreen.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/VisualChartModel.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/charts/OverallRssTimelineChart.kt`
- `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/LiveViewModel.kt`, `LiveUiState.kt`
- Prior: `docs/plans/2026-08-31-0733-spike-visual-trend-graph-plan.md`
- Sibling: #486 (tabbed Live process detail inspector)
