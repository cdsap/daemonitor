# Shared SQLite / packaging roadmap

Status: **slices 1–2 landed** on the Go spike store.

- **v2:** `builds` columns match app `Watcher.sq` (`start_time` / `end_time` / `command_line`).
- **v3:** `process_samples` uses `timestamp` (not `timestamp_ms`), includes nullable live-heap
  placeholders (`heap_*`, always NULL/`0` from Go), and keeps Go-only additive columns
  (`name`, `min_heap_mb`, `gc`, `start_time_ms`, `automated`).

JSON over IPC is unchanged (`start_time_ms` / `sampled_at_ms` on the wire). Dual-run still
copies builds via HTTP into the app DB until same-file open.

## Schema gaps (current)

| Table | App (`Watcher.sq`) | Go spike (`internal/store`) | Status |
|-------|--------------------|-----------------------------|--------|
| `builds` | `start_time`, `end_time`, `command_line`, … | same column names | **Aligned (v2)** |
| `process_samples` | `timestamp` + live heap cols | same shared cols + Go additive extras | **Aligned (v3)** |

## Packaging slices

1. **Builds DDL alignment** — done (`PRAGMA user_version = 2` path).
2. **`process_samples` alignment** — done (`user_version = 3`).
3. **Same-file open** — `daemonitor-cored` and the app agree on one path + WAL; document
   single-writer rules (core writes samples/builds; app reads / optional UI writes).
   Go must `ALTER TABLE` app-created DBs to add additive columns before inserting them.
4. **Ship packaging** — distribute `daemonitor-cored` beside CLI/desktop; default socket + DB
   under app data dirs; remove “spike” framing from the dual-run path.

## Verification

```bash
cd spikes/go-core
go test ./internal/store/ -count=1
```

`TestBuildsTableMatchesAppWatcherColumns` and `TestProcessSamplesIncludesAppWatcherColumns`
pin shared columns to `Watcher.sq`.
