# Shared SQLite / packaging roadmap

Status: **slice 1 landed** — Go spike `builds` table column names match app
`Watcher.sq` (`start_time` / `end_time` / `command_line`). JSON over IPC is unchanged
(`start_time_ms` etc. on the wire).

This is the cutover path for “No reliance on Go spike SQLite for shipping retention/UI”.
Until a later slice opens the **same file**, dual-run still copies builds via HTTP into the
app DB.

## Schema gaps (current)

| Table | App (`Watcher.sq`) | Go spike (`internal/store`) | Status |
|-------|--------------------|-----------------------------|--------|
| `builds` | `start_time`, `end_time`, `command_line`, … | same column names | **Aligned (v2)** |
| `process_samples` | `timestamp`, live heap cols | `timestamp_ms`, `name`/`gc`/`automated`, no live heap | **Divergent** |

JSON `/v1/builds` field names stay snake_case with `_ms` suffixes for Kotlin
`GoCoreSnapshotParser` compatibility.

## Packaging slices

1. **Builds DDL alignment (this)** — Go `PRAGMA user_version = 2`; legacy spike DBs migrate
   `start_time_ms` → `start_time` (and add nullable `command_line`).
2. **`process_samples` alignment** — rename `timestamp_ms` → `timestamp`; decide which Go-only
   columns (`name`, `min_heap_mb`, `gc`, `automated`) stay as additive nullable columns vs
   drop; add nullable live-heap columns for forward compat (values remain NULL from Go).
3. **Same-file open** — `daemonitor-cored` and the app agree on one path + WAL; document
   single-writer rules (core writes samples/builds; app reads / optional UI writes).
4. **Ship packaging** — distribute `daemonitor-cored` beside CLI/desktop; default socket + DB
   under app data dirs; remove “spike” framing from the dual-run path.

## Verification

```bash
cd spikes/go-core
go test ./internal/store/ -count=1
```

`TestBuildsTableMatchesAppWatcherColumns` pins the builds column order to `Watcher.sq`.
