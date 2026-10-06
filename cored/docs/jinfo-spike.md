# JVM flags and properties spike (`jinfo`)

Issue: [#327](https://github.com/cdsap/daemonitor/issues/327)

## Recommendation

Use `jinfo -flags` as a best-effort supplement to the existing `jcmd`/`jstat`
probe, only when the live heap result does not identify a collector. Keep
command-line parsing as the first source for explicitly supplied `-Xmx`, `-Xms`,
and `-XX:+Use*GC` values. Keep `jcmd GC.heap_info` first for live heap and its
collector names, with `jstat -gc` as the live-counter fallback.

Do not use `jinfo -sysprops` in the process model. `jcmd VM.info` already
provides the allowlisted JVM identity currently exposed (`java_version`,
`java_vendor`, and active processors), while arbitrary system properties can
contain paths, credentials, or other user data. In particular, `file.encoding`
is not important enough to justify collecting the complete property map.

| Process property | Source | Decision |
| --- | --- | --- |
| `-Xmx`, `-Xms` | command line | Keep; explicit and cheap |
| Explicit GC flag | command line | Keep; preserves the original JVM argument |
| Selected collector when command line is implicit | `jcmd GC.heap_info` | Preferred; already identifies G1, Parallel, Serial, Shenandoah, and ZGC layouts |
| Selected collector when heap output is unlabelled | `jinfo -flags` | Best-effort supplement; never gates heap availability |
| Live heap used/committed | `jcmd GC.heap_info` | Preferred |
| Live heap used/committed fallback | `jstat -gc` | Keep; do not replace |
| Java version/vendor/active processors | `jcmd VM.info` | Keep the existing allowlisted fields |
| Arbitrary JVM system properties | `jinfo -sysprops` | Unavailable by policy; do not collect or persist |

## Sanitized output fixtures

The parser tests use only synthetic output with placeholder PIDs and no command
lines, paths, system properties, or logs:

```text
-XX:ActiveProcessorCount=8
-XX:+UseG1GC
-XX:MaxHeapSize=4294967296
```

`ParseJinfoFlags` accepts only enabled `Use*GC` flags. It ignores properties,
disabled flags, and lookalike names. The probe invokes it only after a
successful `jcmd GC.heap_info` or `jstat -gc` parse whose collector is still
unknown.

## Compatibility and failure behavior

`jinfo`, `jcmd`, and `jstat` are JDK tools, not JRE tools. They use the HotSpot
attach mechanism and therefore have the same-user requirement on supported
macOS, Linux, and Windows configurations. A different user, exited PID,
non-HotSpot JVM, disabled attach listener, missing tool, or unsupported tool
command can make any of them fail. The probe treats `jinfo` failure as an
unknown collector and retains the successful heap sample.

All tool calls share the existing 750 ms probe deadline. Because `jinfo` is
conditional, normal G1/Parallel/etc. `jcmd GC.heap_info` output does not pay for
an additional attach. A conditional `jinfo` timeout or error is not surfaced as
a heap error. Cache behavior remains keyed by `(pid, startTimeMs)`.

The output shape observed for the supported HotSpot tool family is one flag per
line for `jinfo -flags`; the parser intentionally depends only on the stable
`-XX:+Use...GC` token. `jinfo -sysprops` is a `key = value` map, but no parser is
added because the product does not need arbitrary properties.

### Compatibility matrix

The parser contract is checked in with sanitized fixtures in
`cored/internal/heap/parse_test.go`; no command output, paths, properties, or
real PIDs are persisted in the fixtures.

| Tool / operation | JDK 21 HotSpot | JDK 23 HotSpot | macOS | Linux | Windows | Contract |
| --- | --- | --- | --- | --- | --- | --- |
| `jcmd GC.heap_info` | supported smoke path | supported smoke path | same-user attach | same-user attach | same-user attach | preferred live used/committed and collector |
| `jcmd VM.info` | supported metadata path | supported metadata path | same-user attach | same-user attach | same-user attach | allowlisted version/vendor/processors only |
| `jstat -gc` | supported fallback | supported fallback | local perf-data may fail | local perf-data may fail | runner-dependent | fallback live values; never gates metadata |
| `jinfo -flags` | supported supplement | supported supplement | same-user attach | same-user attach | same-user attach | collector only when heap output is unknown |
| `jinfo -sysprops` | intentionally unused | intentionally unused | n/a | n/a | n/a | never collect arbitrary properties |

Failure scenarios are also part of the fixture contract:

| Scenario | Expected behavior |
| --- | --- |
| same-user live HotSpot PID | run the precedence chain and return live heap when any heap probe parses |
| different-user PID | do not attach; report `permission_denied` without command output |
| exited or reused PID | report `stale_pid`; never reuse a cached sample because the cache key includes start time |
| timeout | cancel the shared probe budget and report `timeout` |
| missing JDK tool | continue to the next eligible tool and report `missing_tool` if no heap source remains |
| unsupported JDK/tool command | preserve other successful values and report `unsupported_jdk` |
| malformed tool output | preserve other successful values and report `parse_error` |

The CI test matrix runs the sanitized parser and fake-runner compatibility
tests on Linux, macOS, and Windows through the repository's existing
cross-platform test job. A real attach smoke is intentionally limited to the
Linux dual-run job: it launches a local JDK 21 Gradle daemon and verifies the
native collector path. macOS and Windows runners remain parser/fake-runner
coverage because daemon creation and attach availability vary by hosted image;
the live result is not treated as portable evidence for JDK 21 versus 23.

The safe `/v1/processes` snapshot includes `heap_probe_diagnostics` entries
with only `tool`, `operation`, and one of these stable categories:
`missing_tool`, `timeout`, `permission_denied`, `stale_pid`, `parse_error`,
`unsupported_jdk`, or `probe_error`. A failed auxiliary operation (including
`VM.info` or `jinfo -flags`) is reported there while a successful heap sample
remains available. Error text and command output are never exposed.

Precedence is therefore: command-line `-Xmx`/`-Xms`/explicit GC flags; live
heap from `jcmd`; live heap from `jstat` only when `jcmd` fails; collector from
`jcmd`, then `jinfo -flags` only when still unknown; allowlisted JVM metadata
from `jcmd VM.info`. No metadata failure can turn a valid live heap sample into
`heap_available=false` or `gc=n/a` when another source has the value.

## Experiment record

The local machine has a JDK 23 installation and the repository contains focused
sanitized fixtures for `jinfo -flags`, `jcmd GC.heap_info`, and `jstat -gc`.
The Gradle wrapper could not download its distribution in the restricted
environment, so a live Gradle/Kotlin daemon comparison and a JDK 21 run were
not claimed as evidence here. The implementation is therefore deliberately
limited to format parsing and a non-blocking supplement; those live attach
comparisons remain release-validation work on macOS/Linux/Windows with JDK 21
and 23.

Focused validation:

```bash
cd cored
go test ./internal/heap ./internal/poll -count=1
```
