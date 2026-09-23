# Go core + IPC clients

Native Daemonitor core that owns process polling + sample persistence and exposes a
Unix-domain-socket HTTP API. The Kotlin CLI can attach with `--core-socket`.

Still under `spikes/go-core` while release/Homebrew and desktop bundling catch up — CLI
`installDist` / `distZip` already embed a host-arch `daemonitor-cored` when Go is available.

## Why

Today CLI + desktop share a Kotlin/JVM core (JDK required, heavier RSS). The product idea:

1. `daemonitor-cored` — long-running Go collector + SQLite
2. `daemonitor-corectl` / Kotlin CLI / Compose desktop — clients over IPC

## Scope

| In | Out |
|----|-----|
| Poll Gradle-related processes (`gopsutil`) | Live heap Attach |
| Classifier + JVM args + Redactor parity | Homebrew / desktop bundle (next) |
| SQLite sample insert + retention purge | Production auth |
| `/v1/health`, `/v1/processes`, `/v1/processes/history` | Replacing the shipping JVM app |
| Go client + zero-dep JVM Unix HTTP client | |
| Kotlin CLI `--core-socket` + shared `watcher.db` | |

## Run

```bash
cd spikes/go-core
go mod tidy
go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
go build -o bin/daemonitor-corectl ./cmd/daemonitor-corectl

./bin/daemonitor-cored                 # terminal 1 — defaults to app data dir
./bin/daemonitor-corectl processes     # terminal 2
./bin/daemonitor-corectl history
./bin/daemonitor-corectl health
```

Or via Gradle (embeds cored next to the CLI):

```bash
./gradlew :cli:installDist
build/install/daemonitor-cli/bin/daemonitor-cored
build/install/daemonitor-cli/bin/daemonitor-cli --plain --core-socket "$HOME/Library/Application Support/Daemonitor/daemonitor-core.sock"
```

Flags: `-socket`, `-db`, `-interval`, `-retention` (cored); `-since-min`, `-limit` (history).

Defaults: app data dir (`…/Daemonitor/daemonitor-core.sock` + `watcher.db`) — see
`docs/sqlite-packaging.md`. Smoke tests still pass explicit temp paths.

### JVM client (IPC from Java/Kotlin world)

```bash
javac clients/jvm/UnixHttpClient.java
SOCK="$HOME/Library/Application Support/Daemonitor/daemonitor-core.sock"
java -cp clients/jvm UnixHttpClient "$SOCK" /v1/processes
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
GET /v1/daemon-logs/{pid}/tail   # lines + parsed U3 events
GET /v1/builds?limit=<n>
```

## Status

- Poll + classify works on macOS against live Gradle/Kotlin daemons
- SQLite retention path in place (`modernc.org/sqlite`, no CGO)
- JVM client proves desktop/CLI language can speak the same socket without FFI
- Kotlin CLI `--core-socket PATH` dual-runs: live process table from Go core
- **Parity slice:** classifier + JVM args + delta CPU + redactor + daemon log tails + U3 events + build aggregation (see `docs/parity.md`)
- **Dual-run notes:** side-by-side checklist and cutover criteria in `docs/dual-run.md`
- **Shared SQLite / packaging:** `docs/sqlite-packaging.md` (DDL + same-file + CLI host bundling)

## Dual-run with Kotlin CLI

```bash
# terminal 1 — Go core (app-dir defaults)
./bin/daemonitor-cored

# terminal 2 — Kotlin CLI; shared watcher.db → core owns writes
SOCK="$HOME/Library/Application Support/Daemonitor/daemonitor-core.sock"
./gradlew :cli:run --args="--plain --core-socket $SOCK"
```

When health `db_path` matches the CLI `--db` (default app `watcher.db`), the CLI skips sample
inserts and HTTP build import. Separate DBs still HTTP-copy builds. Details:
`docs/sqlite-packaging.md`.

Full comparison procedure and cutover gates: [`docs/dual-run.md`](docs/dual-run.md).

## Next

1. Release/Homebrew + desktop bundling of `daemonitor-cored`
2. Confirm redaction fixtures on both sides; decide live-heap policy for Go cutover
