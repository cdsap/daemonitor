# Shared SQLite / packaging roadmap

Status: **slices 1–4 landed** (DDL + same-file + packaging defaults).

- **v2:** `builds` columns match app `Watcher.sq` (`start_time` / `end_time` / `command_line`).
- **v3:** `process_samples` uses `timestamp` (not `timestamp_ms`), includes nullable live-heap
  placeholders (`heap_*`, always NULL/`0` from Go), and keeps Go-only additive columns
  (`name`, `min_heap_mb`, `gc`, `start_time_ms`, `automated`).
- **Same-file:** Go opens with `journal_mode=WAL` + `busy_timeout=5000`; the app sets
  `busy_timeout` via JDBC connection properties and inherits WAL when the file is already WAL.
  Go `ALTER TABLE`s app-created DBs to add Go-only columns before insert.
- **Packaging defaults:** `daemonitor-cored` defaults socket + DB under the app data dir
  (`daemonitor-core.sock` / `watcher.db`). `:cli:installDist` / `distZip` include a host-arch
  `daemonitor-cored` when the Go toolchain is available. With `--core-socket`, if health
  `db_path` matches `--db`, the CLI skips sample inserts and HTTP build import.

## Schema gaps (current)

| Table | App (`Watcher.sq`) | Go spike (`internal/store`) | Status |
|-------|--------------------|-----------------------------|--------|
| `builds` | `start_time`, `end_time`, `command_line`, … | same column names | **Aligned (v2)** |
| `process_samples` | `timestamp` + live heap cols | same shared cols + Go additive extras | **Aligned (v3)** |

## Same-file + packaging (slices 3–4)

```bash
# Defaults already agree (app data dir). Build CLI + cored:
./gradlew :cli:installDist
# terminal 1
build/install/daemonitor-cli/bin/daemonitor-cored
# terminal 2
build/install/daemonitor-cli/bin/daemonitor-cli --plain --core-socket "$HOME/Library/Application Support/Daemonitor/daemonitor-core.sock"
```

`DefaultWatcherDBPath()` / `DefaultSocketPath()` match Kotlin `AppDirectories`.

### Single-writer rules

| Writer | Owns | Notes |
|--------|------|-------|
| `daemonitor-cored` | `process_samples` inserts, confirmed `builds` upserts, sample purge; sets **WAL** | Default writer when sharing |
| App (CLI/desktop) | Reads; settings; retention purge; `busy_timeout=5000` | With matching `db_path`, skips sample writes + HTTP build copy. Separate DBs still import builds over HTTP |

Override with `-db` / `--db` (and matching `-socket` / `--core-socket`) for isolated runs.

## Packaging slices

1. **Builds DDL alignment** — done (`PRAGMA user_version = 2` path).
2. **`process_samples` alignment** — done (`user_version = 3`).
3. **Same-file open** — done (Go WAL + busy_timeout; app busy_timeout; path helper + app-DB ALTER).
4. **Ship packaging** — done for CLI host-arch bundling + app-dir defaults + shared-DB write skip.
   Remaining: release/Homebrew assets, desktop bundling, cross-compile matrix.

## Verification

```bash
cd spikes/go-core
go test ./internal/store/ -count=1
./gradlew :core:test --tests 'io.github.cdsap.daemonitor.application.PollMonitoringTest'
./gradlew :cli:installDist
ls build/install/daemonitor-cli/bin/daemonitor-cored
```
