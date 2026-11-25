# Effective JVM flags metadata

The native core collects `jcmd <pid> VM.flags -all` for the same JVM process
families eligible for live heap probing. Unlike command-line parsing or
`jinfo -flags` (a selected subset), this reports effective boolean and numeric
HotSpot flags, including default, ergonomic, and explicitly configured values.
This is an observation at collection time; manageable flags can change later.
It does not claim that a value came from argv or identify its configuration origin.
Unsupported JVMs or tools produce unavailable metadata, without removing the
process row or successful heap, CPU, or RSS measurements.

## Local read and JSON export

Use the existing local IPC HTTP transport:

```
GET /v1/processes/jvm-flags?pid=42&start_time_ms=1234567890000
```

Both positive parameters are required. Obtain them from `/v1/processes` or its
history. The server selects its local host identity; PID alone never resolves
metadata. This GET returns a standalone JSON document suitable for local export:

```json
{
  "schema_version": 1,
  "pid": 42,
  "start_time_ms": 1234567890000,
  "collected_at_ms": 1234567891000,
  "source": "jcmd.VM.flags-all",
  "availability": "available",
  "privacy_policy": "numeric-boolean-only-v1",
  "incomplete": true,
  "omitted_count": 2,
  "flags": {
    "UseG1GC": {"type": "bool", "value": "true"},
    "MaxHeapSize": {"type": "size_t", "value": "4294967296"}
  }
}
```

Values are strings to preserve large integer precision. Types follow the tool's
HotSpot type names. Both `=` and older `:=` output are accepted, with optional
origin annotations. Unknown flag types are omitted. Consumers must tolerate
additional fields. Existing process snapshots and history do not repeat the
full flag document, and their argv-derived fields keep their existing meaning.

An unavailable document has `availability: "unavailable"`, `incomplete: true`,
an empty `flags` object, and a stable `reason`: `missing_tool`, `timeout`,
`permission_denied`, `attach_disabled`, `stale_pid`, `identity_unavailable`,
`parse_error`, `unsupported_jdk`, `probe_error`, or `output_limit`.
`not_observed` with `collected_at_ms: 0` means no retained document exists for
that exact incarnation (including old database rows, non-JVM processes,
processes not yet probed, or expired retention). Reads never trigger attach.

## Bounds, caching, storage, and privacy

The probe has its own 750 ms default deadline, a 1 MiB limit on each output
stream, and a 2,048 flag-row limit. It uses the existing toolchain resolution
and JVM probe concurrency limit. Same-user ownership and a fresh OS start time
are verified before and after probing; unknown identities fail closed.
Successes and failures are cached for ten minutes by PID plus OS start time,
including concurrent requests. Disappeared PIDs are evicted; reused PIDs cannot
inherit metadata. The in-memory cache is not restored across core restarts.

Before caching or persistence, policy `numeric-boolean-only-v1` omits every
string-valued flag, including paths, commands, URLs, and string lists. It also
omits flag names containing password, token, secret, key, or credential
(case-insensitive), even for numeric and boolean values. Unsupported value
formats are omitted. Only validated decimal numbers and `true`/`false` remain.
Omitted names and values are not retained. `omitted_count` counts omitted tool
rows; `incomplete` states whether any were omitted. There is no opt-in for raw
strings. No arbitrary sysprops, environment values, raw diagnostic output, or
raw error text are added to this metadata contract.

SQLite schema version 12 adds nullable `process_instances.jvm_flags_json`;
existing rows and sample columns remain compatible. Each incarnation holds one
filtered document, updated only when it changes, instead of duplicating it in
`process_samples`. Failed probes also have a retained explicit unavailable
state. Documents follow existing instance retention and file permissions, and
are deleted when their instance is purged. No remote upload is introduced.
