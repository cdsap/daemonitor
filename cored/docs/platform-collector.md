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

Host metrics are sampled alongside process snapshots. Linux additionally reads load averages, PSI, and cgroup v2 files in the target process's cgroup directory; a missing cgroup or file is represented by an unavailable metric rather than zero. macOS does not expose a fabricated container-throttle metric. Windows Job Object fields are read-only optional fields: the collector never creates a Job Object or changes target membership.

`ResourceScope` values are `host`, `process`, `linux_cgroup`, and `windows_job`. Every optional field carries availability metadata, so consumers should consult the capability matrix and the field's availability before using its nullable value.
