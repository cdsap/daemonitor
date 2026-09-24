---
title: Go live-heap jstat/jcmd spike
created: 2026-09-24
status: spike-in-progress
issue: 245
---

# Go live-heap probe spike (`jstat` / `jcmd`)

## Goal

Prove `daemonitor-cored` can obtain **live heap used / committed** for same-UID HotSpot
JVMs **without** embedding JDK Attach/JMX in Kotlin — so the default Go client path can fill
CLI/desktop **HEAP USED** / **HEAP CMT** (#245).

## Product framing

| Signal | Today (Go) | Spike target |
|--------|------------|--------------|
| RSS | gopsutil | unchanged |
| HEAP LIMIT (`-Xmx`) | argv parse | unchanged |
| Live used / committed | always null | external HotSpot tool on PID |

Supersedes the 2026-09-23 cutover that accepted missing live heap on the Go path.

## Approach under test

1. Discover `jstat` / `jcmd` next to `java` or on `PATH`.
2. Prefer `jcmd <pid> GC.heap_info` (clear `used` / `committed` KB).
3. Fall back to `jstat -gc <pid>` (sum young+old capacity/used KB; GC-variant robust parsing).
4. Cache per `(pid, startTimeMs)` ~10s (mirror Kotlin `JvmHeapUsageCollector`).
5. Timeout ~750ms; fail → unavailable (never coerce to 0).
6. Policy (initial): probe `GRADLE_DAEMON` / `KOTLIN_DAEMON` only (wrappers/workers stay `n/a` unless product changes).

## Spike deliverables (this branch)

| Piece | Role |
|-------|------|
| `cored/internal/heap` | Parse `jcmd` / `jstat` output; exec probe; cache |
| `cored/cmd/daemonitor-heapprobe` | One-shot CLI: `daemonitor-heapprobe <pid>` |
| `cored/docs/heap-jstat-spike.md` | Evidence, column math, open questions |
| Unit tests | Fixtures from real JDK 23 G1 `jstat -gc` / `jcmd GC.heap_info` |

**Out of spike (follow-on PR):** wire into `internal/poll` + `/v1/processes` JSON + Kotlin
`GoCoreProcessSource` liveHeap mapping + drop Go-path banner.

## Non-goals

- Replacing Kotlin Attach path for `--jvm-collector`
- CGO / embedding a JVM in cored
- Probing wrappers/test workers (unless product expands #245)

## Success criteria

- [ ] Parser tests green on checked-in fixtures
- [ ] `daemonitor-heapprobe <pid>` returns used/committed MB against a live HotSpot JVM (same UID)
- [ ] Document JDK-on-PATH / tool discovery + failure modes
- [ ] Clear recommendation: ship jcmd-first vs jstat-first into cored poll
