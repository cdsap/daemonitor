---
title: Bazel ancestry bridging and JVM probe spike
created: 2026-11-23
issue: 580
status: partial-evidence
---

# Bazel ancestry bridging and JVM probe spike

Issue: [cdsap/daemonitor#580](https://github.com/cdsap/daemonitor/issues/580).

## Result and evidence limits

Recommend resolving Bazel ownership through the complete same-snapshot PPID index,
keeping physical parentage intact, and requiring JVM executable evidence before
scheduling worker/test heap probes. Add optional ownership and server subtree
fields in a subsequent implementation across cored and both clients.

This spike adds executable **test-only** prototypes and a contract sketch. It does
not change production classification, probe scheduling, JSON, TUI or Desktop.
The proposal is not validated for release: actual sandbox process-tree captures,
live embedded-JDK attach, and end-to-end discovery overhead remain blocked.

| Evidence | Result |
|---|---|
| Repository source and focused Go tests | Inspected and passing |
| macOS Bazel 9.3.3 sandboxed action | Attempted; registry DNS failure before action execution; process enumeration also denied |
| Embedded runtime bin directory | Locally extracted distribution contains only `java`, `keytool` |
| Linux / Windows process trees | No host available; no capture claimed |
| Persistent workers, test runners, compiler actions | Synthetic topology coverage only; no language-rule builds captured |
| In-memory ancestry budget | Benchmarked; OS PPID/start-time read cost unmeasured |

[Local capture attempt and output](fixtures/bazel-580-local-capture.txt) records the
failure evidence. [Topology fixtures](../../cored/internal/poll/testdata/bazel_ancestry_spike.json)
are explicitly synthetic: macOS `sandbox-exec`/clang, Linux nested
`linux-sandbox`/`process-wrapper`/JVM worker, Windows launcher/native test,
persistent Swift worker, swiftc/javac/kotlinc/rustc actions, and malformed ancestry.
The Windows launcher is a hypothetical case, not an assertion that Windows uses
Unix sandbox wrappers or that a job object proves PPID ancestry.

## Current behavior verified in source

- `cored/internal/poll/poll.go`: Pass 0 builds only `kindByPID` from argv;
  Pass 1 reads argv again and filters unclassified processes. Native test promotion
  considers only the immediate parent's hard classification. A wrapper carrying
  `tools/test/test-setup.sh` in its argv can itself classify as `BAZEL_TEST`;
  wrappers are not uniformly unclassified.
- `ShouldProbeLiveHeap(kind)` admits the entire Bazel family. Jobs are queued by
  kind alone. `collectJVMMetrics` checks eligibility again but has no executable
  evidence. Same-UID checking does not identify a JVM.
- `cored/internal/tui/hierarchy.go` only sees emitted rows. Its remembered
  `(pid,start)` relationships cannot reconstruct a wrapper never emitted.
  `src/main/kotlin/io/github/cdsap/daemonitor/ui/live/LiveProcessGrouping.kt`
  also traverses only emitted parent edges, without remembered ownership.
- Neither client can count unclassified native compiler actions or their metrics.
  Adding an owner to classified rows alone would still undercount those actions.
- `ObservePoll` records discovery duration before bounded probe execution.
  Defaults are 2 seconds for OS discovery, 10 seconds for heap/GC cache and
  concurrency 2. Heap default timeout is 750 ms; do not infer a whole-snapshot
  upper bound by multiplying that value, since a sample can run multiple tools.

## Pass 0 ownership decision

Use one per-snapshot discovery record per visible PID with PPID, creation time,
resolved executable and tokenized argv when accessible. Classify from that record
instead of re-reading argv in Pass 1. Walk the complete PPID graph before filtering;
wrapper names are useful fixture coverage, not an ownership allowlist. Cache both
resolved roots and unresolved paths. The test-only `spikeOwners` demonstrates an
iterative, O(N) graph walk with O(N) storage, including a deep chain in reverse
PID order. It performs no OS queries and keeps no cross-snapshot state.

Ownership means the nearest Bazel server in the same snapshot. Each server owns
itself for identity purposes. Stop at another hierarchy root (including Gradle),
missing parent, self-parent, cycle or parent creation time newer than child.
Missing/zero creation times make the edge unresolved in this conservative sketch.
This detects some PID reuse races, not all: OS enumeration is not an atomic snapshot.
A production collector should revalidate identities when sampling and discard
ownership if a process incarnation changes. Do not carry PID-only ownership
between polls or infer it from `_bazel_`, runfiles, cwd or marker substrings.

Short-lived actions that disappear between polls cannot be recovered from a
snapshot. Reparented workers whose wrapper exited before discovery remain
unresolved. Event-based discovery or identity-safe history could address these
later; this spike does not claim complete build resource accounting.

## JVM evidence and probe policy

Keep classification separate from attachment eligibility. Resolve the OS process
image and compare the final executable component exactly (`java`, `java.exe`,
`javaw.exe`, case-insensitive for Windows). Test-only `spikeProbeJVM` exercises
this conservative positive evidence after Bazel classification. It does not
establish that a JVM supports HotSpot tools or permits attachment.

| Classified role / argv evidence | Additional evidence required | Schedule live probe? |
|---|---|---|
| Server: `A-server.jar` plus product/output-base markers | OS image is Java, including embedded runtime | Yes |
| JavaBuilder / KotlinCompile / Turbine persistent worker | OS image is Java | Yes |
| BazelTestRunner / `-Dbazel.test_suite=` test | OS image is Java | Yes |
| Swift/Go/Rust/native JavaBuilder worker, C++/Go/Rust test | Native OS image | No |
| sandbox wrapper or test-setup shell with Java markers in its arguments | Wrapper/shell OS image | No |
| javac/kotlinc launcher, renamed image, inaccessible image | Insufficient JVM evidence | No, pending explicit additional evidence |

Marker strings alone never prove that the inspected PID is the JVM: wrapper argv
can contain an entire Java invocation. Tokenized argv[0] can be an additional
signal when it agrees with the resolved image, but should not override a native
image. Bazel can rename its displayed server argv (`bazel(workspace)`); do not
require argv[0] to be `java` when the OS image identifies Java. Executable names
are a deterministic heuristic, not a security boundary. Native-image tools and
in-process JVM launchers require separate evidence, such as a validated loaded
JVM image; name allowlists must not silently claim support for them.

In a production follow-up, carry this decision into the queued job and enforce it
at both scheduling and collection, avoiding any extra executable lookup during
attachment. Non-JVM rows retain OS metrics, null heap values and no heap sample
timestamp. A skipped native process should consume no probe slots and should not
increment probe failures/timeouts. An actual JVM attach failure remains unavailable
with sanitized diagnostics, preserving the OS row.

## Embedded runtime lookup findings

`heap.Prober.TargetJDKDir` exists but has no production assignment in cored.
Moreover, `lookPath` first calls `LookPathWithJDKDir(p.JDKDir, name)`, which
internally falls back to `JAVA_HOME` and PATH. A successful global lookup prevents
consulting `TargetJDKDir` at all. Its documented priority is therefore not its
current effective priority.

`TestBazelSpikeTargetJDKLookup` characterizes this without executing host tools:
explicit JDK wins; system tools shadow an embedded target; target tools are used
only without system tools; a stripped target falls back; no tools produces
`missing_tool` diagnostics for jcmd and jstat with no runner invocation.
The tests model directories and resolution, not live attach compatibility.

Recommend a separate production change: try each candidate's own bin directory
without fallback, in order configured JDK, target image's JDK, `JAVA_HOME`, PATH.
Derive the target directory from the resolved Java image, not a server
`--install_base` string (layout varies). Resolve symlinks and macOS bundle layouts;
handle Windows tool suffixes and executable permissions explicitly. Reuse
per-incarnation metadata and avoid mutable global target selection with concurrent
probes. Preserve configured overrides.

The inspected Bazel 9.3.3 macOS install has an embedded **runtime** without diagnostic
tools. Do not promise that selecting it supplies jcmd/jstat. Fallback to a full
system JDK can work, but version/vendor/attach compatibility needs live testing.
Existing categories (`missing_tool`, `unsupported_jdk`, `permission_denied`,
`stale_pid`, `timeout`, `parse_error`, `probe_error`) suffice for attempt outcomes;
missing embedded tools alone are not a failed probe if fallback succeeds. Current
diagnostics do not expose each skipped candidate directory. Add sanitized tool
source metadata separately if needed; never include raw tool output or private
paths. OS rows must survive even when every candidate fails.

## API contract sketch (not implemented)

Keep `parent_pid`, `rss_memory_mb` and `cpu_percent` as physical-parent and
self-only measurements. Proposed additive optional JSON fields on `model.Process`:

| Field | Type | Semantics |
|---|---|---|
| `owner_pid` | nullable int32 | Nearest observed Bazel server; server points to itself |
| `owner_start_time_ms` | nullable int64 | Required with owner PID; client compares host/PID/start identity |
| `subtree_process_count` | nullable int64 | Server only; includes server plus all observed owned descendants |
| `subtree_rss_mb` | nullable int64 | Server only; sum observed resident bytes once, then convert to MiB |
| `subtree_cpu_percent` | nullable float64 | Server only; sum self CPU values using cored's existing host-normalized units |
| `subtree_metrics_complete` | nullable bool | False if any owned row lacks RSS or CPU; null on non-server/legacy rows |

Choose `owner_pid` rather than a second `root_pid` alias. Emit owner PID/start
together or neither; absent/null means unresolved, never PID 0 as a known owner.
Host identity uses the snapshot's existing scope. No identity may cross hosts.
For empty descendants, server count is 1 and metrics equal self metrics. Unknown
CPU is null if no member has a sample; otherwise it is the available sum with
`subtree_metrics_complete=false`. RSS follows the same available-sum rule. Count
includes known members lacking metrics. Completeness describes known members'
metrics only, never proof that all OS descendants were visible. RSS is resident
accounting and may double-count shared physical pages across processes; it is not
PSS or unique memory. Do not sum per-row rounded MiB or substitute heap for RSS.

To include native actions without expanding the public process-type taxonomy,
cored should collect OS metrics for all observed owned PIDs internally, including
wrappers and actions. Emit existing classified rows with owner fields; server
subtree values include unclassified internal members. Thus count may exceed the
number of expandable public rows. Ownership must be exclusive, including nested
servers, so rollups never count a descendant twice. Do not recursively add an
already aggregated server row into another server.

Client rollout:

1. cored owns resolution and subtree aggregation before `ObservePoll`; Pass 2
   remains JVM-only. Preserve self fields for details, exports and kill targets.
2. TUI `processHierarchy` uses explicit owner identity when supplied and validates
   it against a present server. For legacy snapshots lacking fields it retains
   its existing parent-chain behavior. A supplied but stale owner must not attach
   to a reused PID through a fallback path. Group summaries prefer server subtree
   values without adding visible children again; details target the self row.
3. `cli/.../GoCoreSnapshotParser.kt` must parse optional fields into
   `core/.../GradleProcess.kt`. Desktop `groupedLiveProcessRows` follows the same
   ownership and summary rules, preserving selection by actual PID. Label total
   observed subtree count separately from expandable classified-child count.
4. Old clients continue reading self fields; they cannot obtain wrapper parity
   until updated. New clients default all fields to null for old cored snapshots.
   The current parser reads known fields explicitly, but add contract tests for
   mixed versions, nulls, missing owner roots and identity mismatch before release.
   No database migration or schema-version change is made by this spike.

## Performance experiment and validation

On Darwin arm64 / Apple M4 Pro / Go 1.26.2, three runs at 300 ms per benchmark:

| Synthetic topology | ns/op range | Approx bytes/op | allocs/op |
|---|---:|---:|---:|
| 1,000 fanout | 63,810–65,495 | 174,593 | 1,009 |
| 1,000 deep chain | 87,044–87,646 | 217,528 | 39 |
| 10,000 fanout | 755,751–767,409 | 1,412,527 | 10,065 |
| 10,000 deep chain | 1,003,617–1,015,007 | 1,778,053 | 160 |

At 10,000 nodes the resolver alone uses about 0.04–0.05% of the default 2-second
cadence. These measurements exclude discovery syscalls, tokenization, OS metric
sampling, serialization and probe execution. They establish algorithm feasibility,
not negligible end-to-end overhead. In particular, adding PPID/creation-time
queries for every unclassified PID may dominate this cost.

Reproduce focused checks from the repository root (use worktree-local caches and
TMPDIR when host policy requires it):

```sh
cd cored
go test ./internal/poll ./internal/heap -run BazelSpike -count=1
go test ./internal/poll ./internal/heap -count=1
go test ./internal/poll -run '^$' -bench BenchmarkBazelSpikeAncestry -benchmem -benchtime=300ms -count=3
```

All passed locally. Fixture tests resolve each topology in both orders and check
missing parents, cycles, parent start-time inversion and unknown creation time.
Heap tests deliberately characterize current lookup rather than bless it as the
intended priority. No production behavior changes; the host owns the full Gradle
suite. No Gradle test run is claimed here.

## Remaining live validation before implementing the contract

On macOS and Linux, use isolated Bazel workspaces and known action PIDs to capture
PID, PPID, creation time, executable, tokenized argv and sample timestamp. Capture
only the controlled workload; sanitize paths and arguments before checking in
fixtures. Include sandboxed persistent Java/Kotlin/Swift workers, JVM/native tests,
and repeated clang/swiftc/javac/kotlinc/rustc actions. Sample rapidly enough to
measure missed short-lived processes, then compare with the normal cadence.
Repeat with process-wrapper and platform-default strategies. On Windows collect
actual parent PID and creation-time data; record job membership separately and do
not substitute it for PPID evidence. Record Bazel, OS and rules/toolchain versions.

For each platform compare old/new `ObservePoll` distributions at 1k/10k visible
processes, attributed member counts/RSS/CPU, and native probe job count (must be
zero). Proposed acceptance budget: incremental discovery p95 below 1% of the
configured OS cadence (20 ms at defaults), with no additional poll overruns in
matched runs. This is a proposed threshold, not a measured result. Test full JDK,
stripped embedded runtime with system fallback, incompatible fallback, no tools,
permission denial and target exit against real same-UID JVMs. Until those captures
and measurements exist, cross-platform parity and live attach remain unverified.
