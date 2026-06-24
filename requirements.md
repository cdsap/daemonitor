# Gradle Process Watcher Spec

## Overview

Build activity is becoming harder to track during agentic AI workflows. Developers may have multiple IDEs, terminals, agents, and background tasks triggering Gradle builds at the same time. The goal of this project is to build a local Gradle Process Watcher that helps developers understand what Gradle activity is currently happening on their laptop and what happened recently.

The application should provide a Compose UI with two main views:

1. **Live Monitor**, focused on currently running Gradle processes.
2. **Historical View**, focused on past Gradle activity and resource usage.

## Goals

The tool should help answer questions like:

* Which Gradle builds are running right now?
* Which project or working directory started each build?
* Which Gradle daemons are active?
* How much memory and CPU are Gradle and related JVM processes using?
* What are the latest lines from the Gradle daemon logs?
* What Gradle activity happened earlier today?
* Did an AI agent trigger unexpected or repeated builds?
* Were there memory spikes, long-running builds, or daemon restarts?

## Non-Goals

The first version does not need to:

* Modify Gradle builds.
* Stop or restart Gradle daemons.
* Upload data to Develocity or any remote service.
* Provide deep task-level analysis.
* Parse full Build Scan data.
* Support team-wide reporting.

This should start as a local observability tool for a developer machine.

## Target Platform

Initial target:

* macOS laptops used for Android or JVM development.
* Gradle builds started from IDEs, terminals, scripts, or AI agents.

Future support may include Linux.

## Frontend

The frontend should be implemented using **Compose UI**, preferably Kotlin + Compose Desktop.

The app should have two primary sections:

### 1. Live Monitor

The Live Monitor should show currently running Gradle-related processes.

Each process row should include:

* Process ID.
* Process type, for example:

  * Gradle daemon.
  * Gradle wrapper process.
  * Kotlin daemon.
  * Test worker.
  * Java process likely related to Gradle.
* Command line.
* Working directory, when available.
* Detected project path.
* Start time.
* Runtime duration.
* CPU usage.
* Memory usage:

  * RSS memory.
  * Heap usage when available.
  * Max heap, when detectable.
* JVM arguments, especially:

  * `-Xmx`
  * `-Xms`
  * GC configuration
  * Gradle daemon flags
* Associated daemon log file, when detectable.
* Last daemon log lines.

The UI should make it easy to identify suspicious activity, such as:

* Multiple builds from the same project.
* Long-running Gradle daemons.
* High memory usage.
* Repeated daemon restarts.
* Builds triggered from unexpected folders.
* Several agents running Gradle at the same time.

### 2. Historical View

The Historical View should show what happened over time.

It should include:

* Timeline of detected Gradle processes.
* Build/session start and end times.
* Duration.
* Project path.
* Command line or inferred Gradle command.
* Peak memory usage.
* Average memory usage.
* Peak CPU usage.
* Last known process state.
* Exit detection, when available.
* Related daemon log snippets.
* Tags or inferred source, for example:

  * Terminal.
  * IDE.
  * AI agent.
  * Unknown.

The historical view should support filtering by:

* Time range.
* Project.
* Process type.
* Memory threshold.
* Long-running processes.
* Failed or suspicious sessions.
* AI-agent-related processes, when detectable.

## Data Collection

The watcher should periodically scan local processes and identify Gradle-related JVM activity.

Process detection should look for:

* `gradle`
* `gradlew`
* `GradleDaemon`
* `org.gradle.launcher.daemon`
* Kotlin daemon processes.
* Gradle test worker JVMs.
* Java processes with Gradle-related classpaths or arguments.

For each detected process, the collector should capture:

* PID.
* Parent PID.
* Command line.
* Working directory.
* Environment hints, where available.
* Start time.
* CPU usage.
* RSS memory.
* JVM flags.
* Process status.
* Open files, when useful and permitted.
* Associated Gradle user home, when detectable.

The watcher should also locate Gradle daemon logs, usually under:

```text
~/.gradle/daemon/<gradle-version>/daemon-<pid>.out.log
```

For each active daemon, the app should tail the last lines of the corresponding log file.

## Data Storage

Historical data should be stored locally.

Recommended storage:

* SQLite for structured process/session history.
* Plain text or compressed log snippets for daemon log samples.

Suggested tables:

### process_samples

Stores periodic snapshots.

Fields:

* id
* timestamp
* pid
* parent_pid
* process_type
* command_line
* working_directory
* project_path
* cpu_percent
* rss_memory_mb
* heap_memory_mb
* max_heap_mb
* status

### process_sessions

Stores aggregated process lifetime information.

Fields:

* session_id
* pid
* process_type
* command_line
* working_directory
* project_path
* start_time
* end_time
* duration_seconds
* peak_memory_mb
* avg_memory_mb
* peak_cpu_percent
* inferred_source
* final_status

### daemon_logs

Stores selected daemon log snippets.

Fields:

* id
* timestamp
* pid
* daemon_log_path
* log_tail
* detected_errors
* detected_warnings

## Session Detection

The app should group repeated samples for the same PID into a process session.

A session starts when a new Gradle-related process is detected.

A session ends when:

* The process disappears.
* The PID is no longer active.
* The process command line changes unexpectedly.

The app should retain enough historical information to reconstruct what happened during the session.

## Source Detection

The watcher should try to infer what started the build.

Possible sources:

* Terminal.
* IntelliJ / Android Studio.
* VS Code / Cursor.
* Claude Code.
* Codex.
* Other AI agent.
* Unknown.

This can be inferred from:

* Parent process name.
* Command line.
* Working directory.
* Environment variables.
* Known agent process names.
* Terminal process ancestry.

This does not need to be perfect in the first version. The UI should show the confidence level or mark the source as unknown when uncertain.

## UI Requirements

### Live Monitor Screen

The Live Monitor should include:

* A summary header:

  * Number of active Gradle processes.
  * Total memory used.
  * Highest memory process.
  * Number of active projects.
* A process table.
* A detail panel for the selected process.
* A daemon log tail panel.

The detail panel should show:

* Full command line.
* Working directory.
* JVM args.
* Memory trend for the selected process.
* Last daemon log lines.

### Historical Screen

The Historical screen should include:

* Timeline chart.
* Process/session table.
* Filters.
* Detail panel for selected historical session.
* Memory and CPU trend for selected session.
* Related log snippets.

Useful default filters:

* Today.
* Last hour.
* Last 24 hours.
* High memory processes.
* Long-running processes.
* Unknown source.
* AI-agent-triggered builds.

## Alerts and Highlighting

The app should visually highlight suspicious or useful events:

* Gradle process using more than a configurable memory threshold.
* Multiple Gradle builds running for the same project.
* Daemon restart detected.
* Process running longer than expected.
* Repeated builds from the same AI agent.
* Kotlin daemon using unexpectedly high memory.
* Test worker near memory limit.

First version can use UI badges instead of system notifications.

## Configuration

The app should allow users to configure:

* Polling interval.
* History retention period.
* Memory warning threshold.
* Memory critical threshold.
* Number of daemon log lines to keep.
* Gradle user home location.
* Projects or folders to ignore.
* Process types to show or hide.

Suggested defaults:

* Polling interval: 2 seconds.
* Log tail lines: 100.
* History retention: 7 days.
* Memory warning: 4 GB RSS.
* Memory critical: 8 GB RSS.

## Privacy

All data should remain local by default.

The app should not upload process data, command lines, logs, or project paths unless explicitly configured in the future.

Because command lines and logs may contain sensitive information, the app should make this clear in the UI.

## MVP Scope

The first implementation should include:

* Local process scanner.
* Detection of Gradle daemon and Gradle wrapper processes.
* Live Monitor UI.
* Memory and CPU metrics.
* Daemon log tailing.
* SQLite storage.
* Basic Historical View.
* Session start/end detection.
* Simple filters by project and time range.

## Future Enhancements

Possible future improvements:

* System notifications for suspicious Gradle activity.
* Better AI agent source detection.
* Build command reconstruction.
* Integration with Build Scan links when available.
* Local-only report export.
* Comparison between manual builds and agent-triggered builds.
* Memory leak detection across daemon lifetime.
* Timeline correlation between IDE, terminal, and agent activity.
* Support for Linux.
* Optional Develocity integration.
* Optional CLI mode.

## Success Criteria

The project is successful when a developer can open the app and quickly understand:

* What Gradle processes are running right now.
* Which project each process belongs to.
* How much memory Gradle activity is consuming.
* Whether an AI agent or IDE is triggering unexpected builds.
* What happened earlier in the day.
* Which daemon logs explain the latest Gradle behavior.

The main value is local visibility into Gradle activity during increasingly automated and agentic development workflows.

## Deferred / Open Questions

### From 2026-06-24 review

Findings from a multi-persona document review (coherence, feasibility, product-lens, design-lens, security-lens, scope-guardian, adversarial). Each entry is a concern to resolve during planning, not yet an applied change.

#### Critical — load-bearing assumptions

- **PID-keyed sessions conflate daemon lifetime with builds** — Session Detection (P0, adversarial, confidence 75)

  A Gradle daemon is long-lived and serves many sequential builds over hours, so keying a "session" to a PID produces one giant multi-hour record per daemon rather than per-build records. This directly defeats "Which builds are running right now?", "What activity happened earlier today?", and "Did an AI agent trigger repeated builds?" — per-build duration, peak memory, and source attribution all become meaningless aggregates. Build boundaries must come from daemon-log parsing or Tooling-API connection events, not process polling.

  <!-- dedup-key: section="session detection" title="pidkeyed sessions conflate daemon lifetime with builds" evidence="the app should group repeated samples for the same pid into a process session" -->

- **Heap and max-heap not OS-observable for external JVMs** — Data Collection (P1, feasibility + adversarial + scope-guardian, confidence 75)

  `heap_memory_mb` / `max_heap_mb` and the memory-trend and "near memory limit" features assume live heap visibility, but from outside the JVM on macOS only RSS is available. Real heap needs JMX/jstat/agent attach (same-UID, heavyweight, and daemons don't expose JMX by default); max-heap is only knowable when `-Xmx` is explicit on the command line. Either scope to RSS + parsed `-Xmx`, or specify an attach mechanism — otherwise heap columns are empty for most rows.

  <!-- dedup-key: section="data collection" title="heap and maxheap not osobservable for external jvms" evidence="heap usage when available" -->

- **Source detection breaks when daemons reparent to launchd** — Source Detection (P1, adversarial + feasibility + product-lens, confidence 75)

  Attribution leans on parent-process ancestry and environment variables, but daemons detach and reparent to launchd (severing the link to Terminal/IDE/agent), and macOS won't expose env vars of processes you don't own. The headline "did an AI agent trigger this?" will frequently resolve to "Unknown" for exactly the processes it most wants to attribute. Attribute at build-start via the connecting client (gradlew launcher / Tooling-API client) correlated through the daemon log, not via the daemon's own ancestry.

  <!-- dedup-key: section="source detection" title="source detection breaks when daemons reparent to launchd" evidence="parent process name" -->

- **2-second poll misses short builds entirely** — Configuration (P1, adversarial, confidence 75)

  Wrapper/launcher processes, up-to-date no-op builds, and short agentic builds start and exit between 2s polls and are never sampled — undercounting the very "repeated agent builds" signal the tool exists for, and making peak CPU/memory a coarse undersample. State the limitation and use daemon-log parsing as the authoritative build-event source.

  <!-- dedup-key: section="configuration" title="2second poll misses short builds entirely" evidence="polling interval 2 seconds" -->

#### Security and data handling

- **Command lines stored unredacted may expose secrets** — Data Collection / Data Storage (P0, security-lens, confidence 100)

  Tokens, signing passwords, and API keys passed as `-P`/`-D` flags (e.g. `./gradlew publish -PsonatypePassword=…`) are captured verbatim into `command_line` and persisted for 7 days; anything that reads the SQLite file retrieves plaintext credentials. Add a redaction pass (denylist of secret-shaped keys) before persistence — in Data Collection/Storage, not only as a UI notice.

  <!-- dedup-key: section="data collection data storage" title="command lines stored unredacted may expose secrets" evidence="for each detected process the collector should capture command line" -->

- **Environment-variable capture has no redaction requirement** — Data Collection (P0, security-lens, confidence 100)

  "Environment hints, where available" is undefined and could store full values like `AWS_SECRET_ACCESS_KEY` or `GITHUB_TOKEN` for 7 days. Restrict capture to env-var names (not values), or define an allowlist of non-sensitive names.

  <!-- dedup-key: section="data collection" title="environmentvariable capture has no redaction requirement" evidence="environment hints where available" -->

- **Daemon log snippets stored unredacted** — Data Storage / daemon_logs (P1, security-lens, confidence 100)

  Daemon logs can contain property values, credentialed repository URLs, and paths; storing up to 100 lines per poll for 7 days in `log_tail` exposes them. Apply pattern-based redaction before storage, or store only structured metadata (error/warning counts, timestamps).

  <!-- dedup-key: section="data storage daemon_logs" title="daemon log snippets stored unredacted" evidence="daemon_logs log_tail" -->

- **SQLite file has no encryption or permission requirement** — Data Storage / Privacy (P1, security-lens, confidence 100)

  No `chmod 600` and no encryption decision means any process under the same UID can read 7 days of command lines, env hints, log tails, and project paths. "Stays local" and "make clear in UI" are not technical controls. Create the DB and log directory owner-only (0600/0700) and state whether SQLCipher is in scope.

  <!-- dedup-key: section="data storage privacy" title="sqlite file has no encryption or permission requirement" evidence="historical data should be stored locally" -->

- **Process scanning not scoped to current user** — Data Collection (P1, security-lens, confidence 75)

  Scanning isn't constrained to the current user's processes; granting elevated privileges to read other users' command lines/open files would make the tool a high-value credential-reading target. Scope scanning to effective-UID match and explicitly prohibit elevated privileges in the MVP.

  <!-- dedup-key: section="data collection" title="process scanning not scoped to current user" evidence="the watcher should periodically scan local processes and identify gradlerelated jvm activity" -->

- **No retention purge guarantee for stored sensitive data** — Configuration / Privacy (P2, security-lens, confidence 75)

  7-day retention is a default setting but no purge is required to run; if the app isn't running, credential-bearing data accumulates past the window. Require a hard-delete purge on startup, not just during active polling.

  <!-- dedup-key: section="configuration privacy" title="no retention purge guarantee for stored sensitive data" evidence="history retention 7 days" -->

- **Open-files capture scope is undefined** — Data Collection (P2, security-lens, confidence 75)

  "Open files, when useful and permitted" has no scope; capturing them can record keystore and `gradle.properties` paths, revealing the on-disk layout of secrets. Limit to Gradle log/user-home paths and exclude credential stores (`*.jks`, `*.p12`, `gradle.properties`).

  <!-- dedup-key: section="data collection" title="openfiles capture scope is undefined" evidence="open files when useful and permitted" -->

#### Scope discipline

- **MVP section silently imports full-spec scope** — MVP Scope (P0, scope-guardian, confidence 100)

  The 7 alert types, 8 config options, 6-tool source detection, timeline/trend charts, heap fields, the third SQLite table, and 7 filter presets are not marked "post-MVP", so they become de-facto v1 requirements far beyond the MVP bullet list and success criteria. Mark every section MVP / Post-MVP and make the MVP list the authoritative ceiling.

  <!-- dedup-key: section="mvp scope" title="mvp section silently imports fullspec scope" evidence="the first implementation should include local process scanner" -->

- **MVP defers the only differentiating capabilities** — MVP Scope (P1, product-lens, confidence 75)

  The commodity process-table is in the MVP while AI-agent attribution — the spec's stated reason to exist — is deferred, so the first shippable version is exactly what `jps`/Activity Monitor already deliver. Pull a minimal, honest version of source attribution into the MVP, or reconsider whether v1 is worth building over existing tools.

  <!-- dedup-key: section="mvp scope" title="mvp defers the only differentiating capabilities" evidence="better ai agent source detection" -->

- **Problem premise asserted, not demonstrated; existing tools overlap** — Overview / Live Monitor (P1, product-lens, confidence 75)

  The always-on app is justified by a premise stated as fact with no incident or metric behind it, and the core live-monitor columns are already provided by Activity Monitor, `jps -lvm`, `ps`, `gradle --status`, Build Scans, and Develocity. Anchor the spec to a real incident and add a "why not Activity Monitor / jps / gradle --status / Develocity" section that defines the actual gap the MVP fills.

  <!-- dedup-key: section="overview live monitor" title="problem premise asserted not demonstrated existing tools overlap" evidence="build activity is becoming harder to track during agentic ai workflows" -->

- **Heap/max-heap, 6-tool source inference, 7 alerts, 8 config options, charts, 7 filters exceed MVP** — multiple sections (P1, scope-guardian, confidence 75–100)

  Several detailed subsystems are disproportionate to the "start as a local observability tool" / "does not need to be perfect" framing and aren't traceable to the success criteria. Candidate v1 cuts: RSS-only memory; Terminal/IDE/Unknown source via parent name only; two highlight conditions (high RSS, multiple builds same project); hard-coded defaults instead of a config surface; a sortable table instead of timeline/trend charts; project + time-range filters only.

  <!-- dedup-key: section="multiple" title="heapmaxheap 6tool source inference 7 alerts 8 config options charts 7 filters exceed mvp" evidence="memory usage rss memory heap usage when available max heap when detectable" -->

#### Design specification gaps (design-lens)

- **Undefined UI states and interactions across both screens** — UI Requirements (P0–P1, design-lens, confidence 75–100)

  Multiple under-specified areas would lead two implementers to build incompatible UIs: empty state (no processes / permission denied), detail-panel open/dismiss trigger and behavior when the selected process exits, memory-trend chart axes/range/metric, timeline-chart type and layout, process-table column density (13+ fields/row) and overflow, navigation model between the two views, alert-badge lifecycle (auto-clear vs sticky), source-confidence UI treatment, and filter interaction (live vs apply, AND/OR). Specify each before building the UI.

  <!-- dedup-key: section="ui requirements" title="undefined ui states and interactions across both screens" evidence="a process table a detail panel for the selected process a daemon log tail panel" -->

- **AI-slop risk: generic dashboard with no information hierarchy** — UI Requirements (P2, design-lens, confidence 75)

  A summary header + table + detail + log panel reads as a generic system monitor; the spec never encodes the product's differentiated value (detecting AI-agent and multi-agent parallel builds) into the visual hierarchy, giving PID the same weight as "agent triggered 4 builds in 90s". Make suspicious-activity signals the most prominent element and source/agent a first-class column.

  <!-- dedup-key: section="ui requirements" title="aislop risk generic dashboard with no information hierarchy" evidence="the ui should make it easy to identify suspicious activity such as" -->

#### Other correctness

- **"Command line changes" session-end trigger is spurious** — Session Detection (P2, adversarial, confidence 75)

  A process's argv is immutable for the life of the PID, so this trigger either never fires or fires on measurement artifacts (truncation/encoding), causing phantom session ends. Remove it; if the intent was PID reuse, key on PID + start-time.

  <!-- dedup-key: section="session detection" title="command line changes sessionend trigger is spurious" evidence="a session ends when" -->

- **CPU% needs interval sampling, not a single read** — Data Collection / Historical View (P2, feasibility, confidence 75)

  `ps %cpu` is a lifetime average, so a single read per poll can't produce a meaningful live or peak CPU value. Define `cpu_percent` / `peak_cpu_percent` as deltas of cumulative CPU time across consecutive samples.

  <!-- dedup-key: section="data collection historical view" title="cpu needs interval sampling not a single read" evidence="cpu usage" -->

#### FYI (advisory, confidence 50)

- **Compose Desktop may be heavier than the need** — Frontend (P2, product-lens, confidence 50)

  An always-running Compose Desktop window is itself a JVM consuming memory on the laptop whose strain is the concern; a CLI or menubar widget could deliver the glance value at far less surface area. "Optional CLI mode" is listed as future — the inverse of a lean first cut. Briefly justify the GUI form factor.

  <!-- dedup-key: section="frontend" title="compose desktop may be heavier than the need" evidence="the frontend should be implemented using compose ui preferably kotlin compose desktop" -->

- **Depth-vs-simplicity identity bet is implicit** — Historical View (P3, product-lens, confidence 50)

  Sessions, samples, timelines, trend charts, 7 filters, and 7 alerts layered onto a "quickly understand" success criterion is a depth-over-simplicity bet the doc never acknowledges. State whether this is a glanceable monitor or a forensic tool, and cut v1 breadth to match.

  <!-- dedup-key: section="historical view" title="depthvssimplicity identity bet is implicit" evidence="the project is successful when a developer can open the app and quickly understand" -->

- **Daemon log path mixes many builds' output** — Data Collection (P2, adversarial, confidence 50)

  `daemon-<pid>.out.log` accumulates output from many sequential builds on one daemon, so a per-session `log_tail` will mix builds, making "which daemon logs explain the latest behavior" unreliable; the path/format also varies by Gradle version. Specify per-build log segmentation (timestamp/offset bookmarking).

  <!-- dedup-key: section="data collection" title="daemon log path mixes many builds output" evidence="gradledaemonversiondaemonpidoutlog" -->

- **"Daemon restart detected" alert needs a cross-PID identity** — Alerts and Highlighting (P2, adversarial, confidence 50)

  Restart detection requires a daemon identity that survives across PIDs, but the data model keys only on PID. Define a stable key (project path + Gradle user home + Gradle version) so PID churn under one identity can be counted as a restart.

  <!-- dedup-key: section="alerts and highlighting" title="daemon restart detected alert needs a crosspid identity" evidence="repeated daemon restarts" -->
