# Platform collector

`cored/internal/platform` separates OS telemetry adapters from polling, JVM probes, and persistence.

## Layout

```text
internal/platform/
  availability.go         # available | unsupported | permission_denied | …
  types.go                # PlatformCollector, metrics, capabilities
  gopsutil.go             # shared portable collection
  collector_darwin.go     # //go:build darwin
  collector_linux.go      # //go:build linux
  collector_windows.go    # //go:build windows
```

OS adapters live in the same package behind build constraints to avoid import cycles while keeping platform-specific capability overrides isolated.

`platform.New()` returns the OS-specific adapter. Poll uses it for process OS metrics; JVM probing stays in `internal/heap` and must not block OS collection.

## Availability

Optional metrics carry an availability state. Unavailable values serialize as JSON `null`, never as fabricated zeros.

## Local API

`GET /v1/capabilities` returns the current support matrix (`schema_version: 1`).

## Process I/O semantics

The I/O byte counters are cumulative and intentionally distinguish logical
transfer from storage transfer:

- Linux reads `rchar`/`wchar` as logical bytes and `read_bytes`/`write_bytes`
  as storage bytes from `/proc/<pid>/io`.
- macOS publishes only the storage counters exposed by `proc_pid_rusage`.
  Generic gopsutil zero values are not treated as logical-I/O evidence.
- Windows maps `GetProcessIoCounters` transfer bytes and operation counts to
  the logical family. Storage counters remain null, and the process count is
  named `handles_count`, not an open-file-descriptor count.

Unavailable platform fields serialize as JSON `null`. Any future interval
throughput must be computed only from two valid, monotonic cumulative samples;
the first sample is therefore rate-null.
