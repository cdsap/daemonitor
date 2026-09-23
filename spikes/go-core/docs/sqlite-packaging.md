# Shared SQLite / packaging roadmap

Status: **slices 1–3 landed** on the Go spike store (+ app busy_timeout).

- **v2:** `builds` columns match app `Watcher.sq` (`start_time` / `end_time` / `command_line`).
- **v3:** `process_samples` uses `timestamp` (not `timestamp_ms`), includes nullable live-heap
  placeholders (`heap_*`, always NULL/`0` from Go), and keeps Go-only additive columns
  (`name`, `min_heap_mb`, `gc`, `start_time_ms`, `automated`).
- **Same-file:** Go opens with `journal_mode=WAL` + `busy_timeout=5000`; the app sets
  `busy_timeout` and inherits WAL when the file is already WAL (cored should open first).
  Go `ALTER TABLE`s app-created DBs to add Go-only columns before insert. Path helper:
  `store.DefaultWatcherDBPath()` matches Kotlin `AppDirectories.databasePath`.

JSON over IPC is unchanged (`start_time_ms` / `sampled_at_ms` on the wire). Dual-run can
still HTTP-copy builds when using separate files; same-file opt-in uses one `watcher.db`.

## Schema gaps (current)

| Table | App (`Watcher.sq`) | Go spike (`internal/store`) | Status |
|-------|--------------------|-----------------------------|--------|
| `builds` | `start_time`, `end_time`, `command_line`, … | same column names | **Aligned (v2)** |
| `process_samples` | `timestamp` + live heap cols | same shared cols + Go additive extras | **Aligned (v3)** |

## Same-file open (slice 3)

```bash
# macOS example — both processes share Application Support/Daemonitor/watcher.db
# Start cored first so it sets journal_mode=WAL on the file; the app inherits WAL on reopen.
APP_DB="$HOME/Library/Application Support/Daemonitor/watcher.db"
./bin/daemonitor-cored -db "$APP_DB"
./gradlew :cli:run --args="--plain --core-socket ${TMPDIR:-/tmp}/daemonitor-core.sock --db $APP_DB"
```

`DefaultWatcherDBPath()` resolves the same path on Windows / macOS / Linux as the app.

### Single-writer rules

| Writer | Owns | Notes |
|--------|------|-------|
| `daemonitor-cored` | `process_samples` inserts, confirmed `builds` upserts, sample purge; sets **WAL** | Prefer this as the only sample writer when sharing a file |
| App (CLI/desktop) | Reads; settings; optional UI writes; retention purge; `busy_timeout=5000` | Does **not** force WAL (avoids Windows test/sidecar pain); opens an existing WAL DB in WAL mode. Avoid inserting samples from the JVM collector into the **same** file while core is also sampling — you get duplicate rows. Build HTTP import (`GET /v1/builds`) is upsert-safe if both write builds |

Spike default `-db` remains under `$TMPDIR` so casual dual-run demos stay isolated. Pass
`-db` / `--db` explicitly to share.

## Packaging slices

1. **Builds DDL alignment** — done (`PRAGMA user_version = 2` path).
2. **`process_samples` alignment** — done (`user_version = 3`).
3. **Same-file open** — done (Go WAL + busy_timeout; app busy_timeout; path helper + app-DB ALTER).
4. **Ship packaging** — distribute `daemonitor-cored` beside CLI/desktop; default socket + DB
   under app data dirs; skip JVM sample writes / HTTP build copy when sharing; remove “spike”
   framing from the dual-run path.

## Verification

```bash
cd spikes/go-core
go test ./internal/store/ -count=1
./gradlew :core:test --tests 'io.github.cdsap.daemonitor.store.WatcherDatabaseTest'
```

`TestBuildsTableMatchesAppWatcherColumns`, `TestProcessSamplesIncludesAppWatcherColumns`,
`TestOpensAppCreatedDBAndAddsGoColumns`, and `TestOpenEnablesWAL` pin the Go cutover path.
