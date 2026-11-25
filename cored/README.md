# Go core + native IPC clients

Native Daemonitor core that owns process polling + sample persistence and exposes a
Unix-domain-socket HTTP API. The native `daemonitor-cli` is the default standalone CLI.
The Kotlin desktop app and `--headless` mode attach to (and auto-start packaged)
`daemonitor-cored`; use `--jvm-collector` for the legacy collector path.

First-class Go module at `cored/` (`github.com/cdsap/daemonitor/cored`). Ships:

1. `daemonitor-cored` — long-running collector + SQLite + socket API
2. `daemonitor-cli` — native Bubble Tea TUI + one-shot/query/JSON commands (thin client)
3. `daemonitor-corectl` — small diagnostic client (commands mirrored in `daemonitor-cli`)

## Why

Today CLI + desktop share a Kotlin/JVM core (JDK required, heavier RSS). The product idea:

1. `daemonitor-cored` — long-running Go collector + SQLite
2. `daemonitor-corectl` / Kotlin CLI / Compose desktop — clients over IPC

## Scope

| In | Out |
|----|-----|
| Poll Gradle-related processes (`gopsutil`) | Live heap Attach |
| Classifier + JVM args + Redactor parity | Production auth |
| SQLite sample insert + retention purge | Replacing the shipping JVM app |
| `/v1/health`, `/v1/processes`, `/v1/processes/history` | |
| Go client + zero-dep JVM Unix HTTP client | |
| Kotlin CLI / desktop / headless `--core-socket` + shared `watcher.db` | |

## Run

```bash
cd cored
go mod tidy
go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
go build -o bin/daemonitor-cli ./cmd/daemonitor-cli
go build -o bin/daemonitor-corectl ./cmd/daemonitor-corectl

./bin/daemonitor-cored                 # terminal 1 — defaults to app data dir
./bin/daemonitor-cli ps --json         # terminal 2 — one-shot
./bin/daemonitor-cli history --since 1h --format csv
./bin/daemonitor-cli ps                  # find the active daemon PID
./bin/daemonitor-cli builds --pid 12345  # show that daemon's build history
./bin/daemonitor-cli ps --watch --until 1m --format jsonl
./bin/daemonitor-cli top               # interactive TUI (TTY)
./bin/daemonitor-corectl health
```

Or via Gradle (embeds cored next to the CLI):

```bash
./gradlew :cli:installDist
build/install/daemonitor-cli/bin/daemonitor-cored
build/install/daemonitor-cli/bin/daemonitor-cli --socket "$HOME/Library/Application Support/Daemonitor/daemonitor-core.sock"
```

## Cross-compile

```bash
./cored/scripts/cross-compile-cored.sh   # → cored/bin/cross/…
./gradlew crossCompileDaemonitorCored             # → build/cored-cross/…
```

Release publishes per-platform CLI zips (`daemonitor-cli-<ver>-<os>-<arch>.zip`) plus standalone
`daemonitor-cored-<ver>-*` binaries for all darwin/linux/windows amd64+arm64 targets.

Core flags: `-socket`, `-db`, `-interval`, `-retention`; CLI flags include `--since`, `--limit`,
`--pid`, `--project`, `--status`, `--watch`, `--until`, and `--format plain|json|jsonl|csv`.
Watch mode can return exit code `3` with `--fail-on-rss MB` when a process reaches the threshold.

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
./scripts/packaging-shared-db-smoke.sh   # bundled CLI + shared DB write-skip (from repo root)
```

GitHub Actions runs `go test` (including Unix-socket IPC integration) and builds binaries on
**ubuntu-latest**, **windows-latest**, and **macos-latest**. Full shell smoke (Go + JVM clients)
runs on Linux and macOS; Windows relies on the Go IPC integration test (Git Bash path mapping
breaks the shell/JDK smoke there). The **Package standalone CLI** job also runs the packaging
shared-DB E2E and asserts `daemonitor-cored` is inside the CLI zip.

## API sketch

Effective JVM flags use a separate per-incarnation metadata document. See
[the read/export contract and privacy policy](../docs/jvm-flags-metadata.md).

```
GET /v1/health
GET /v1/processes
GET /v1/processes/history?since_ms=<epoch_ms>&limit=<n>
GET /v1/processes/jvm-flags?pid=<pid>&start_time_ms=<epoch_ms>
GET /v1/daemon-logs
GET /v1/daemon-logs/{pid}/tail   # lines + parsed U3 events
GET /v1/builds?limit=<n>&pid=<pid>&project=<text>&status=<status>
```

For live Bazel servers, `/v1/daemon-logs` includes `tool: "bazel"` entries
with `source: "command.log"` or `source: "server/jvm.out"`. The PID tail defaults
to `command.log` (or the server log when it is absent); use
`/v1/daemon-logs/{pid}/tail?source=server%2Fjvm.out` to select the server log.
These tails contain redacted lines and an empty events array. The watcher derives
paths from the server's `--output_base=` argv, never invokes Bazel, and never
opens its lock. It retains up to 100 complete lines per file, reading a recent
256 KiB window and dropping lines over 16 KiB. Rotation and detected rewrites
reset the tail; absent files are ignored until they appear.

## Security and privacy

Daemonitor is local-only by default: the core does not upload telemetry or start a
remote exporter. Command lines and daemon-log lines are redacted before storage;
environment values are never collected; JVM properties are restricted to an
allowlist; and JVM diagnostic output is used only for the requested probe and is
not persisted. Probe failures cross the API as stable categories, never raw tool
stderr.

The IPC socket and SQLite database are owner-only on supported local filesystems.
History, event, build, and export queries are bounded. CLI exports include only
project and log basenames by default; pass `--include-project-path` when a full
path is explicitly needed. MCP remains an optional, read-only local surface and
inherits the same redaction and result bounds.

## Status

- Poll + classify works on macOS against live Gradle/Kotlin daemons
- SQLite retention path in place (`modernc.org/sqlite`, no CGO)
- JVM client proves desktop/CLI language can speak the same socket without FFI
- Kotlin CLI / desktop / `--headless` `--core-socket PATH` dual-runs: live process table from Go core
- **Parity slice:** classifier + JVM args + delta CPU + redactor + daemon log tails + U3 events + build aggregation (see `docs/parity.md`)
- **Dual-run notes:** side-by-side checklist and cutover criteria in `docs/dual-run.md`
- **Shared SQLite / packaging:** `docs/sqlite-packaging.md` (DDL + same-file + CLI host bundling)
- **Multi-arch release:** `scripts/cross-compile-cored.sh` + per-OS CLI zips + Homebrew multi-URL formula
- **Linux dual-run honesty in CI:** `dual-run-honesty` job runs `scripts/dual-run-linux.sh`
- **First-class module:** top-level `cored/` (`github.com/cdsap/daemonitor/cored`); left `spikes/`
- **Live heap (Go path):** `internal/heap` (`jcmd`/`jstat`) wired into poll / `/v1/processes` (#245)
- **Platform-faithful process memory:** byte metrics and native extras are documented in
  [`docs/process-memory-metrics.md`](docs/process-memory-metrics.md) (#401)

## Dual-run with Kotlin clients

```bash
# terminal 1 — Go core (app-dir defaults)
./bin/daemonitor-cored

# terminal 2 — Kotlin CLI; shared watcher.db → core owns writes
SOCK="$HOME/Library/Application Support/Daemonitor/daemonitor-core.sock"
./gradlew :cli:run --args="--plain --core-socket $SOCK"

# or desktop / headless
./gradlew run --args="--core-socket $SOCK"
./gradlew run --args="--headless --core-socket $SOCK"
```

When health `db_path` matches `--db` (default app `watcher.db`), the client skips sample
inserts and HTTP build import. Separate DBs still HTTP-copy builds. Details:
`docs/sqlite-packaging.md`.

Full comparison procedure and cutover gates: [`docs/dual-run.md`](docs/dual-run.md).

## Next

1. Optional: expand dual-run honesty to assert live-heap near-match when JDK tools are present

### JVM sampling and client reads

Core `-interval` (default `2s`) controls OS process scans and snapshot publication.
CLI `--poll-interval` controls how often the client reads the latest snapshot; it
never changes core sampling or forces an attach command. Configure the core
before starting it, for example:

```sh
daemonitor-cored -interval 2s -jvm-heap-interval 10s -jvm-advanced-interval 20s -jvm-probe-budget 750ms
```

Heap/GC (including JVM identity) and advanced JIT/class/code-cache/safepoint
commands have separate FIFO queues, results and budgets. Their intervals are
minimum gaps between admission of attempts, checked on OS scan ticks, so actual
freshness also depends on scan timing, queue length and tool duration. Each
family gets a reserved worker by default (two concurrent family attempts total).
A slow advanced family cannot consume the heap worker; FIFO ordering gives other
processes a turn. Each process incarnation/family has at most one queued or
running attempt. Exited/reused PIDs cancel old work and discard old results;
shutdown cancels queued/running commands and joins workers.

Publication merges the last completed results without waiting for JVM tools.
The JVM scheduling/merge path is regression-tested to return within **100 ms**
with blocked tools. This is a bound on JVM-induced waiting, not a whole-system
OS enumeration latency or an overhead benchmark. OS scan/storage latency still
depends on the host. Performance comparisons require an isolated baseline as
tracked in #398; concurrent collector row counts do not establish overhead.

`heap_sampled_at_ms` and `jvm_advanced_sampled_at_ms` record completed family
attempts and stay unchanged when cached results appear in newer OS snapshots.
Before a family completes, its timestamp is null and its values are unavailable.
A failed heap attempt replaces the prior result with unavailable values and the
failure attempt's timestamp; sanitized diagnostics describe failures. Advanced
attempts can contain partial results; missing fields remain unavailable and their
timestamp records the attempt, not a claim that every tool succeeded.
`sampled_at_ms` on the process/snapshot continues to describe the OS scan.

`/v1/self` reports effective OS/JVM cadences, the per-family
`jvm_probe_budget_ms`, and total `probe_concurrency` (minimum two, reserving at
least one slot per family). `/v1/health` status `ok` means IPC is serving;
`process_sample_ready=false` and `process_sampled_at_ms=0` mean no OS scan has
completed yet. A completed, valid empty scan has readiness true and a nonzero
sample timestamp. Clients can inspect process rows separately for useful JVM
samples. The Linux JDK CI matrix runs `scripts/jvm-cadence-smoke.sh` with three
live JVMs classified as Gradle daemon, Kotlin daemon and Gradle worker; the
fixtures check live tools and repeated publication, not real build overhead.
