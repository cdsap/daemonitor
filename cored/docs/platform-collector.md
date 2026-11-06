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

## Cutover status

This first slice preserves existing gopsutil process metrics while exposing capability metadata for platform-specific fields (physical footprint, PSS, private commit, disk I/O, handles) that follow-up collectors will fill in.
