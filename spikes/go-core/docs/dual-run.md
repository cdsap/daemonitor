# Dual-run migration notes (JVM collector ↔ Go core)

Status as of post-merge Go spike on `main` (aggregator + log tails + Kotlin dual-run clients).

These notes capture what dual-run proves today, how to compare sessions, and what must
be true before the shipping CLI/desktop can drop the in-process collector for a given
surface.

## What dual-run means

| Surface | Default (JVM) | `--core-socket PATH` |
|---------|---------------|----------------------|
| Live process table | `ProcessCollector` (OSHI) | `GoCoreProcessSource` → `GET /v1/processes` |
| Daemon log discover / tail | `DaemonLogWatcher` | `GoCoreDaemonLogSource` → `/v1/daemon-logs` |
| Build-event parse / correlation | JVM (`DaemonLogParser` + `BuildAggregator`) | Go aggregates into spike DB; `--core-socket` imports via `GET /v1/builds` (JVM re-agg skipped) |
| Sample / build SQLite | App `WatcherDatabase` | shared `watcher.db` by default (cored + app-dir paths); HTTP build copy only when DBs differ |
| Live heap Attach / JMX | JVM only | always `null` from Go |

`--core-socket` is experimental. CLI, desktop, and `--headless` share the same wiring
(`wireGoCore`); stderr/log prints a banner when the flag is set.

## Side-by-side procedure

Run both sessions against the **same machine** while a Gradle build is active (daemon up).

```bash
# Terminal A — Go core
cd spikes/go-core
go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
./bin/daemonitor-cored -socket "${TMPDIR:-/tmp}/daemonitor-core.sock"

# Terminal B — Kotlin CLI via Go core
./gradlew :cli:run --args="--plain --core-socket ${TMPDIR:-/tmp}/daemonitor-core.sock"

# Terminal C — Kotlin CLI fully in-process (control)
./gradlew :cli:run --args="--plain"

# Optional — desktop / headless against the same socket
./gradlew run --args="--core-socket ${TMPDIR:-/tmp}/daemonitor-core.sock"
./gradlew run --args="--headless --core-socket ${TMPDIR:-/tmp}/daemonitor-core.sock"
```

Automated Linux honesty (native Linux, or `--docker` from macOS):

```bash
./spikes/go-core/scripts/dual-run-linux.sh          # Linux host
./spikes/go-core/scripts/dual-run-linux.sh --docker # macOS → Linux container
```

Optional Go-only cross-check (no CLI UI):

```bash
./bin/daemonitor-corectl -socket "${TMPDIR:-/tmp}/daemonitor-core.sock" processes
./bin/daemonitor-corectl -socket "${TMPDIR:-/tmp}/daemonitor-core.sock" logs
./bin/daemonitor-corectl -socket "${TMPDIR:-/tmp}/daemonitor-core.sock" log-tail <pid>
./bin/daemonitor-corectl -socket "${TMPDIR:-/tmp}/daemonitor-core.sock" builds
```

See also `docs/parity.md` for field-level mapping.

## Comparison checklist

Mark each row after a live dual-run. “Honest” means differences are understood, not that
values are bit-identical.

| Check | How to judge | Expected today | 2026-09-22 macOS | 2026-09-22 Linux (Docker aarch64) |
|-------|--------------|----------------|------------------|-----------------------------------|
| PID set for Gradle-related types | Same daemons / wrappers / Kotlin daemons appear | Match (classifier parity) | **Honest** — 10/10 common PIDs; transient wrappers may appear on one side only | **Honest** — common=2; Go also saw 2 extra short-lived PIDs |
| `type` label | Same `ProcessType` name per PID | Match | **Pass** — 10/10 | **Pass** — 2/2 |
| RSS MB | Same order of magnitude; may differ by sample timing | Near-match (±1 sample window) | **Pass** — max \|Δ\| = 5 MB, median 0 | **Pass** — max \|Δ\| = 1 MB, median 1 |
| Heap limit (`-Xmx`) | Same parsed MB when args present | Match | **Pass** — including `12288` / `null` when absent | **Pass** — 2/2 (`512`) |
| CPU % | First sample `null`/absent on both; later samples trend together | Near-match (delta CPU) | **Honest** — Go had warmed values; JVM first-sample `null` still seen | **Honest** — Go first sample `-` until warm |
| `automated` / non-interactive | Same for wrapper invocations | Match | **Pass** on sampled rows (`false`) | **n/a** — daemon-only sample |
| Command line redaction | Secrets masked identically (KTD-7) | Match | **Pass** on fixtures; live tail had no unmasked `-Ptoken=` | **Pass** fixtures; live parse no longer SOEs on multi-KB cmdlines |
| Daemon log **count** | Discover lists same `daemon-<pid>.out.log` set | Match (paths under `~/.gradle/daemon`) | **Honest gap** — Go listed 713 logs under `~/.gradle`; 3/6 live daemons unmatched (alternate Gradle user homes under `/private/tmp/...`) | **Pass** — live daemon log matched under `/root/.gradle/daemon` |
| Daemon log **tail** content | Redacted lines agree for active daemon PIDs | Near-match | **Pass** for matched PID — 100 lines + U3 events (`busy_mark`, `build_start`, …) | **Pass** — discover + builds path exercised via corectl |
| Build rows | Go spike `/v1/builds` and/or JVM app DB | Should appear | **Pass (Go)** — builds aggregated (`SUCCESS` / `COMPLETED_NO_OUTCOME` / `FAILED`, source `IDE`) | **Pass (Go)** — `COMPLETED_NO_OUTCOME` after `./gradlew help` |
| Live heap | Go path always empty | **Divergent by design** | **Confirmed** — no live heap on Go snapshots | **Confirmed** — honesty harness disables Attach |

### 2026-09-22 session notes (macOS)

- Compared `ProcessCollector` (one-shot test dump) vs `daemonitor-corectl processes` with live Gradle 8.x/9.x daemons.
- Go `/v1/builds` populated after local compile activity against daemon `30246`.
- **Was blocking Terminal B (`--core-socket` CLI):** `GoCoreDaemonLogSource.discover()` returns the full `~/.gradle/daemon` tree (700+ logs); older `PollMonitoring` HTTP-tailed every path and SOE'd. Fixed in [#221](https://github.com/cdsap/daemonitor/issues/221) — Kotlin now only `readNewLines` for live ∪ previously known `GRADLE_DAEMON` PIDs (mirrors Go `Poll(active)`).
- Keep `--core-socket` off by default until remaining cutover items below are green.

### 2026-09-22 session notes (Linux)

- Ran `./spikes/go-core/scripts/dual-run-linux.sh --docker` (Ubuntu jammy aarch64 container on a macOS Docker host).
- Opt-in harness: `DualRunHonestyTest` (`DAEMONITOR_DUAL_RUN=1`) compares `ProcessCollector` to `GoCoreProcessSource` for common PIDs.
- **Parser fix:** `GoCoreSnapshotParser.stringField` no longer uses nested regex on `command_line` (StackOverflowError on multi-KB GradleDaemon argv). Hand-rolled JSON string decode instead.
- Go `/v1/builds` showed `COMPLETED_NO_OUTCOME` for the warming `./gradlew help` invocation; daemon log discover matched the live PID under `/root/.gradle/daemon`.

## Known honest gaps (do not block dual-run demos)

1. **No live heap from Go** — Attach/JMX stays Kotlin-only until a future design.
2. **Shared SQLite by default** — `daemonitor-cored` and the CLI default to the same app-dir
   `watcher.db` + socket. Matching `db_path` skips JVM sample writes and HTTP build import.
   Pass distinct `-db`/`--db` for an isolated core DB (HTTP copy remains). See
   [`sqlite-packaging.md`](sqlite-packaging.md).
3. **Go log poll scope** — continuous tail is limited to active `GRADLE_DAEMON` PIDs so large
   `~/.gradle/daemon` trees stay cheap; inactive PIDs seed on `TailFor` / first CLI read.
4. **Alternate Gradle user homes** — processes whose logs live outside `~/.gradle/daemon` are
   classified but have no matching Go log discovery entries.
5. **Sample timing** — RSS/CPU can disagree by one interval without indicating a classifier bug.
6. **Windows smoke** — shell/JDK Unix-socket smoke is Linux/macOS; Windows relies on Go IPC
   integration tests (see spike README).

## Cutover criteria (per surface)

Promote a surface out of “experimental dual-run” only when all apply:

- [x] Process table checklist honest on at least one live macOS session (2026-09-22)
- [x] Same process/log honesty repeated on Linux (2026-09-22 Docker aarch64)
- [x] `--core-socket` CLI poll stays healthy on large `~/.gradle/daemon` trees (active-PID filter, #221)
- [x] Redaction fixtures still pass on both sides (`RedactorTest` / `redactor_test.go`) —
      reconfirmed 2026-09-23 (identical fixture set; both suites green)
- [x] Failure mode is clear when the socket is missing or `daemonitor-cored` dies
      (`GoCoreUnavailableException` — missing path vs unreachable/stale sock; CLI prints the message and keeps the last good frame)
- [x] No reliance on Go spike SQLite for shipping retention/UI (or schema is deliberately
      shared and migrated) — app-dir defaults + shared-DB write skip (#230/#packaging)
- [ ] Product accepts missing live heap **or** heap has a non-JVM story

Until then, keep `--core-socket` off by default.

## Suggested next engineering slices

1. Product decision on missing live heap for Go cutover (last open cutover gate)
2. Optional: multi-arch `daemonitor-cored` cross-compile matrix
3. Optional: wire `DualRunHonestyTest` / `dual-run-linux.sh` into CI (Linux-only)

### 2026-09-23 redaction reconfirm

```bash
./gradlew :core:test --tests 'io.github.cdsap.daemonitor.domain.RedactorTest'
cd spikes/go-core && go test ./internal/poll/ -run Redact -count=1
```

Go `redactor_test.go` mirrors the seven Kotlin cases (command-line `-P`/`-D`, long-option,
URL credentials, safe tokens, tab-separated argv, key-substring non-over-redact, log line).
Both passed; no fixture gaps.