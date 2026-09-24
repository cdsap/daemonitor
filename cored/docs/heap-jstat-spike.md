# Go live-heap spike (`jstat` / `jcmd`)

Issue: [#245](https://github.com/cdsap/daemonitor/issues/245)  
Plan: [`docs/plans/2026-09-24-go-heap-jstat-spike-plan.md`](../../docs/plans/2026-09-24-go-heap-jstat-spike-plan.md)

## What this proves

`daemonitor-cored` can read **live heap used / committed** from outside the target JVM by
shelling to HotSpot tools — no Kotlin Attach/JMX on the Go client path.

| Tool | Command | Parse |
|------|---------|--------|
| Preferred | `jcmd <pid> GC.heap_info` | `committed NNNK, used NNNK` on the heap line |
| Fallback | `jstat -gc <pid>` | `used = S0U+S1U+EU+OU`, `committed = S0C+S1C+EC+OC` (KB → MB) |

Package: `cored/internal/heap` (parsers + cached `Prober`).  
CLI: `cored/cmd/daemonitor-heapprobe`.

**Not wired into** `internal/poll` / `/v1/processes` yet — that is the follow-on shipping PR.

## Run

```bash
cd cored
go test ./internal/heap/ -count=1

# Against a live HotSpot PID (same UID; JDK tools on PATH):
go build -o bin/daemonitor-heapprobe ./cmd/daemonitor-heapprobe
./bin/daemonitor-heapprobe <pid>
# → pid=… source=jcmd used_mb=… committed_mb=…
```

Needs `jcmd` and/or `jstat` (JDK `bin/`, usually next to `java`).

## Evidence (2026-09-24, JDK 23 G1)

Idle `java -Xmx256m` process:

```text
jstat -gc → used≈34 MB (OU), committed≈256 MB (EC+OC)
jcmd GC.heap_info → used 36447K (35 MB), committed 262144K (256 MB)
```

Attach can still fail (permissions, exited PID, non-HotSpot) → probe returns unavailable.

## Open questions for product wiring

1. Ship **jcmd-first** (clearer numbers) with jstat fallback — recommended.
2. Require JDK tools on PATH vs discover beside the target process’s `java` binary.
3. Keep daemon-only probe policy, or also wrappers/workers (#245 discussion).
4. JSON field names for Kotlin `GoCoreProcessSource` (`live_heap_used_mb`, …).
