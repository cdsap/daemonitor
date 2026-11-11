# Desktop Live process details spike (#465)

## Decision

Keep Live as the selected process's current, inspectable snapshot. Add a small amount of daemon context there; retain cross-process charts and timelines in Visual and the searchable, full build record in History. The following is a proposed implementation contract for D1–D7, not a claim that the layout has been user-tested or agreed by the issue stakeholders. No UI change is part of this spike.

| Unit | Decision | Scope |
| --- | --- | --- |
| D2 #468 | Confirm | Gradle version and log path for Gradle daemons, adjacent to the existing log card. |
| D1 #467 | Confirm, with correlation prerequisite | Up to three recent build summaries for the selected Gradle daemon; History remains the full build browser. |
| D3 #469 | Trim | Add CPU user/system/total seconds, cores used and resource scope. Page faults and context switches are already displayed; do not duplicate them. |
| D4 #470 | Confirm | Add available private committed, PSS, physical footprint and peak resident values to Memory; retain distinct platform meanings and units. |
| D5 #471 | Confirm | Add pools, JIT and code cache to JVM details, with existing heap, metaspace and GC rows retained. |
| D6 #472 | Trim | Compact host context and an expandable capability explanation, not a full matrix in the default view. |
| D7 #473 | Defer | Visual already owns charts. Revisit a compact assessment only after a reliable, incarnation-scoped history and shared analysis contract exist. |

Implement in the suggested D2 → D1 → D3 → D4 → D5 → D6 order, addressing D1's identity prerequisite before displaying its data. D7 requires a separate decision.

## Proposed layout

The existing Live detail and daemon log cards remain separate, with one scrolling detail column:

```text
PID · type · status / ended             [snapshot]
Identity, project, uptime, children
Gradle version · log path               [Gradle daemon only; log metadata]
Recent builds (up to 3)                 [Gradle daemon only; builds]
Memory (heap + native) · CPU / OS activity
JVM (pools, JIT, code cache) · GC · I/O
Host context · collection capabilities  [expand for capabilities]
Command line / working directory

Daemon log card                         [existing tail]
```

Use expandable groups for long diagnostics rather than another multi-pane TUI. A build summary can show status, project, start and duration; History retains filtering, build detail and log excerpt. Visual retains its process comparison and memory timeline. No Gradle metadata, builds or Gradle log panel is offered for Bazel or other non-Gradle rows; shared OS and host fields remain applicable, and JVM fields appear only where relevant/available.

## Selection and data contract

When Go core is active, use its IPC sources for enrichments even when it owns the same database; do not open SQLDelight from Live to retrieve builds. The current wiring chooses shared-core-owned persistence versus remote builds based on database ownership, so D1 needs a dedicated core-backed detail read in both modes ([GoCoreWiring](../../cli/src/main/kotlin/io/github/cdsap/daemonitor/coreipc/GoCoreWiring.kt)). In JVM collector mode, keep existing snapshot/log behavior and show enrichments as unavailable unless an equivalent source is explicitly provided; do not silently switch databases.

| Data | When and how |
| --- | --- |
| Process metrics | Render the selected `/v1/processes` snapshot immediately and update with normal polling; no per-row requests. D3 fields are already parsed, while D4/D5 need model and parser mappings for core fields. |
| Gradle log metadata | On Gradle selection use the existing discovered `/v1/daemon-logs` metadata, or fetch it lazily if absent. It contains PID, version and path. The existing tail call discards metadata from `/v1/daemon-logs/{pid}/tail`; avoid an extra tail just for metadata. Metadata is PID-associated, not proof of a process incarnation. |
| Builds | On Gradle selection request bounded `/v1/builds?pid=…&start_time_ms=…&limit=20`, without `include_logs`; show three summaries. Require a positive, trustworthy start time and the identity safeguards below. Refresh while selected at a bounded cadence or on explicit refresh, not for every row or on every UI recomposition. |
| Host | Fetch `/v1/host` on selection and refresh at a bounded cadence while visible; label sample time. It is host-wide, not attributed to the selected process. |
| Capabilities | Fetch `/v1/capabilities` when its explanation is opened; cache for the core session and retry on failure or reconnection. |
| History | No selection-time request for D1–D6. If D7 is reconsidered, request bounded `/v1/processes/history?since_ms=…&limit=…` only when the assessment is opened, filter by PID **and** start time, and show insufficient data when the bounded response cannot support an assessment. The endpoint is global, so a limit can omit relevant samples. |

Run requests off the UI thread with bounded timeouts and independent loading, empty and error states. Key requests and results by `(pid, start_time_ms)` plus a selection generation; cancel or discard late results after reselection, process exit or PID reuse. Clear data on selection change. Preserve a clearly marked last-known snapshot on exit, but do not attach later PID-only log or build data to it. Existing `LiveViewModel` follows selection and tail results by PID alone, so implementation needs to strengthen both poll reconciliation and asynchronous result checks ([LiveViewModel](../../src/main/kotlin/io/github/cdsap/daemonitor/ui/live/LiveViewModel.kt), [WatcherService](../../src/main/kotlin/io/github/cdsap/daemonitor/WatcherService.kt)). Do not expose raw exception messages in UI.

## PID reuse and unavailable values

Reuse the core query rather than reimplementing correlation in Compose, but do not assume it proves a complete lifetime. The TUI calls `BuildsForDaemon(pid, startMs, 20)` ([commands](../../cored/internal/tui/commands.go), [client](../../cored/internal/client/client.go)); the core currently filters PID and `build.start_time >= daemonStartMs`, with no end bound ([server](../../cored/internal/api/server.go), [store](../../cored/internal/store/builds.go)). It excludes older incarnations for a current selection, but may include a later incarnation after exit. PID-only queries resolve the current log identity and return no rows if it is missing; they cannot establish an ended selection's identity. Before D1 ships, establish and test a core-supported incarnation boundary or stable identity for both active and ended selections; otherwise suppress results when correctness cannot be established. Do not fall back to PID-only matching or treat an unknown start as zero.

Represent missing, unsupported, permission-denied, stale and failed data as unavailable or `n/a`, never as a measured zero; preserve real zero values and available units. Keep errors distinct from an empty build list and from loading. Honor core availability and sample timestamps, and never substitute RSS for PSS or another platform-specific measurement. The current parser defaults missing RSS and start time to zero ([parser](../../cli/src/main/kotlin/io/github/cdsap/daemonitor/coreipc/GoCoreSnapshotParser.kt)); D4 and identity work must address those defaults where presented or used for correlation. Existing nullable metric rows already render unavailable ([LiveMonitorScreen](../../src/main/kotlin/io/github/cdsap/daemonitor/ui/live/LiveMonitorScreen.kt)).

## Evidence and follow-up validation

- The TUI groups Gradle metadata/builds/log separately and renders OS activity, native memory, JVM pools/JIT, host and capabilities from the snapshot and detail calls ([details view](../../cored/internal/tui/details_view.go), [commands](../../cored/internal/tui/commands.go)). Its history request uses a 30-minute window and limit 500; analysis filters by incarnation ([memory analysis](../../cored/internal/analysis/memory_growth.go)).
- Desktop already renders memory, CPU, JVM, GC, I/O, process fields and a separate log card; Visual uses a poll-time RSS timeline, and History provides filtering and build detail ([Live](../../src/main/kotlin/io/github/cdsap/daemonitor/ui/live/LiveMonitorScreen.kt), [Visual](../../src/main/kotlin/io/github/cdsap/daemonitor/ui/live/ProcessVisualScreen.kt), [History](../../src/main/kotlin/io/github/cdsap/daemonitor/ui/history/HistoryScreen.kt)). Core exposes richer fields than the current Kotlin parser maps ([core model](../../cored/internal/model/process.go)).
- Each implementation issue should add focused parser/source and UI/state tests for its fields and type gates, genuine zero versus unavailable, errors, selection races, ended processes and PID reuse as applicable. D1 also needs core API tests for lifetime boundaries and shared-core-owned wiring. D7 would need sparse/truncated history and insufficient-sample tests before an assessment can be trusted.
