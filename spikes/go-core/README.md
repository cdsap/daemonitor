# Spike: Go core + IPC clients

Exploratory prototype for a **native Daemonitor core** that owns process polling + sample
persistence and exposes a Unix-domain-socket HTTP API. CLI and desktop would be thin clients.

This is **not** production code and is **not** wired into the Gradle build.

## Why

Today CLI + desktop share a Kotlin/JVM core (JDK required, heavier RSS). The product idea:

1. `daemonitor-cored` — long-running Go collector + SQLite
2. `daemonitor-corectl` / Kotlin CLI / Compose desktop — clients over IPC

## Scope

| In | Out |
|----|-----|
| Poll Gradle-related processes (`gopsutil`) | Live heap Attach |
| Classifier + JVM args + Redactor parity | Packaging / Homebrew |
| SQLite sample insert + retention purge | Production auth |
| `/v1/health`, `/v1/processes`, `/v1/processes/history` | Replacing the shipping JVM app |
| Go client + zero-dep JVM Unix HTTP client | |
| Kotlin CLI `--core-socket` dual-run | |

## Run

```bash
cd spikes/go-core
go mod tidy
go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
go build -o bin/daemonitor-corectl ./cmd/daemonitor-corectl

./bin/daemonitor-cored                 # terminal 1
./bin/daemonitor-corectl processes     # terminal 2
./bin/daemonitor-corectl history
./bin/daemonitor-corectl health
```

Flags: `-socket`, `-db`, `-interval`, `-retention` (cored); `-since-min`, `-limit` (history).

Defaults live under `$TMPDIR` (`daemonitor-core.sock` / `daemonitor-core.sqlite`).

### JVM client (IPC from Java/Kotlin world)

```bash
javac clients/jvm/UnixHttpClient.java
java -cp clients/jvm UnixHttpClient "$TMPDIR/daemonitor-core.sock" /v1/processes
```

## Smoke / CI

```bash
./scripts/smoke.sh
```

GitHub Actions runs `go test` (including Unix-socket IPC integration) and builds binaries on
**ubuntu-latest**, **windows-latest**, and **macos-latest**. Full shell smoke (Go + JVM clients)
runs on Linux and macOS; Windows relies on the Go IPC integration test (Git Bash path mapping
breaks the shell/JDK smoke there).

## API sketch

```
GET /v1/health
GET /v1/processes
GET /v1/processes/history?since_ms=<epoch_ms>&limit=<n>
GET /v1/daemon-logs
GET /v1/daemon-logs/{pid}/tail
```

## Status

- Poll + classify works on macOS against live Gradle/Kotlin daemons
- SQLite retention path in place (`modernc.org/sqlite`, no CGO)
- JVM client proves desktop/CLI language can speak the same socket without FFI
- Kotlin CLI `--core-socket PATH` dual-runs: live process table from Go core
- **Parity slice:** classifier + JVM args + delta CPU + redactor + daemon log tails (see `docs/parity.md`)
- **Dual-run notes:** side-by-side checklist and cutover criteria in `docs/dual-run.md`

## Dual-run with Kotlin CLI

```bash
# terminal 1 — Go core
./bin/daemonitor-cored

# terminal 2 — Kotlin CLI reads processes + daemon logs from the core
./gradlew :cli:run --args="--plain --core-socket $TMPDIR/daemonitor-core.sock"
```

`--core-socket` is experimental: the CLI keeps its own SQLite store, but the live process
table and daemon log tails come from `daemonitor-cored` via Unix-socket HTTP. Build-event
parsing still runs in the JVM against the redacted Go tails.

Full comparison procedure and cutover gates: [`docs/dual-run.md`](docs/dual-run.md).

## Next

1. Port `DaemonLogParser` build events into Go if correlation should leave the JVM
2. Shared SQLite schema / packaging beyond the spike
