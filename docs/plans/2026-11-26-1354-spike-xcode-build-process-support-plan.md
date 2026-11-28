---
title: "Spike — Xcode build process support: document analysis, impact, and cost"
created: 2026-11-26
status: tier0-tier2-implemented
surface: daemonitor-cored (darwin) + CLI/TUI + SQLite store + Kotlin desktop parity
origin: Analysis of /Users/factory/Downloads/xcode-build-execution.docx against Daemonitor v1.2.3+ codebase
related:
  - docs/plans/2026-11-10-1830-feat-bazel-cli-support-plan.md
  - docs/plans/2026-11-24-1552-spike-kotlin-toolchain-cli-classification-plan.md
  - cored/docs/cross-platform-telemetry-matrix.md
target_release: spike evaluation (recommended phased rollout: Tier 0 -> Tier 1 -> Tier 2)
---

# Spike: Xcode Build Process Support — Impact & Cost Assessment

## Executive Summary

Supporting Xcode build processes in Daemonitor is **architecturally feasible on macOS** because `daemonitor-cored` already has native Darwin `proc_pid_rusage` collection, `(host, pid, create_time_ms)` incarnation tracking, and lifecycle event tables (`process_instances`, `process_events`).

However, Xcode is a **fundamentally different workload shape** from Gradle, Kotlin Daemon, Bazel, and Kotlin Toolchain:

- **No JVM**: zero heap/GC/JIT telemetry; 10 of 17 wide TUI columns become `n/a`.
- **Fan-out of ephemeral native tools**: 10–32+ parallel `swift-frontend`, `clang`, `ld`, `actool`, `dsymutil`, and `codesign` jobs spawning and exiting inside seconds, rather than 1–3 long-lived JVM daemons.
- **Broken PPID tree**: `xcodebuild` / `Xcode` submits requests over XPC to `SWBBuildService` / `XCBBuildService`, whose lifetime is independent of individual builds; compiler children do **not** reliably descend from `xcodebuild`.
- **High false-positive risk**: matching unscoped `clang`, `ld`, or `sh` would swallow unrelated developer workloads.
- **No global daemon log**: unlike `~/.gradle/daemon/*.out.log`, Xcode has no passive global log file for automatic build history correlation.

**Recommendation:** Execute in bounded tiers focused purely on Daemonitor observability (explicitly dropping the document's §4 prediction/forecasting layer as out of scope):

| Tier | Scope | Effort | Risk | Value |
|------|-------|--------|------|-------|
| **Tier 0** | Darwin telemetry hygiene (fix macOS disk I/O wiring, deduplicate `proc_pid_rusage`, Xcode secret redaction) | **1–1.5 eng days** | Low | Fixes existing macOS gaps across all build systems |
| **Tier 1** | Classify-first live observation (`cored` + TUI/CLI + Kotlin parity, collapsible `BuildService` root, non-JVM UI polish) | **3–5 eng days** | Low–Med | Immediate live visibility of Xcode builds with low blast radius |
| **Tier 2** | Persisted observation sessions, attribution confidence, adaptive 1s polling, Darwin host pressure/swap/thermal, Xcode build history | **1.5–2.5 eng weeks** | Med–High | Accurate per-build rollups, concurrent build honesty, history/MCP parity |
| **Tier 3 (Optional)** | Post-build `.xcresult` ingestion (`xcresulttool`) for target/timing correlation | **4–6 eng days** | Medium | Optional enrichment when `-resultBundlePath` is present |

---

## 1. Critical Analysis of `xcode-build-execution.docx` vs. Daemonitor Reality

### 1.1 What the Document Gets Right (Validated Architectural Constraints)

1. **Process topology**: `Xcode` / `xcodebuild` talks to `SWBBuildService` / `XCBBuildService`, which schedules native jobs (`swift-frontend`, `swiftc`, `clang`/`clang++`, `ld`, `actool`/`ibtool`, `dsymutil`/`codesign`, custom build-phase scripts).
   - *Verified locally on Xcode 27.2 (`Build 27B52`):* both bundles are present at:
     - `/Applications/Xcode.app/Contents/SharedFrameworks/SwiftBuild.framework/Versions/A/PlugIns/SWBBuildService.bundle`
     - `/Applications/Xcode.app/Contents/SharedFrameworks/XCBuild.framework/Versions/A/PlugIns/XCBBuildService.bundle`
     - Toolchain binaries under `/Applications/Xcode.app/Contents/Developer/usr/bin/{xcodebuild,actool,ibtool}` and `/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/{swiftc,swift-frontend,clang,ld,dsymutil}` (note: `codesign` lives at `/usr/bin/codesign`, so it must be ancestor-verified rather than Xcode-path-scoped).
2. **Identity & attribution**: PID alone is unsafe; `(pid, start_time)` is mandatory, and parent-PID ancestry cannot be assumed to root at `xcodebuild`. Concurrent builds must surface ambiguity rather than silently merging.
3. **RSS vs. Physical Footprint**: Summing RSS across parallel compiler jobs double-counts shared dyld/framework pages. Darwin `ri_phys_footprint` must be kept distinct from RSS.
4. **Polling blindness**: Periodic polling misses sub-interval jobs; job counts must be labeled as *observed*, not exhaustive, and cumulative counters must be differenced per process incarnation before aggregating.

### 1.2 Where the Document's Assumptions Diverge from Daemonitor's Codebase

| Document Assumption | Actual Daemonitor State | Implication for Cost |
|---------------------|-------------------------|----------------------|
| "Prototype with `ps` at 1s; evolve to native `libproc` (`proc_pidinfo` / `proc_pid_rusage`)" | `cored` **already** uses `gopsutil` + CGO `proc_pid_rusage(RUSAGE_INFO_V4)` on macOS (`cored/internal/poll/memory_metrics_darwin.go`, `cored/internal/platform/darwin_rusage.go`). | **Saves work** — skip `ps` prototyping entirely; fix 3 existing Darwin gaps instead (see §2.1). |
| "Reuse Build Process Watcher's timeline, storage and **prediction** layers" | Timeline & SQLite storage exist; there is no forecasting layer (`memory_growth.go` is a daemon leak heuristic). | **Dropped (out of scope for Daemonitor)** — avoids greenfield ML/forecasting cost completely. |
| "Capture `session_id`, `role`, `attribution_confidence`, `footprint_bytes`, `read_bytes`, `write_bytes`" | `cored/internal/sessions/sessions.go` is an **unwired, in-memory Gradle spike** keyed on `GradleJVMPID` and PID-only `SamplesInWindow`. Not persisted in SQLite or mounted in `api/server.go`. | Session model needs real persistence + schema work (Tier 2). |
| "Host context: memory pressure, swap, logical CPUs, thermal state" | `platform/gopsutil.go` collects logical CPUs, CPU %, memory total/avail/used, load1/5/15. It does **not** collect Darwin memory pressure, host swap, or thermal state. | Additive Darwin host adapter work (~1–2 days in Tier 2). |
| "Independent service lifetime vs. session lifetime" | Daemonitor's `builds` table and `builds.Aggregator` are tightly coupled to Gradle daemon logs (`daemon_pid` + `daemon_identity`). | Cannot reuse `cored/internal/logs` for Xcode; needs invocation-based or wrapper-based lifecycle. |

---

## 2. Detailed Impact Across Daemonitor Subsystems

### 2.1 Collector & Darwin Platform Layer (`cored/internal/poll/`, `cored/internal/platform/`)

**Existing strengths:**
- Incarnation identity `(pid, createTimeMs)` with monotonic CPU counter checks already exists (`cored/internal/poll/poll.go:49-52,246-267`).
- `ShouldProbeLiveHeap(kind)` (`poll.go:485-492`) is an explicit allowlist, so native Xcode types automatically bypass `jcmd`/`jstat` probes with zero probe failures or overhead.

**Gaps & bugs uncovered during this spike:**
1. **macOS disk I/O is collected but dropped before storage/API:** `collector_darwin.go:17-19` sets `LogicalReadBytesTotal`/`LogicalWriteBytesTotal` to `Unsupported` and populates `DiskReadBytesTotal`/`DiskWriteBytesTotal` via `proc_pid_rusage`, but `poll.go:514-515` only copies `LogicalReadBytesTotal`/`LogicalWriteBytesTotal` into `model.Process.ReadBytes`/`WriteBytes`. Result: `read_bytes`/`write_bytes` are always `null` on macOS.
2. **Duplicate `proc_pid_rusage` syscall + stale capability:** Both `platform/darwin_rusage.go` and `poll/memory_metrics_darwin.go` invoke `proc_pid_rusage(RUSAGE_INFO_V4)` separately per PID, while `collector_darwin.go:29` still advertises `process.memory.physical_footprint_bytes` as `NotCollected`. Under a 24-way Swift build, unifying this into one `proc_pid_rusage` call halves Darwin rusage syscall cost.
3. **CGO requirement on macOS:** `darwin_rusage_nocgo.go` and `memory_metrics_darwin_nocgo.go` stub both disk I/O and physical footprint when `CGO_ENABLED=0`. macOS release binaries must remain CGO-enabled (or switch to a pure-Go `libproc` binding).
4. **False-positive control requires executable path + ancestor walk:** Today `poll.go:159-163` classifies purely from `cmdline`. Matching `clang`, `ld`, `actool`, or `sh` from `cmdline` alone will misclassify Homebrew/Cargo/CMake/shell workloads. Safe Xcode classification requires:
   - Always matching `xcodebuild`, `SWBBuildService`, `XCBBuildService` (path-verified under `/Xcode*.app/`, `/CommandLineTools/`, or `/usr/bin/xcodebuild`).
   - Matching `swift-frontend`, `swiftc`, `clang`/`clang++`, `ld`, `actool`, `ibtool`, `dsymutil`, `codesign` **only when** executable path is inside an Xcode/CommandLineTools toolchain **OR** parent chain includes `xcodebuild` / `SWBBuildService` / `XCBBuildService`.
   - **Never** matching unscoped `sh`/`bash` unless explicitly parented by `SWBBuildService`/`XCBBuildService`/`xcodebuild`.
   - **Excluding** the interactive `Xcode` GUI app itself from default worker rows (and especially from TUI `x`/`X` kill targets!).
5. **Single-sample CPU blindness:** `poll.go:55-68` returns `cpu_percent = nil` on a process's first observation. At the default 2s poll interval (`cored/cmd/daemonitor-cored/main.go:22`), any compiler job living <2s never gets an interval CPU rate. Cumulative `cpu_user_seconds_total` / `cpu_system_seconds_total` *are* captured on sample 1 and should be used for ephemeral job CPU-second accounting.

### 2.2 Taxonomy & Kotlin/Go Dual-Surface Coupling

**Critical finding:** `cli/src/main/kotlin/io/github/cdsap/daemonitor/coreipc/GoCoreSnapshotParser.kt:78` drops any process whose `type` is not in Kotlin's `ProcessType` enum:
```kotlin
val type = runCatching { ProcessType.valueOf(typeName) }.getOrNull() ?: return null
```
And `core/.../WatcherDatabase.kt:141,507` falls back unknown DB `process_type` strings to `UNKNOWN_GRADLE_WORKER` / `JAVA_GRADLE_RELATED`!

Therefore, adding **10 granular `XCODE_*` enum values** would force edits across **10+ Kotlin files** (`GradleProcess.kt`, `GradleProcessClassifier.kt`, `ProcessSnapshotBuilder.kt`, `ProcessCollector.kt`, `LiveMetricLabels.kt`, `WatcherDatabase.kt`, `HeadlessTerminalUi.kt`, `ProcessTypeIcon.kt`, `VisualChartModel.kt`, `LiveProcessGrouping.kt`) plus tests and icons.

**Design recommendation:** Keep the top-level `ProcessType` enum compact (**4 Xcode types**), and store specific tool names (`swift-frontend`, `clang`, `ld`, `actool`, `dsymutil`, `codesign`) in `model.Process.Name` (already populated!) or an optional `role`/`subrole` field:

| `ProcessType` | Display Label | Matches | Hierarchy Role |
|---------------|---------------|---------|----------------|
| `XCODE_BUILD` | `Xcode build` | `xcodebuild` | Standalone / session anchor |
| `XCODE_BUILD_SERVICE` | `Xcode build service` | `SWBBuildService`, `XCBBuildService` | **Group root** (like `GRADLE_DAEMON`) |
| `XCODE_COMPILER` | `Xcode compiler` | Xcode-scoped `swift-frontend`, `swiftc`, `clang`, `clang++` | Child of build service (or standalone) |
| `XCODE_TOOL` | `Xcode build tool` | Xcode-scoped `ld`, `actool`, `ibtool`, `dsymutil`, `codesign` (+ build-service-parented scripts) | Child of build service (or standalone) |

*Alternative (longer term):* Replace closed-enum `ProcessType` with ecosystem + role facets (same refactoring previously deferred in `docs/plans/2026-11-10-1830-feat-bazel-cli-support-plan.md`). Don't block Tier 1 on a facet rewrite.

### 2.3 Hierarchy, TUI, CLI & Desktop UX (`cored/internal/tui/`, `render/`, `src/.../ui/`)

1. **Hardcoded `GRADLE_DAEMON` root**:
   - `cored/internal/tui/hierarchy.go:84,128,140` and `src/.../ui/live/LiveProcessGrouping.kt:36,49` only root on `GRADLE_DAEMON`. Adding `XCODE_BUILD_SERVICE` as a root keeps 16–32 compiler children collapsible under one aggregate row.
   - **Honesty nuance**: `aggregateGroup` sums `RSSMemoryMB`. For Xcode, summed RSS across parallel `swift-frontend` jobs inflates apparent RAM due to shared frameworks; grouped headers should eventually show `Physical Footprint` (or note summed RSS).
2. **Row churn / visual jitter**: Flat view with 30 transient `swift-frontend` rows refreshing every 1–2s is noisy; grouped view (with `XCODE_BUILD_SERVICE` collapsed or showing role counts like `12 swift-frontend, 3 clang, 1 ld`) is much more readable.
3. **JVM-centric TUI columns & details pane**:
   - `table_view.go:258-262`: 10 of 17 wide columns are JVM-specific (`GC`, `HEAP%`, `META`, `HEAP`, `CMT`, `XMX`, `YGCT`, `FGCT`, `CGCT`, `GCT`).
   - `details_view.go:167-194,394`: Renders `JVM MEMORY POOLS`, `GC COUNTERS / RATES`, `JIT / CODE CACHE`, and header `"GRADLE COMMANDER CENTER / PROCESS DETAILS"` for every process. For non-JVM processes, these sections should be hidden and `NATIVE MEMORY` (especially `Physical footprint`, `Resident`, `Peak`, `Disk I/O`) promoted to the top.
   - `details_view.go:459-481` (`analysis.Analyze`): Will always print `INSUFFICIENT_DATA` for ephemeral compilers (requires ≥5 samples over ≥2 min). Should show simple min/peak/last stats for short-lived processes instead.
   - Hardcoded `"No Gradle-related processes are currently running."` (`table_view.go:100`, `render.go:199`, `DaemonitorMcpServer.kt:65,118`) should be generalized to `"No supported build processes are currently running."`
4. **Kill safety (`x` / `X`)**: Confirming `X` (kill all) in TUI (`tui/table_view.go:222`) could terminate an active `SWBBuildService` or `xcodebuild`. Keep `Xcode.app` unclassified and ensure kill prompts clearly display the process type and impact.

### 2.4 Storage, Performance & Query Limits (`cored/internal/store/`, `Watcher.sq`, `api/server.go`)

| Dimension | Current Gradle Workload | Active Xcode Workload (16-core Mac) | Impact |
|-----------|------------------------|-------------------------------------|--------|
| Concurrent matched processes | 2–6 | **15–40** (peak compilation) | 5–10× more `CollectProcess` calls per tick |
| Poll interval | 2s (`main.go:22`) | 2s (Tier 1) / **1s adaptive** (Tier 2) | Up to 10–20× higher `process_samples` write rate during builds |
| SQLite rows per 2-min build | ~240 rows | ~1,800–3,600 rows (at 1s) | Moderate (~1–3 MB WAL/DB per build; 24h retention keeps size bounded) |
| `process_events` churn | ~4–10 events/build | **100–500+ events/build** (`first_observed` + `disappearance_detected`) | `defaultQueryLimit = 100` in `/v1/events` & `/v1/processes/history` (`api/server.go:46`) saturates in seconds! |
| Schema parity (`store.go` vs `Watcher.sq`) | `store.go` is at v6 (63 cols); `Watcher.sq` lacks `physical_footprint_bytes`, `cpu_user_seconds_total`, etc. | Needs alignment if Desktop reads Xcode native metrics directly from SQLite | Small additive `Watcher.sq` migration |

### 2.5 Build History, Sessions & Attribution (`sessions/`, `builds/`, `api/server.go`, MCP)

- **Passive `xcodebuild` attribution (Tier 1/2):** Extract project context from `xcodebuild` argv (`-workspace`, `-project`, `-scheme`, `-configuration`, `-derivedDataPath`, `-destination`, fallback `cwd`). Use `xcodebuild` `first_observed` → `disappearance_detected` timestamps as an inferred session window.
- **IDE-initiated builds (`Xcode.app`):** No `xcodebuild` process exists. `SWBBuildService` still spawns `swift-frontend`/`clang`/`ld`, so live telemetry works in Tier 1, but session boundaries and outcome (pass/fail) cannot be known reliably without reading Xcode's `DerivedData/.../Logs/Build/*.xcactivitylog` or `.xcresult`.
- **Explicit wrapper / session API (Tier 2):** `daemonitor-cli observe -- xcodebuild ...` (calling `POST /v1/sessions` open/close) gives **100% deterministic session boundaries, exit code, and `confirmed` attribution** when paired with `-derivedDataPath` or explicit PID/time scoping, and marks `ambiguous` whenever ≥2 concurrent Xcode builds overlap.

### 2.6 Security, Privacy & Distribution Constraints

1. **Mac App Store Sandbox (`packaging/macos/app-store.entitlements`):** `com.apple.security.app-sandbox = true` blocks reading arbitrary process argv (`KERN_PROCARGS2`) and cross-process `proc_pid_rusage`. Xcode support will **only work in non-sandboxed distributions** (Homebrew `daemonitor-cli`/`daemonitor-cored` and direct DMG desktop builds) — consistent with `docs/mac-app-store-distribution.md`.
2. **Command-line redaction (`cored/internal/poll/redactor.go`):** `xcodebuild` invocations can carry `-authenticationKeyPath`, `-authenticationKeyID`, `-authenticationKeyIssuerID`, `DEVELOPMENT_TEAM`, or secret `KEY=VALUE` build settings. Redactor rules must be extended and unit-tested before persisting Xcode command lines.
3. **CI / Test Matrix (`cored/docs/cross-platform-telemetry-matrix.md`):** Automated runtime validation is Apple Silicon (`macos-latest`) only; Intel macOS remains manual-evidence-only. Linux and Windows paths are unaffected (guard Xcode matching to `runtime.GOOS == "darwin"` or keep classifier portable with Darwin-only runtime tests).

---

## 3. Cost & Effort Breakdown by Tier

### Tier 0 — Darwin Telemetry Hygiene (**1–1.5 engineering days**, Low Risk)
*Valuable even if Xcode support is deferred.*
- **U0.1** Unify `proc_pid_rusage(RUSAGE_INFO_V4)` into `cored/internal/platform/darwin_rusage.go` (single syscall returning `ri_phys_footprint`, `ri_lifetime_max_phys_footprint`, `ri_diskio_bytesread`, `ri_diskio_byteswritten`); flip `process.memory.physical_footprint_bytes` capability to `Available` when CGO is enabled.
- **U0.2** Expose `disk_read_bytes` / `disk_write_bytes` cleanly on `model.Process` + `store.go` (preserving TEL-04 separation from `logical_read_bytes`), so macOS I/O is no longer silently `null`.
- **U0.3** Extend `cored/internal/poll/redactor.go` + tests for `xcodebuild` authentication/signing flags and `KEY=VALUE` secrets.

### Tier 1 — Classify-First Live Observation (**3–5 engineering days**, Low–Medium Risk)
*Follows the proven Bazel / Kotlin Toolchain spike pattern.*
- **U1.1** Go classifier (`cored/internal/poll/`): add `XCODE_BUILD`, `XCODE_BUILD_SERVICE`, `XCODE_COMPILER`, `XCODE_TOOL` with path-scoped / ancestor-verified matching (no unscoped `clang`/`ld`/`sh`, exclude `Xcode.app` GUI). Extract `ProjectPath` from `xcodebuild` `-workspace` / `-project` / `cwd`.
- **U1.2** Grouping & labels (`cored/internal/render/render.go`, `cored/internal/tui/hierarchy.go`): add `TypeDisplay` labels and make `XCODE_BUILD_SERVICE` a hierarchy root alongside `GRADLE_DAEMON`.
- **U1.3** Non-JVM UI polish (`cored/internal/tui/details_view.go`, `table_view.go`): hide empty JVM/GC/JIT sections for non-JVM process types; surface `Physical footprint` & `Disk I/O` cleanly; generalize `"No Gradle-related processes"` copy.
- **U1.4** Kotlin parity (`GradleProcess.kt`, `GradleProcessClassifier.kt`, `LiveMetricLabels.kt`, `WatcherDatabase.kt`, `HeadlessTerminalUi.kt`, `ProcessTypeIcon.kt`, `VisualChartModel.kt`, `LiveProcessGrouping.kt`): add the 4 enum values so Desktop/Kotlin CLI never drop or mislabel Xcode rows from `cored`.
- **U1.5** Real-world verification on macOS (Xcode 27.2) with clean, no-change, and incremental `xcodebuild` runs + collector overhead check via `/v1/self`.

### Tier 2 — Session-Scoped Observation, Attribution & History (**1.5–2.5 engineering weeks**, Med–High Risk)
*Delivers the core observability value of `xcode-build-execution.docx` §4–§5.*
- **U2.1** Graduate `cored/internal/sessions` to persisted SQLite (`observation_sessions`, `session_process_links`) + `/v1/sessions` HTTP endpoints in `api/server.go`, using `(host_id, pid, start_time_ms)` incarnation-scoped queries instead of PID-only queries.
- **U2.2** Attribution engine: tag each process in a session as `confirmed` (PPID/DerivedData/wrapper match), `inferred` (single active Xcode build temporal overlap), or `ambiguous` (≥2 concurrent Xcode builds).
- **U2.3** Adaptive polling cadence: drop `ProcessOS` poll interval from 2s → 1s (configurable, e.g., 500ms–1s) while an Xcode session or `XCODE_BUILD` is active; revert to 2s when idle.
- **U2.4** Role-based session rollups: peak aggregate RSS, peak aggregate physical footprint, total user+system CPU seconds (from cumulative counters so 1-sample jobs count), max observed parallelism, disk I/O deltas, and observed job counts by role.
- **U2.5** `daemonitor-cli observe -- xcodebuild ...` wrapper command + passive `xcodebuild` lifecycle session auto-capture into `builds` / History UI / MCP server.
- **U2.6** Darwin host context metrics in `collector_darwin.go` + `HostMetrics`: memory pressure level, host swap usage, and thermal state.
- **U2.7** Query pagination / filtering updates (`/v1/processes/history`, `/v1/events`) so bursty Xcode runs don't clip at `limit=100`.

### Tier 3 (Optional) — `.xcresult` Post-Build Enrichment (**4–6 engineering days**, Medium Risk)
- **U3.1** Optional `.xcresult` post-build parser (`xcresulttool` invocation when `-resultBundlePath` is present on argv or passed via `daemonitor-cli observe`) to overlay Xcode's target/task timing summary onto Daemonitor's sampled OS telemetry.

### Explicit Non-Goals (Out of Scope for Daemonitor)
- **No prediction / forecasting layer** (Doc §4 10/30/60s RSS/job forecasts) — Daemonitor is a process/build observer, not a predictive modeling tool.
- **No `dtrace` / `kdebug` / EndpointSecurity / root helpers** to capture sub-500ms exec/exit events — breaks Daemonitor's zero-privilege user-space model.
- **No `Xcode.app` IDE plugin or private XPC hooking** — fragile across Xcode updates and breaks SIP/signing.
- **No synthetic JVM fields** — never coerce `heap_used_mb` or GC counters to zero for native processes.
- **No Mac App Store sandbox claim** for Xcode observation.

---

## 4. Reproducible Validation Plan (Adapted from Doc §5)

Before promoting Tier 1 → Tier 2, run a paired validation experiment on macOS (this host: Xcode 27.2, `Build 27B52`) against a real multi-target Swift/iOS or SwiftPM-backed Xcode project:

1. **Three controlled conditions** (recording Xcode/macOS versions, CPU core count, DerivedData & package cache state):
   - **Clean:** fresh `-derivedDataPath /tmp/daemonitor-xcode-dd`
   - **No-change:** immediate repeat build
   - **Incremental:** edit one Swift file and rebuild
   - **Concurrency test:** launch two simultaneous `xcodebuild` invocations against separate DerivedData dirs to verify ambiguity flagging (Tier 2) and non-merging of incarnations.
2. **Paired overhead check** (per `cored/docs/cross-platform-telemetry-matrix.md` §Performance gate):
   - Compare wall time and `/v1/self` (`poll_duration_ms`, DB growth) with `daemonitor-cored` off vs. on at 2s and 1s intervals.
3. **Acceptance criteria for Tier 1 go/no-go:**
   - `xcodebuild`, `SWBBuildService`/`XCBBuildService`, and active `swift-frontend`/`clang`/`ld` rows appear with non-zero RSS and `physical_footprint_bytes`.
   - Zero unrelated `clang`/`sh` processes from non-Xcode workloads are classified.
   - `ShouldProbeLiveHeap` emits zero JVM probe attempts for Xcode processes.
   - `daemonitor-cored` poll duration stays well below 20% of poll interval even at peak compiler parallelism.
   - Kotlin Desktop / CLI parses all 4 Xcode types cleanly without dropping rows.

### Tier 0 + Tier 1 Verification Results (2026-11-26, Xcode 27.2 / Build 27B52)

- **Go test suite:** `go test ./...` in `cored/` — all 16 packages pass (including new `xcode_test.go`, `darwin_rusage_test.go`, `redactor_test.go`, `render_test.go`, and `hierarchy_test.go`).
- **Build checks:** Both CGO native (`cored/bin/daemonitor-cored`, `cored/bin/daemonitor-cli`) and `CGO_ENABLED=0` cross-compile succeed cleanly.
- **Kotlin parity tests:** `:core:test`, `:cli:test`, and desktop `:test` targeted parity suites pass (`GradleProcessClassifierTest`, `RedactorTest`, `LiveMetricLabelsTest`, `ProcessSnapshotBuilderTest`, `GoCoreSnapshotParserTest`, `LiveProcessGrouping*`, `ProcessTypeIcon*`, `VisualChartModel*`, `HeadlessTerminalUiTest`).
- **Live `xcodebuild` smoke run** (temp SwiftPM package, `xcodebuild -scheme Demo -destination 'platform=macOS' -derivedDataPath ... build`, `daemonitor-cored --interval 500ms`):
  - `XCODE_BUILD` (`xcodebuild`): 16 samples, peak RSS 160 MB, peak physical footprint **64.0 MB**, disk read 20.1 MB / write 717 KB, project `dmn-xcode-smoke`, `heap_available=false`
  - `XCODE_BUILD_SERVICE` (`SWBBuildService`): 10 samples, peak RSS 258 MB, peak physical footprint **76.0 MB**, disk read 21.5 MB / write 4.3 MB, `heap_available=false`
  - `XCODE_COMPILER` (`clang`, `swift-frontend`): 9 samples across 4 PIDs, peak RSS 323 MB, peak physical footprint **172.9 MB**, disk read 41.5 MB / write 42.2 MB, `heap_available=false`
  - Darwin capabilities verified via `daemonitor-cli metrics list --json`: `physical_footprint_bytes`, `peak_resident_bytes`, `disk_read_bytes_total`, and `disk_write_bytes_total` all report `available (proc_pid_rusage)`.

### Tier 2 Verification Results (2026-11-26, Xcode 27.2 / Build 27B52)

- **Go test suite:** `go test ./...` in `cored/` — all 16 packages pass (including new `sessions_api_test.go`, `observe_test.go`, extended `sessions_test.go`, `darwin_rusage_test.go`, and `instances_test.go` schema v7 checks).
- **Build checks:** Native CGO (`cored/bin/daemonitor-cored`, `cored/bin/daemonitor-cli`) and `CGO_ENABLED=0` cross-compile both pass.
- **Live end-to-end `daemonitor-cli observe -- xcodebuild ...`** (with `daemonitor-cored --interval 2s` proving adaptive 1s polling):
  - `session=obs-a681d3a85d436f29 ecosystem=xcode mode=explicit outcome=success duration=6.43s`
  - `peak_rss=567 MB peak_footprint=219 MB total_cpu=2.07s max_parallelism=3 observed_processes=5 disk_read=37.9 MB disk_write=4.6 MB`
  - Captured all 4 Xcode roles: `Xcode build` (1), `Xcode build service` (1), `Xcode compiler` (2), `Xcode build tool` (1).
  - Verified durable persistence via `daemonitor-cli sessions --ecosystem xcode` and `daemonitor-cli builds` (`status=SUCCESS`, `dur=6.4s`, `project=dmn-xcode-tier2`).
  - Verified Darwin host metrics via `daemonitor-cli host`: `swap_used=8.8 GB`, `swap_total=10 GB`, `memory_pressure=normal`, `thermal_state=nominal`.

### Multi-Target Validation Benchmark (2026-11-27, 9 targets / 65+ Swift+C files, 14 logical cores, Xcode 27.2)

| Condition | Wall Time | Peak RSS | Peak Footprint | Total CPU | Max Parallelism | Notes |
|-----------|-----------|----------|----------------|-----------|-----------------|-------|
| Baseline Clean (Daemonitor OFF) | 5.55s | — | — | — | — | Fresh DerivedData |
| Clean (Daemonitor ON) | 5.70s (+0.15s) | 5.2 GB | 3.1 GB | 18.5s | 16 (14 compilers) | 102 MB disk write; +2.7% wall delta |
| No-Change Repeat | 0.84s | 8 MB | 3.7 MB | 0.04s | 1 | `xcodebuild` only |
| Incremental (1 Swift file) | 1.36s | 353 MB | 126 MB | 1.39s | 2 | `xcodebuild` + `SWBBuildService` |
| Concurrent Build A + B | 6.77s / 6.75s | 4.8 GB / 6.0 GB | 2.4 GB / 3.5 GB | 16.5s / 20.4s | 17 / 17 | `concurrent_overlap=true`; `-derivedDataPath` isolated each build's compilers (`confirmed`) while marking shared `SWBBuildService` as `ambiguous` |

- **Collector self-telemetry (`/v1/self`):** `poll_duration_ms=57ms` (5.7% of 1s active budget), `poll_overrun_total=0`, `probe_failure_total=0`, `storage_write_duration_ms=0`.
- **Bug caught & fixed during validation:** Concurrent builds of the same project originally over-confirmed sibling compilers via generic `RootProjectPath` substring matching; fixed in `cored/internal/sessions/sessions.go` by excluding foreign `XCODE_BUILD` anchors and separating concurrent builds via `-derivedDataPath`.
- **New Xcode 27.2 helpers discovered via raw `ps` audit & added to `XCODE_TOOL`:** `appintentsmetadataprocessor`, `clang-stat-cache` (plus `swift-driver`, `tapi`).

---

## 5. Go / No-Go Decision Guide

| Decision | When |
|----------|------|
| **Go — Tier 0 + Tier 1** | You want multi-build-system live visibility (Gradle + Bazel + Kotlin Toolchain + Xcode) with ~1 week total effort and minimal architectural risk. |
| **Expand to Tier 2** | Only after Tier 1 proves operators need per-build Xcode history, concurrent-build attribution, and sub-2s CPU/parallelism rollups (and once `sessions` graduates for Gradle GBOS too — shared investment!). |
| **Optional Tier 3 (`.xcresult`)** | Only if operators want target-level Xcode timing summaries correlated alongside Daemonitor's process history (~4–6 days). |

### Open Questions to Confirm Before Implementation
1. **Surface priority:** Should Tier 1 target `daemonitor-cli` / `daemonitor-cored` first (like Bazel v1), or full Desktop UI polish (custom icons, Visual tab series, non-JVM Details layout) in the same slice?
2. **Shared session investment:** Should Tier 2's persisted `observation_sessions` table be designed jointly with Gradle Build Observation (`cored/internal/sessions`) so both Gradle and Xcode share one session/summary schema?
3. **IDE builds vs. CLI builds:** Is live visibility of `SWBBuildService` + compilers enough for Xcode IDE builds, or is build-history ingestion from `DerivedData/.../Logs/Build` required?
