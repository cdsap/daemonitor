# Dual-run migration notes (JVM collector ↔ Go core)

Status as of the `feat/go-core-kotlin-log-tails` slice.

These notes capture what dual-run proves today, how to compare sessions, and what must
be true before the shipping CLI/desktop can drop the in-process collector for a given
surface.

## What dual-run means

| Surface | Default (JVM) | `--core-socket PATH` |
|---------|---------------|----------------------|
| Live process table | `ProcessCollector` (OSHI) | `GoCoreProcessSource` → `GET /v1/processes` |
| Daemon log discover / tail | `DaemonLogWatcher` | `GoCoreDaemonLogSource` → `/v1/daemon-logs` |
| Build-event parse / correlation | JVM (`DaemonLogParser` + `BuildAggregator`) | **still JVM** (diffs Go redacted tails) |
| Sample / build SQLite | App `WatcherDatabase` | same app DB (Go spike DB is separate) |
| Live heap Attach / JMX | JVM only | always `null` from Go |

`--core-socket` is experimental. The CLI stderr banner says so when the flag is set.

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
```

Optional Go-only cross-check (no CLI UI):

```bash
./bin/daemonitor-corectl -socket "${TMPDIR:-/tmp}/daemonitor-core.sock" processes
./bin/daemonitor-corectl -socket "${TMPDIR:-/tmp}/daemonitor-core.sock" logs
./bin/daemonitor-corectl -socket "${TMPDIR:-/tmp}/daemonitor-core.sock" log-tail <pid>
```

See also `docs/parity.md` for field-level mapping.

## Comparison checklist

Mark each row after a live dual-run. “Honest” means differences are understood, not that
values are bit-identical.

| Check | How to judge | Expected today |
|-------|--------------|----------------|
| PID set for Gradle-related types | Same daemons / wrappers / Kotlin daemons appear | Match (classifier parity) |
| `type` label | Same `ProcessType` name per PID | Match |
| RSS MB | Same order of magnitude; may differ by sample timing | Near-match (±1 sample window) |
| Heap limit (`-Xmx`) | Same parsed MB when args present | Match |
| CPU % | First sample `null`/absent on both; later samples trend together | Near-match (delta CPU) |
| `automated` / non-interactive | Same for wrapper invocations | Match |
| Command line redaction | Secrets masked identically (KTD-7) | Match |
| Daemon log **count** | Discover lists same `daemon-<pid>.out.log` set | Match (paths under `~/.gradle/daemon`) |
| Daemon log **tail** content | Redacted lines agree for active daemon PIDs | Near-match (Go continuous-tails only active `GRADLE_DAEMON`; lazy seed on demand) |
| Build rows in app DB | Dual-run still inserts via JVM parser | Should appear; timestamps may differ vs control if tail windows differ |
| Live heap | Go path always empty | **Divergent by design** |

## Known honest gaps (do not block dual-run demos)

1. **No live heap from Go** — Attach/JMX stays Kotlin-only until a future design.
2. **Two SQLite worlds** — `daemonitor-cored` keeps a spike DB for `/v1/processes/history`;
   the CLI/desktop keep writing the app schema. History UI is not served from Go.
3. **Go log poll scope** — continuous tail is limited to active `GRADLE_DAEMON` PIDs so large
   `~/.gradle/daemon` trees stay cheap; inactive PIDs seed on `TailFor` / first CLI read.
4. **Dual SQLite for builds** — Go aggregates into the spike DB (`/v1/builds`); the app CLI still
   writes its own build rows when dual-running. Prefer Go builds for cutover only after schemas
   align or the client switches to `/v1/builds`.
5. **Sample timing** — 2s poll defaults on both sides; RSS/CPU can disagree by one interval
   without indicating a classifier bug.
6. **Windows smoke** — shell/JDK Unix-socket smoke is Linux/macOS; Windows relies on Go IPC
   integration tests (see spike README).

## Cutover criteria (per surface)

Promote a surface out of “experimental dual-run” only when all apply:

- [ ] Comparison checklist rows for that surface stay honest across macOS + Linux on at
      least one real multi-project Gradle session
- [ ] Redaction fixtures still pass on both sides (`RedactorTest` / `redactor_test.go`)
- [ ] Failure mode is clear when the socket is missing or `daemonitor-cored` dies
      (CLI already requires the socket file for Go sources)
- [ ] No reliance on Go spike SQLite for shipping retention/UI (or schema is deliberately
      shared and migrated)
- [ ] Product accepts missing live heap **or** heap has a non-JVM story

Until then, keep `--core-socket` off by default.

## Suggested next engineering slices

1. **Optional:** have Kotlin dual-run consume `/v1/builds` (skip JVM `BuildAggregator`)
2. **Shared SQLite / packaging** — only after process + log + build dual-run stay honest and the
   product commits to a core binary distribution story
3. Keep stacking PRs through `#215` before landing follow-ons on `main`
