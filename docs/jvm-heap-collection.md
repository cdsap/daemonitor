# Live JVM heap collection

Issue: cdsap/daemonitor#159

Daemonitor collects three distinct memory signals for Gradle-related JVMs:

| Signal | Source | Meaning |
|--------|--------|---------|
| **RSS** | OSHI `residentSetSize` | Operating-system resident set (heap pages, metaspace, native, stacks, mapped files, …) |
| **Heap limit (`-Xmx`)** | Argv parse (`JvmArgParser`) | Configured maximum heap from the process command line |
| **Live heap** | JDK Attach + local JMX `MemoryMXBean` | Runtime used / committed / max heap inside the JVM |

Missing live-heap values are reported as **unavailable** (UI `unavailable` / CLI `—` / JSON `null` + `heapAvailable: false`). They are never coerced to zero.

## Mechanism

For each classified Gradle-related process on each poll (~2s):

1. Attach to the target PID with the JDK Attach API (`VirtualMachine.attach`).
2. Call `startLocalManagementAgent()` once per PID (address cached afterward).
3. Connect over the local JMX connector and read `MemoryMXBean.heapMemoryUsage`.
4. Detach; map bytes to megabytes for used, committed, and runtime max.

Same-UID HotSpot JVMs are supported. Non-Java processes are never classified into the snapshot path.

## Overhead

- **First successful probe per PID:** loads the local management agent into the target JVM (one-time attach cost).
- **Later polls:** reuse the cached connector address and perform a short JMX round-trip (no re-attach when the address still works).
- **PID exit / attach failure / timeout:** drop the cached address and mark heap unavailable for that sample; RSS and `-Xmx` collection continue unchanged.
- Each attach/JMX attempt is bounded (~750ms). Self-attach is skipped to avoid HotSpot deadlocks.
- Only Gradle and Kotlin **daemon** processes are probed; wrappers and test workers stay unavailable.
- Probes run on the existing IO poll path; they do not block the UI thread.

## Failure behavior

Attach/JMX can fail when:

- the process has exited between enumeration and attach;
- the target is not a HotSpot JVM that accepts attach;
- OS permissions or App Sandbox deny attach (common for Mac App Store builds);
- the local connector address is stale after a JVM restart under the same PID (rare; cleared on read failure).

In all of those cases Daemonitor keeps RSS and configured `-Xmx` and surfaces live heap as unavailable for that poll.

## Packaging

Native distributions include `jdk.attach`, `java.management`, and `jdk.management.agent` so packaged apps can perform the same probe as a JDK-run build.

## Tests

- Collector unit tests cover unavailable/success mapping without inventing zero values.
- Persistence round-trips nullable live-heap columns and migrates existing databases.
- UI/CLI/MCP tests assert labels distinguish RSS, heap used, heap committed, and heap limit.
- Packaging tests assert the Attach/management modules are listed for native distributions.
- Native packaging smoke coverage (`scripts/smoke-test-native-distribution.sh`) checks the bundled
  runtime release file for `jdk.attach` and that headless output includes heap columns (values or
  the explicit unavailable marker).
