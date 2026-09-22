# Go core ↔ Kotlin collector parity

Status as of the `feat/go-core-parity` slice.

## Classifier

Go `poll.Classify` mirrors `GradleProcessClassifier` fixtures from
`core/src/test/kotlin/.../GradleProcessClassifierTest.kt` (daemon, wrapper shell + jar form,
Kotlin daemon, test worker, JAVA_GRADLE_RELATED, cache-path false positive, unrelated).

Covered by `spikes/go-core/internal/poll/poll_test.go`.

## JVM args / automation

| Concern | Kotlin | Go |
|---------|--------|-----|
| `-Xmx` / `-Xms` / `-XX:+Use*GC` | `JvmArgParser` | `poll.ParseJVMArgs` |
| `--non-interactive` / `--console=plain` | `InvocationFlags` | `poll.IsNonInteractive` |
| First CPU sample | `null` until prior exists | `cpu_percent: null` until prior exists |
| CPU formula | delta cpu / wall / logical CPUs | same (gopsutil Times) |
| Wrapper `projectPath` | cwd when type is wrapper | same |
| Command redaction | full `Redactor` | home → `~` + truncate (partial) |

## Still divergent

- Live JVM heap Attach / JMX (Kotlin only)
- Full command-line redaction rules
- Daemon log tail (still JVM-side in dual-run)
- Shared SQLite schema with the desktop app (Go has its own spike DB)

## Dual-run check

```bash
cd spikes/go-core && go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
./bin/daemonitor-cored &
./gradlew :cli:run --args="--plain --core-socket $TMPDIR/daemonitor-core.sock"
```

Compare TYPE / PID / RSS / HEAP LIMIT / CPU against a normal JVM CLI session on the same machine.
