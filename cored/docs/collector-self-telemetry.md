# Collector self-telemetry and sampling cadences

## Self metrics (`GET /v1/self`)

Daemonitor records its own collection cost:

| Metric | Meaning |
|---|---|
| `poll_duration_ms` | Last process discovery + OS sample duration |
| `poll_overrun_total` | Times a poll exceeded the configured process interval |
| `probe_duration_ms` | Last JVM probe duration |
| `probe_success_total` / `probe_failure_total` / `probe_timeout_total` | JVM probe outcomes |
| `storage_write_duration_ms` | Last SQLite snapshot write |
| `storage_size_bytes` | Watcher DB file size after last write |

These describe Daemonitor, not the target build.

## Independent cadences

Provisional defaults (also returned under `cadences` on `/v1/self`):

| Collector | Default |
|---|---:|
| OS process counters | 2s (`-interval`) |
| Host counters | 5s (`GET /v1/host`) |
| JVM heap/GC cache TTL | 10s |
| JVM JIT / code cache | 20s (reserved) |
| Linux PSS | 20s (reserved) |
| Container/job | 5s (reserved) |

## Probe isolation

Process discovery and OS metrics run first. JVM diagnostic probes run afterward with bounded concurrency (default 2) and per-probe timeouts from `internal/heap`. A slow or failed JVM probe cannot drop OS rows from the snapshot.
