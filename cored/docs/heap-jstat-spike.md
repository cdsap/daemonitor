# Go live-heap probe (`jstat` / `jcmd`)

Issue: [#245](https://github.com/cdsap/daemonitor/issues/245)  
Plan: [`docs/plans/2026-09-24-go-heap-jstat-spike-plan.md`](../../docs/plans/2026-09-24-go-heap-jstat-spike-plan.md)

## What this proves

`daemonitor-cored` reads **live heap used / committed** from outside the target JVM by
shelling to HotSpot tools — no Kotlin Attach/JMX on the Go client path.

| Tool | Command | Parse |
|------|---------|--------|
| Preferred | `jcmd <pid> GC.heap_info` | `committed NNNK, used NNNK` on the heap line |
| Fallback | `jstat -gc <pid>` | `used = S0U+S1U+EU+OU`, `committed = S0C+S1C+EC+OC` (KB → MB) |

Package: `cored/internal/heap` (parsers + cached `Prober`).  
The historical `daemonitor-heapprobe` spike binary has been retired. Live heap probing now runs through `daemonitor-cored`; inspect it with `daemonitor-cli ps --details`.

**Wired into** `internal/poll` → `/v1/processes` (`heap_used_mb`, `heap_committed_mb`,
`heap_available`, …) → Kotlin `GoCoreProcessSource` / CLI-desktop HEAP USED / HEAP CMT.

Policy: probe Gradle/Kotlin daemons, Gradle test workers, generic Gradle
workers, and other classified Gradle-related JVMs. Wrappers remain `n/a`.
Attachment is best-effort, so an unavailable JVM tool never blocks OS metrics.

`jstat -gc` cumulative GC times are seconds: `YGCT` (young), `FGCT` (full),
`CGCT` (concurrent), and `GCT` (total). They are exposed as nullable
`*_gc_time_seconds` snapshot fields and formatted to three decimal places with an
`s` suffix; unavailable values are rendered as `—`.

## Expanded JVM telemetry

The slower JVM probe path can also parse per-pool measurements (retaining the
HotSpot pool name and source), `jstat -compiler` cumulative JIT counters,
`jcmd Compiler.codecache` code heaps, `jstat -class` current loaded and
cumulative unloaded-class counts, and `jcmd PerfCounter.print` safepoint
counters. Safepoint counters are not labeled as stop-the-world pause time.
Missing fields stay null; no zero values are synthesized.

Tool lookup is configured JDK directory, then `JAVA_HOME/bin`, then `PATH`.
The formats are intended for HotSpot JDK 17, 21, and 25; JDK/vendor-specific
unsupported commands are reported as sanitized diagnostics. Daemons, Kotlin
daemons, test workers, generic workers, and R8-like Gradle-related JVMs use the
same optional attach path; a JRE, non-HotSpot VM, different-user PID, or
disabled attach mechanism leaves JVM fields unavailable without affecting RSS,
CPU, or other OS collection.

## Run

```bash
cd cored
go test ./internal/heap/ ./internal/poll/ -count=1

# Against a live HotSpot PID (same UID; JDK tools on PATH / JAVA_HOME):
go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
go build -o bin/daemonitor-cli ./cmd/daemonitor-cli
./bin/daemonitor-cli ps --details
```

Needs `jcmd` and/or `jstat` (JDK `bin/`, usually next to `java` or under `JAVA_HOME`).

## Evidence (2026-09-24, JDK 23 G1)

Idle `java -Xmx256m` process:

```text
jstat -gc → used≈34 MB (OU), committed≈256 MB (EC+OC)
jcmd GC.heap_info → used 36447K (35 MB), committed 262144K (256 MB)
```

Attach can still fail (permissions, exited PID, non-HotSpot) → probe returns unavailable.

## Product decisions (shipping)

1. **jcmd-first** with jstat fallback.
2. Prefer `JAVA_HOME/bin`, then PATH.
3. Daemon-only probe policy (wrappers/workers stay `n/a`).
4. Flat JSON: `heap_used_mb`, `heap_committed_mb`, `heap_max_mb`, `heap_sampled_at_ms`,
   `heap_available`.

## Retained JVM history contract

SQLite schema v12 retains JIT `compiled_count`, `failed_count`, `invalid_count`,
`compilation_time_seconds`; class `loaded`, `unloaded_total`, `loaded_total`,
`time_seconds`; and each of `young_gc_time_seconds`, `full_gc_time_seconds`,
`concurrent_gc_time_seconds`, `total_gc_time_seconds`. Counts use SQLite INTEGER
(signed 64-bit); seconds use REAL (the live float64 precision), without conversion
to integer milliseconds. SQL NULL means unavailable; measured zero stays zero.
Presence columns distinguish an absent JIT/class object from an object whose
individual measurements are all unavailable. Older rows remain unavailable;
legacy millisecond fields are retained independently and never backfill seconds.

`loaded` is a gauge, `unloaded_total` a cumulative counter, and `loaded_total` a
separate cumulative counter only when supplied by the probe. In particular,
`jstat -class` does not supply `loaded_total`; retention does not derive it from
other counters. Young, full, concurrent and total GC seconds retain their own
probe definitions, including a total that differs from a sum of known components.
Old-generation millisecond fields do not stand in for full or concurrent seconds.

Each row retains its snapshot `timestamp` (history `sampled_at_ms`), the existing
`heap_sampled_at_ms` probe timestamp, PID and OS process `start_time_ms`. Storage
does not replace a probe timestamp with the snapshot timestamp or invent a
separate JIT/class timestamp that the live model does not supply. PID reuse must
be interpreted with start time. The shared database also retains `host_id`;
the current process-history response and normalized exports expose local
PID/start-time identity, not a cross-host identity.

The migrations add nullable columns without rebuilding existing sample rows.
The existing core single-writer/WAL model remains in place. Kotlin's shared
schema, insertion, and persisted history/MCP reads use the same columns. Go
history JSON and JSONL reconstruct the nested objects. Normalized `metrics`
and `export` rows include the new scalar fields under `process.jvm.*`, with
`count` or `seconds` units, snapshot collection time and PID/start-time identity.
An unavailable scalar exports as null, including when its parent object is absent.

Audit of the remaining live JVM fields in `model.Process`:

| Fields | Retention boundary |
| --- | --- |
| Configured min/max heap and GC name; live heap values, availability and probe timestamp | Retained by Go history; configured limits are not live occupancy. |
| Metaspace used/committed, young/old/full/concurrent/total GC counts, legacy young/old milliseconds | Retained independently; missing measurements stay unavailable. |
| Java/runtime/vendor/VM metadata, OS metadata, active processor count | Retained by Go history as nullable allowlisted values. |
| `memory_pools`, `code_cache` | Still live-only structured pool details; not part of retained history equivalence. No substitute pool values are synthesized. |
| `safepoints` | Still live-only; these counters must not be represented as GC seconds or stop-the-world pause time. |
| `heap_probe_diagnostics` | Still live-only attempt diagnostics; an absent history diagnostic is not evidence of probe success. |

The live-only boundaries above are explicit limitations retained by this change,
not evidence that those values were measured on any platform. This storage
contract and its fixture tests do not establish JVM tool support on Linux,
Windows, or any particular JDK/vendor.
