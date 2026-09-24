# Live JVM heap collection

Issue: cdsap/daemonitor#159 · Go path: cdsap/daemonitor#245

Daemonitor collects three distinct memory signals for Gradle-related JVMs:

| Signal | Source | Meaning |
|--------|--------|---------|
| **RSS** | OSHI / gopsutil `residentSetSize` | Operating-system resident set (heap pages, metaspace, native, stacks, mapped files, …) |
| **Heap limit (`-Xmx`)** | Argv parse (`JvmArgParser` / Go `ParseJVMArgs`) | Configured maximum heap from the process command line |
| **Live heap** | JDK Attach + JMX (`--jvm-collector`) **or** HotSpot `jcmd`/`jstat` (`daemonitor-cored`) | Runtime used / committed / max heap inside the JVM |

Missing live-heap values are reported as **unavailable** (UI detail `unavailable` / table+CLI `n/a` /
JSON `null` + `heapAvailable: false`). They are never coerced to zero.

## When live heap stays unavailable

Live heap is **intentionally not probed** for:

- Gradle wrappers
- Test workers
- Other non-daemon Gradle-related JVMs (`JAVA_GRADLE_RELATED`)

Those rows keep RSS and configured `-Xmx` (when present) but show heap used/committed as `n/a` /
`unavailable`. That is expected, not a broken metric.

Gradle and Kotlin **daemon** processes are probed on both client paths:

| Path | Mechanism |
|------|-----------|
| `--jvm-collector` | JDK Attach API + local JMX `MemoryMXBean` |
| Default Go core (`daemonitor-cored`) | Out-of-process `jcmd <pid> GC.heap_info` (preferred) or `jstat -gc` (fallback) |

Daemons can still show unavailable when the probe fails (permissions, exited PID, non-HotSpot
target, missing JDK tools — see Failure behavior below).

## Live CPU first sample

CPU% is a delta over the prior poll (OSHI / gopsutil cumulative CPU time ÷ wall clock ÷ logical processors).
On the **first observation** of a process there is no prior sample, so Live/CLI show `…` /
`sampling…` rather than an em dash or `0%`. After the next ~2s poll, `0%` means idle and a
non-zero value means measured usage.

## Mechanism (JVM collector)

For each classified Gradle / Kotlin **daemon** process on each poll (~2s):

1. Consult the process-aware live-heap sample cache (`JvmHeapUsageCollector`), keyed by
   `(pid, startTimeMs)`. Within the refresh interval (~10s) the prior sample is reused.
2. On a cache miss, attach to the target PID with the JDK Attach API (`VirtualMachine.attach`).
3. Call `startLocalManagementAgent()` once per process identity (address cached afterward).
4. Connect over the local JMX connector and read `MemoryMXBean.heapMemoryUsage`.
5. Detach; map bytes to megabytes for used, committed, and runtime max.

Same-UID HotSpot JVMs are supported. Non-Java processes are never classified into the snapshot path.
Caches are keyed by process start time so PID reuse cannot return another lifetime's sample or
connector address. Entries for processes that disappear are evicted each poll.

## Mechanism (Go core)

`daemonitor-cored` probes the same daemon types via `cored/internal/heap`:

1. Cache keyed by `(pid, startTimeMs)` with ~10s TTL (same cadence as `JvmHeapUsageCollector`).
2. Prefer `jcmd <pid> GC.heap_info`; parse `committed NNNK, used NNNK` on the heap line.
3. Fall back to `jstat -gc <pid>`; used ≈ S0U+S1U+EU+OU, committed ≈ S0C+S1C+EC+OC (KB → MB).
4. Discover tools via `JAVA_HOME/bin` then `PATH`. Timeout budget ~750ms; skip self-PID; same-UID only.
5. Expose `heap_used_mb` / `heap_committed_mb` / `heap_available` on `/v1/processes`; Kotlin
   `GoCoreProcessSource` maps them to `LiveJvmHeap`.

CGO-free: cored shells to external JDK tools (no embedded JVM).

## Overhead

- **First successful probe per process identity:** loads the local management agent (Attach path)
  or runs one `jcmd`/`jstat` (Go path).
- **Later polls within the refresh interval (~10s):** return the cached live-heap sample.
- **PID exit / attach failure / timeout / missing tools:** drop the cached sample for that identity
  and mark heap unavailable for that sample; RSS and `-Xmx` collection continue unchanged.
- Each probe attempt is bounded (~750ms). Self-attach / self-PID is skipped.
- Only Gradle and Kotlin **daemon** processes are probed; wrappers, test workers, and other
  non-daemon related JVMs stay unavailable by design (see When live heap stays unavailable).
- Probes run on the existing IO poll path; they do not block the UI thread.

## Failure behavior

Probes can fail when:

- the process has exited between enumeration and attach/tool run;
- the target is not a HotSpot JVM that accepts attach / `jcmd`;
- OS permissions or App Sandbox deny attach (common for Mac App Store builds / Attach path);
- JDK tools (`jcmd` / `jstat`) are missing from `PATH` and `JAVA_HOME` (Go path);
- the local connector address is stale after a JVM restart under the same PID (cleared by
  process-identity keys and on read failure).

In all of those cases Daemonitor keeps RSS and configured `-Xmx` and surfaces live heap as unavailable for that poll.

## Packaging

Native distributions include `jdk.attach`, `java.management`, and `jdk.management.agent` so packaged apps can perform the Attach probe as a JDK-run build when using `--jvm-collector`.

Default Go-path live heap needs a local JDK with `jcmd` and/or `jstat` (usually beside `java`).

## Tests

- Collector unit tests cover unavailable/success mapping without inventing zero values.
- Live-heap cache tests cover refresh throttling, process disappearance eviction, and PID reuse.
- Go `cored/internal/heap` parser + prober tests cover jcmd/jstat fixtures and the unavailable path.
- Kotlin `GoCoreSnapshotParser` maps `heap_*` JSON fields and keeps wrappers unavailable.
- Persistence round-trips nullable live-heap columns and migrates existing databases.
- UI/CLI/MCP tests assert labels distinguish RSS, heap used, heap committed, and heap limit.
- Packaging tests assert the Attach/management modules are listed for native distributions.
