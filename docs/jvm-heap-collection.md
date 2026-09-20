# Live JVM heap collection

Issue: cdsap/daemonitor#159

Daemonitor collects three distinct memory signals for Gradle-related JVMs:

| Signal | Source | Meaning |
|--------|--------|---------|
| **RSS** | OSHI `residentSetSize` | Operating-system resident set (heap pages, metaspace, native, stacks, mapped files, …) |
| **Heap limit (`-Xmx`)** | Argv parse (`JvmArgParser`) | Configured maximum heap from the process command line |
| **Live heap** | JDK Attach + local JMX `MemoryMXBean` | Runtime used / committed / max heap inside the JVM |

Missing live-heap values are reported as **unavailable** (UI detail `unavailable` / table+CLI `n/a` /
JSON `null` + `heapAvailable: false`). They are never coerced to zero.

## When live heap stays unavailable

Live heap is **intentionally not probed** for:

- Gradle wrappers
- Test workers
- Other non-daemon Gradle-related JVMs (`JAVA_GRADLE_RELATED`)

Those rows keep RSS and configured `-Xmx` (when present) but show heap used/committed as `n/a` /
`unavailable`. That is expected, not a broken metric.

Gradle and Kotlin **daemon** processes are probed; they can still show unavailable when Attach/JMX
fails (permissions, exited PID, non-HotSpot target — see Failure behavior below).

## Live CPU first sample

CPU% is a delta over the prior poll (OSHI cumulative CPU time ÷ wall clock ÷ logical processors).
On the **first observation** of a process there is no prior sample, so Live/CLI show `…` /
`sampling…` rather than an em dash or `0%`. After the next ~2s poll, `0%` means idle and a
non-zero value means measured usage.

## Mechanism

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

## Overhead

- **First successful probe per process identity:** loads the local management agent into the target
  JVM (one-time attach cost).
- **Later polls within the refresh interval (~10s):** return the cached live-heap sample (no attach /
  JMX round-trip).
- **Later polls after refresh:** reuse the cached connector address when still valid and perform a
  short JMX round-trip (no re-attach when the address still works).
- **PID exit / attach failure / timeout:** drop the cached address and sample for that identity and
  mark heap unavailable for that sample; RSS and `-Xmx` collection continue unchanged.
- Each attach/JMX attempt is bounded (~750ms). Self-attach is skipped to avoid HotSpot deadlocks.
- Only Gradle and Kotlin **daemon** processes are probed; wrappers, test workers, and other
  non-daemon related JVMs stay unavailable by design (see When live heap stays unavailable).
- Probes run on the existing IO poll path; they do not block the UI thread.

## Failure behavior

Attach/JMX can fail when:

- the process has exited between enumeration and attach;
- the target is not a HotSpot JVM that accepts attach;
- OS permissions or App Sandbox deny attach (common for Mac App Store builds);
- the local connector address is stale after a JVM restart under the same PID (cleared by
  process-identity keys and on read failure).

In all of those cases Daemonitor keeps RSS and configured `-Xmx` and surfaces live heap as unavailable for that poll.

## Packaging

Native distributions include `jdk.attach`, `java.management`, and `jdk.management.agent` so packaged apps can perform the same probe as a JDK-run build.

## Tests

- Collector unit tests cover unavailable/success mapping without inventing zero values.
- Live-heap cache tests cover refresh throttling, process disappearance eviction, and PID reuse.
- Persistence round-trips nullable live-heap columns and migrates existing databases.
- UI/CLI/MCP tests assert labels distinguish RSS, heap used, heap committed, and heap limit.
- Packaging tests assert the Attach/management modules are listed for native distributions.
