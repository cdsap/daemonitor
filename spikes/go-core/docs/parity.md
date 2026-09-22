# Go core ↔ Kotlin collector parity

Status as of the `feat/go-core-build-aggregator` slice.

## Classifier

Go `poll.Classify` mirrors `GradleProcessClassifier` fixtures from
`core/src/test/kotlin/.../GradleProcessClassifierTest.kt` (daemon, wrapper shell + jar form,
Kotlin daemon, test worker, JAVA_GRADLE_RELATED, cache-path false positive, unrelated).

Covered by `spikes/go-core/internal/poll/poll_test.go`.

## JVM args / automation / redaction

| Concern | Kotlin | Go |
|---------|--------|-----|
| `-Xmx` / `-Xms` / `-XX:+Use*GC` | `JvmArgParser` | `poll.ParseJVMArgs` |
| `--non-interactive` / `--console=plain` | `InvocationFlags` | `poll.IsNonInteractive` |
| Command / token / log redaction | `Redactor` (KTD-7) | `poll.RedactCommandLine` / `RedactToken` / `RedactLogLine` |
| First CPU sample | `null` until prior exists | `cpu_percent: null` until prior exists |
| CPU formula | delta cpu / wall / logical CPUs | same (gopsutil Times) |
| Wrapper `projectPath` | cwd when type is wrapper | same |

Redactor fixtures live in `spikes/go-core/internal/poll/redactor_test.go` (mirrors `RedactorTest.kt`).

## Daemon logs

| Concern | Kotlin | Go |
|---------|--------|-----|
| Discover `daemon-<pid>.out.log` | `DaemonLogWatcher.discover` | `logs.Watcher.Discover` |
| Incremental redacted tail | `DaemonLogWatcher.readNewLines` | `logs.Watcher.Poll` + `TailFor` |
| Build-event parse (U3) | `DaemonLogParser` | `logs.ParseLine` / `ParseLines` (events on `/tail`) |
| Build aggregation (U5) | `BuildAggregator` | `builds.Aggregator` + `GET /v1/builds` |
| Source / agent (U6) | `SourceDetector` / `AgentDetector` | `builds.DetectSource` / `DetectAgent` |
| IPC | (in-process) | `GET /v1/daemon-logs`, `/v1/daemon-logs/{pid}/tail`, `/v1/builds` |

Go lists every discovered log, but only continuously tails **active** `GRADLE_DAEMON` PIDs from the process snapshot (large `~/.gradle/daemon` trees). `TailFor` lazily seeds inactive PIDs on demand. Tail responses include `events` parsed from the retained redacted lines. Confirmed builds are stored in the spike SQLite DB and listed via `/v1/builds`.

## Still divergent

- Live JVM heap Attach / JMX (Kotlin only)
- Shared SQLite schema with the desktop app (Go spike `builds` table is separate from app `WatcherDatabase`)
- Kotlin CLI `--core-socket` still re-parses/aggregates builds in-process unless a future client consumes `/v1/builds`

## Dual-run check

Procedure, comparison checklist, and cutover criteria: [`dual-run.md`](dual-run.md).

```bash
cd spikes/go-core && go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
./bin/daemonitor-cored &
./gradlew :cli:run --args="--plain --core-socket $TMPDIR/daemonitor-core.sock"
```

Compare TYPE / PID / RSS / HEAP LIMIT / CPU / daemon log count against a normal JVM CLI
session on the same machine.
