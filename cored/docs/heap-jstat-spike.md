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
CLI: `cored/cmd/daemonitor-heapprobe`.

**Wired into** `internal/poll` → `/v1/processes` (`heap_used_mb`, `heap_committed_mb`,
`heap_available`, …) → Kotlin `GoCoreProcessSource` / CLI-desktop HEAP USED / HEAP CMT.

Policy: probe `GRADLE_DAEMON` / `KOTLIN_DAEMON` only (wrappers/workers stay `n/a`).

## Run

```bash
cd cored
go test ./internal/heap/ ./internal/poll/ -count=1

# Against a live HotSpot PID (same UID; JDK tools on PATH / JAVA_HOME):
go build -o bin/daemonitor-heapprobe ./cmd/daemonitor-heapprobe
./bin/daemonitor-heapprobe <pid>
# → pid=… source=jcmd used_mb=… committed_mb=…
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
