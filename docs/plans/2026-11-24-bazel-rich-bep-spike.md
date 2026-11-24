---
title: Rich Bazel BEP telemetry, binary decoding, and agent attribution
created: 2026-11-24
status: spike-complete
issue: https://github.com/cdsap/daemonitor/issues/581
---

# Rich Bazel BEP contract

## Decision and scope

Use a shared, allowlisted event projection for JSON and length-delimited binary
BEP. Keep rich telemetry in a separate, versioned SQLite detail row keyed by the
existing build UUID. Keep list responses small; details require an explicit opt-in.
Associate an agent with the short-lived Bazel **client invocation**, never the
long-lived Bazel server environment.

This spike delivers real, privacy-reduced fixtures and an executable, test-only
wire experiment. It does not enable binary ingestion, migrate databases, change
HTTP/MCP responses, or collect process environments. Those are follow-up work.
The decisions below are a proposed implementation contract, not current behavior.

## Evidence and boundaries

Repository sources inspected:

- `cored/internal/bep/{parse_json.go,watcher.go,discover.go}`: binary sinks are
  discovered but skipped. JSON parsing emits and resets at `finished`.
- `cored/internal/store/{store.go,builds.go,sessions.go}`: schema version 7;
  `builds` has `agent`, `agent_provider`, and `tool`; sessions store separate
  process summaries and a cached close response.
- `cored/internal/api/server.go`: `/v1/builds` has filters and bounded limits;
  log snippets require `include_logs=true`.
- `src/main/kotlin/io/github/cdsap/daemonitor/mcp/DaemonitorMcpServer.kt`,
  `core/.../application/DefaultDaemonitorQueryService.kt`, and
  `core/.../store/WatcherDatabase.kt`: MCP queries Kotlin persistence, not a
  transparent proxy of `/v1/builds`. History merges build and session entries.
- `cored/internal/poll/poll.go`: Pass 0 currently indexes process classification
  for Bazel test ancestry, not agent ancestry.
- `core/.../domain/AgentDetector.kt`: names-only fingerprints, ambient-name
  subtraction, specific-before-generic matching. Only Claude's signature is
  documented there as empirically confirmed; other signatures remain heuristics.

Wire mappings are pinned to Bazel **8.4.2**, not an unversioned main branch:
[BEP proto](https://github.com/bazelbuild/bazel/blob/8.4.2/src/main/java/com/google/devtools/build/lib/buildeventstream/proto/build_event_stream.proto),
[command-line proto](https://github.com/bazelbuild/bazel/blob/8.4.2/src/main/protobuf/command_line.proto),
[action-cache proto](https://github.com/bazelbuild/bazel/blob/8.4.2/src/main/protobuf/action_cache.proto).
The [BEP documentation](https://bazel.build/remote/bep) describes varint-delimited
file framing and post-finish events. This is file decoding, not decoding the BES
gRPC envelope. Compatibility with other Bazel releases needs its own fixtures.

### Captures

On 2026-11-24 UTC, Bazel 8.4.2 on macOS arm64 ran a custom Starlark test that
writes an executable shell script containing `exit 0`. A second invocation built
`//:missing` to exercise loading failure. Both commands emitted JSON and binary
simultaneously. The environment was cleared with `env -i`; user rc files were
disabled. No existing project, credentials, or environment files were consulted.

Fixtures in `cored/internal/bep/testdata/`:

| Pair | Outcome | Retained events | JSON bytes | Binary bytes |
|---|---|---:|---:|---:|
| `rich_success.ndjson` / `rich_success.bin` | One passing test | 20 | 4257 | 1843 |
| `rich_failed.ndjson` / `rich_failed.bin` | Missing target; `LOADING_FAILURE` | 8 | 2374 | 1261 |

These are **projections of real captures**, not complete BEP graphs. Event order,
UUIDs, timestamps, counts, and retained payload values are unchanged. Unknown
payloads and child announcements were removed. Retained IDs identify original
events; projections must not be used to test graph completeness. The reductions
are identical in both encodings:

- Retain `started` except options description; `finished` except anomaly/failure
  detail; `testSummary` except passed/failed file references.
- Retain structured command-line label and only the `command` section; remove
  executable paths, target chunks, and **all option sections**.
- Retain metrics action summary (except per-action data), memory, timing, worker,
  and worker-pool submessages; omit graph, package, artifact, and network metrics.
- Retain progress and aborted payloads from these controlled runs, manually
  inspected to contain only fixture paths and build diagnostics; retain
  `lastMessage`. Arbitrary user progress text would not be retained by default.

The success fixture reports actions created/executed **7/6**, action-cache misses
**7**, CPU/wall time **5729/4062 ms**, analysis/execution **90/401 ms**, and one
passing test lasting **224 ms**. `finished` precedes `buildMetrics` in **both**
fixtures. `workerPoolMetrics` is present but empty; `workerMetrics` and post-GC
heap scalars are absent. Worker, heap-scalar, and nonzero cache-hit mappings below
are source-verified, not empirically exercised by these captures. No real coding
agent invocation was captured.

### Reproduction recipe

Use a disposable workspace with this `fixture.bzl`:

```python
def _impl(ctx):
    executable = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(executable, "#!/bin/sh\nexit 0\n", is_executable = True)
    return [DefaultInfo(executable = executable)]
fixture_test = rule(implementation = _impl, test = True)
```

`MODULE.bazel` contains `module(name = "bep_spike")`; `BUILD.bazel` contains:

```python
load(":fixture.bzl", "fixture_test")
fixture_test(name = "smoke")
```

Using a pinned Bazel executable, run from that workspace (paths below are scratch
paths, not paths in a user's project):

```sh
env -i PATH=/usr/bin:/bin:/opt/homebrew/bin /tmp/issue581-bazel-8.4.2 \
  --batch --output_user_root=/tmp/issue581-bazel-root --ignore_all_rc_files \
  test //:smoke --noenable_bzlmod --enable_workspace --lockfile_mode=off \
  --symlink_prefix=/tmp/issue581-links/ \
  --build_event_json_file=/tmp/issue581-capture/raw.json \
  --build_event_binary_file=/tmp/issue581-capture/raw.bin
```

Repeat with `build //:missing` and different output filenames; exit 1 is expected.
Public Bazel tool dependencies may need downloads. Apply the projection above
before retaining fixtures: for binary, filter fields within each frame, rebuild
the enclosing lengths and frame prefix, preserving retained wire bytes. JSON
uses the corresponding field names. Timing/count values vary across reruns.

## Minimal field and semantic contract

Numbers below are **payload field numbers**, not fields of `BuildEventId`.
Wire type `0` is varint; `2` is length-delimited (message/string/packed values).

| BuildEvent field | Number | Wire |
|---|---:|---:|
| id / children | 1 / 2 | 2 |
| progress / aborted | 3 / 4 | 2 |
| started / test_summary / finished | 5 / 9 / 14 | 2 |
| last_message | 20 | 0 |
| structured_command_line / build_metrics | 22 / 24 | 2 |

Nested fields (JSON names shown, with wire numbers in parentheses):

| Message | Selected fields | Meaning / storage rule |
|---|---|---|
| BuildStarted | uuid(1), startTimeMillis(2), buildToolVersion(3), command(5), workingDirectory(6), workspaceDirectory(7), serverPid(8), startTime(9) | Strings use wire 2; millis/PID use 0; timestamp uses 2. UUID is build identity, PID is server identity evidence only. Do not retain optionsDescription(4). |
| BuildFinished | overallSuccess(1), finishTimeMillis(2), exitCode(3), finishTime(5) | ExitCode name(1, string), code(2, int32). Present exitCode with omitted code means success (proto3 zero). Missing exitCode is unknown unless legacy overallSuccess is supplied. |
| BuildMetrics | actionSummary(1), memoryMetrics(2), timingMetrics(5), workerMetrics(9, repeated), workerPoolMetrics(11) | All wire 2. Keep message presence and source version. |
| MemoryMetrics | usedHeapSizePostBuild(1), peakPostGcHeapSize(2), garbageMetrics(3, repeated), peakPostGcTenuredSpaceHeapSize(4) | Heap and garbage amounts are bytes, not RSS or MB. Post-build heap needs memory profiling; peak zero may mean no major GC. Do not force GC to obtain telemetry. |
| GarbageMetrics | type(1, string), garbageCollected(2, int64) | Per-pool garbage bytes, not GC count/pause time. Bounded list; sanitize pool names. |
| TimingMetrics | cpuTimeInMs(1), wallTimeInMs(2), analysisPhaseTimeInMs(3), executionPhaseTimeInMs(4), actionsExecutionStartInMs(5) | int64, ms, wire 0. CPU is build CPU time, not an OS sampling percentage. Analysis/execution may overlap; never require their sum to equal wall time. |
| ActionSummary | actionsCreated(1), actionsExecuted(2), actionsCreatedNotIncludingAspects(3), actionData(4), remoteCacheHits(5), runnerCount(6), actionCacheStatistics(7) | Counts are invocation metrics. Defer per-action data; remoteCacheHits is deprecated and must retain that label if exposed. |
| RunnerCount | name(1, string), count(2, int32), execKind(3, string) | Keep bounded entries, including `total`; do not sum `total` together with its component runners. |
| ActionCacheStatistics | hits(3), misses(4), missDetails(5) | Local action-cache counters; MissDetail reason(1, enum), count(2, int32). Do not rename these remote-cache hits/misses. |
| WorkerMetrics | workerId(1, deprecated), processId(2), mnemonic(3), isMultiplex(4), isSandbox(5), isMeasurable(6), workerStats(7, repeated), workerIds(8, repeated uint32), workerKeyHash(9), workerStatus(10), actionsExecuted(11), code(12, optional), priorActionsExecuted(13) | Numeric/bool fields wire 0; mnemonic/stats wire 2; workerIds supports packed wire 2 and unpacked wire 0. Multiplex IDs can share one process; do not count each ID as a process. |
| WorkerStats | collectTimeInMs(1), workerMemoryInKb(2), lastActionStartTimeInMs(3), priorWorkerMemoryInKb(4) | Wire 0. Store source KB unit explicitly; convert to bytes only using a documented 1024 multiplier. These snapshots are not a continuous memory peak. |
| WorkerPoolMetrics | workerPoolStats(1, repeated) | Nested stats: hash(1), mnemonic(2), createdCount(3), destroyedCount(4), evictedCount(5), userExecExceptionDestroyedCount(6), ioExceptionDestroyedCount(7), interruptedExceptionDestroyedCount(8), unknownDestroyedCount(9), aliveCount(10). All numeric wire 0, mnemonic wire 2. Destroyed includes evictions; do not double count. |
| CommandLine | commandLineLabel(1), sections(2, repeated) | Section label(1), chunkList(2), optionList(3). ChunkList chunk(1, repeated string); OptionList option(1, repeated). Option combinedForm(1), optionName(2), optionValue(3), effectTags(4), metadataTags(5). Only allowlisted derived metadata persists. |
| TestSummary | totalRunCount(1), overallStatus(5), totalNumCached(6), firstStartTimeMillis(7), lastStopTimeMillis(8), totalRunDurationMillis(9), runCount(10), shardCount(11), totalRunDuration(12), firstStartTime(13), lastStopTime(14), attemptCount(15) | Scalars wire 0; modern duration/timestamps wire 2. Aggregate by target/configuration, not by TestResult attempts again. Drop file lists passed(3)/failed(4). |
| BuildEventId.testSummary | testSummary(7) → label(1), configuration(2) → id(1) | All wire 2. Needed to distinguish target/configuration summaries; this ID tag is **not** the payload tag 9. |
| Aborted / Progress | reason(1, enum), description(2, string) / stdout(1), stderr(2) | Aborted can replace an expected payload. Retain reason by default; text handling below. |

`actionsExecuted` includes remote-cache hits and excludes local action-cache hits.
It is not a cache-miss counter. Runner counts are strategy observations, not unique
logical action totals. Worker hashes are grouping hints, not globally unique IDs.
Do not correlate worker PIDs to observations without process start-time evidence.

Prefer modern Timestamp/Duration fields over deprecated millis when available.
Both messages use seconds(1, int64) and nanos(2, int32); validate ranges and signed
values before converting with overflow checks. JSON timestamps use RFC3339 and
durations use seconds strings. JSON int64 fields may be strings; never decode
through float64. Preserve unknown enum numbers as unknown, not success.

Proto3 scalar omission does not prove that a measurement is available or zero.
Normalize unavailable metrics to null plus an availability reason where needed,
especially heap scalars. For a present cache-statistics message, absent ordinary
counter fields have protobuf zero semantics. Absent submessage means unavailable.
Missing worker metrics means unavailable, not proof of zero live workers.

## Decoder and lifecycle proposal

The test-only reader uses `encoding/binary` varints and skips wire types 0/1/2/5.
It proves framing and selected JSON/binary field parity without adding a module,
protobuf-generated code, C bindings, or runtime code. `protowire` is a reasonable
production alternative for battle-tested tag skipping; choose it if maintaining
manual compatibility becomes more costly than the small dependency. No binary
size delta is claimed for a future production implementation; test-only code
adds no shipped runtime code. Keep `CGO_ENABLED=0` as a build gate.

Production acceptance contract:

1. Cap frames and JSON lines at 8 MiB before allocation; cap nested depth at 16,
   retained event state at 1 MiB/build, lists at 256 entries/category, and total
   active invocation state at 64 MiB. Overflow marks detail `truncated` and drops
   excess fields deterministically, never evicting a completed outcome silently.
2. Incremental framing retains split prefixes/payloads until more bytes arrive.
   EOF at a frame boundary is temporary while tailing; truncated final frames
   mark details partial on close. Overlong/overflowing varints, invalid field
   numbers, wrong known wire types, and lengths past the frame boundary fail
   that sink. Do not scan arbitrary bytes trying to resynchronize.
3. Skip unknown fields safely, including fixed32/fixed64; impose depth limits
   when skipping groups or reject deprecated groups explicitly. Accept packed
   and unpacked repeated worker IDs. Use protobuf last-oneof-wins and singular
   merge semantics; deduplicate repeated events by original event ID.
4. Keep invocation state after `finished`. Upsert the base outcome then attach
   metrics/test details by UUID as later events arrive. `lastMessage` seals detail
   collection; it does not create success or prove graph completeness. Track
   announced IDs if a `complete` graph claim is needed. No finish means no
   invented completed build; finish without metrics stays a valid base outcome.
5. Track source file identity plus offset, not path alone. Truncation/replacement
   starts a fresh stream; duplicate JSON/binary sinks merge by UUID/event ID,
   preferring one configured source rather than adding counters. Bound state and
   expire idle sinks (proposed 60 seconds after client exit); late data may
   enrich existing rows, but must not rewrite a conflicting final outcome.

The experiment reads finite files, rejecting truncated frames. It does **not**
implement incremental tailing, duplicate/oneof merging, packed worker decoding,
modern time conversion, or the production memory budget. Those need dedicated
regression tests before enabling binary sinks. The real fixtures do not exercise
these cases; synthetic wire-boundary tests cover only the reader's stated scope.

## SQLite proposal

Use the next coordinated Go/SQLDelight migration after the current schema, not a
Go-only change that leaves Kotlin MCP unable to read details. Suggested SQL:

```sql
CREATE TABLE bazel_build_details (
  build_id TEXT PRIMARY KEY NOT NULL REFERENCES builds(build_id) ON DELETE CASCADE,
  schema_version INTEGER NOT NULL CHECK (schema_version = 1),
  bazel_version TEXT,
  source_format TEXT NOT NULL CHECK (source_format IN ('json', 'binary')),
  collection_state TEXT NOT NULL
    CHECK (collection_state IN ('partial', 'sealed', 'truncated', 'invalid')),
  updated_at_ms INTEGER NOT NULL,
  metrics_json TEXT NOT NULL CHECK (length(CAST(metrics_json AS BLOB)) <= 1048576),
  attribution_source TEXT,
  attribution_confidence TEXT
);
```

`metrics_json` is a typed, allowlisted projection, never raw BEP. Shape v1:
`memory` (heap bytes, garbage list, availability), `timing` (ms), `actions`
(created/executed, local cache counters, bounded runners), `workers`,
`worker_pools`, `tests` (target/configuration, status, counts, duration), and
`diagnostics` (abort enum, optional sanitized snippet). Use integer JSON numbers
internally; public int64 counters/bytes use decimal strings to avoid JavaScript
precision loss. Reject negative unsigned metrics and overflow. Include explicit
source units in field names. Strings have per-field limits and output is bounded
by serialized byte size. A separate detail row avoids deserializing metrics or
multiplying rows in ordinary list queries.

Write base build and detail atomically when possible; late events update only
details. Keep existing sampled `peak_memory_mb` / `peak_cpu_percent` as OS metrics,
not substitutes for BEP heap/CPU. Attribution display stays in `builds.agent` and
`agent_provider`; provenance lives in the detail row. A Bazel server PID alone
must not bridge process incarnations or hosts.

Foreign keys are not currently enabled in the Go DSN. Do not rely solely on
`ON DELETE CASCADE`: migration must establish FK enforcement on **every** writer
connection or explicitly delete detail rows in each build-retention transaction,
including Go purge and Kotlin deletion paths. Test rollback, orphan prevention,
and old DB migration. No new index is needed for UUID detail lookups.

Leave `observation_sessions` and its cached close response small. Session membership
is a time/process association, not a reason to copy rich metrics into
`response_json`. Fetch details through matching build UUIDs on demand. A session
can overlap builds; never sum details across overlapping sessions as unique work.
Existing close responses remain historical snapshots when late metrics arrive.

## HTTP and MCP proposal

Preserve current default response shapes. Add `include_metrics=true` to
`GET /v1/builds` and `include_metrics: boolean` (default false) to both
`daemonitor_search_history` and `daemonitor_builds_for_process` tool schemas.
Only selected build rows gain an optional `bazel_details` object; Gradle and
session entries do not. Example **proposed**, abbreviated detail:

```json
{"build_id":"59ba4502-9d89-4034-ade6-8c476524344e","tool":"BAZEL",
 "bazel_details":{"schema_version":1,"collection_state":"sealed",
 "metrics":{"actions":{"executed":"6","local_cache_misses":"7"},
 "timing":{"wall_ms":"4062"}}}}
```

Use the existing list limit first, then one batch detail lookup for the selected
UUIDs; no N+1 queries, metrics joins in summary counts, or text search over raw
metrics. Bound opt-in detail requests to 20 builds and 2 MiB per response; reject
larger explicit requests with HTTP 400 / MCP invalid-params and explain the limit
rather than silently dropping rows. Keep history's `builds` compatibility array
and `history` entries consistent. Observation session entries never gain copied
metrics. Do not advertise MCP support until SQLDelight models, query service, and
serializers support the same projection. These changes require contract tests
for default responses, opt-in parity, PID reuse, mixed history, and size limits.

`include_metrics` never enables diagnostic text. Snippets require the existing
HTTP `include_logs=true` plus collection opt-in; MCP needs an explicit equivalent
`include_logs` option with default false. Text must be sanitized **before storage**,
not only at response time. No API may return raw command-line options or env data.

## Privacy and agent attribution proposal

Default collection allowlists numeric metrics, outcome enums, and bounded names.
Drop raw command lines, option values/combined forms, metadata maps, URLs/headers,
exec-request environments, test-log URIs, file contents, and progress text.
Treat labels, workspace paths, mnemonics, and GC-pool names as potentially private
strings: apply existing redaction plus length/control-character limits before
storage. Do not fetch URIs referenced by a BEP file.

For opt-in diagnostics, retain at most 4 KiB UTF-8 per build after redaction.
Prefer an abort enum and a safe generated message. A size cap is not redaction;
if arbitrary output cannot be reliably sanitized, discard it. No raw text in
parse-error logs. The controlled failure fixture's diagnostic is test evidence,
not permission to collect arbitrary build output.

### Attribution sources and confidence

| Source | Proposal | Limitation |
|---|---|---|
| Client ancestry during Pass 0 | Cache only same-user `(pid, start_time, ppid, executable classification)`; walk at most 16 ancestors under a short timeout. Match known executable basenames / launcher identities for Claude Code, Cursor, Codex, Gemini CLI, Aider. | Node/Python launchers need narrowly matched script identity; generic `node`, editor presence, or arbitrary substring matches are insufficient. Capture before short-lived clients exit. |
| Client-provided env **names** | Match the Gradle fingerprints below, subtract watcher ambient names case-insensitively. Prefer a names-only client integration. | OS environment APIs generally return values too; do not call them under a strict names-only collection contract. Reading values and then dropping them is still reading values. |
| BEP command-line env options | Disabled by default. A future opt-in streaming projection may extract only the prefix before `=` from known env-option encodings and immediately discard the remainder, without logging/copying it into typed state. | Structured command lines do not contain a guaranteed complete client environment; `action_env` can reflect build configuration, not the driving agent. This is weaker evidence and requires explicit review of the collection boundary. |
| BEP metadata keys | Ignore arbitrary metadata. A future dedicated allowlisted agent enum may be accepted as self-reported. | Metadata values/tool tags are user-controlled and are not trustworthy ancestry proof. |

Match Gradle's priority and display vocabulary: `CLAUDECODE`, `CLAUDE_CODE*`, or
`CLAUDE_EFFORT` → Claude Code / Anthropic; `CURSOR*` → Cursor / configurable;
`CODEX*` → Codex / OpenAI; `GEMINI*` → Gemini CLI / Google; `AIDER*` → Aider /
configurable. After ambient subtraction, `AI_AGENT` or `AI_*` with no specific
match maps to AI agent (unrecognized) / unknown. Names are uppercased in memory;
store only derived agent/provider, source, and confidence, never the name set.
API-key **names** can describe available tooling without proving agent usage;
classify these fingerprints as heuristic, not verified identity.

Use distinguishing live ancestry as higher confidence than names-only heuristics.
Conflicting specific sources yield unknown/conflict rather than guessing; do not
infer provider for configurable agents. Missing permissions, exited parents,
cycles, PID reuse, or ambient-only names yield no attribution. Preserve Gradle's
first-specific-match behavior within the names detector, but record multiple-source
conflicts separately. Never attribute all builds from a persistent server based
on the server's startup environment. Agent attribution must not change the build's
BEP source or outcome and is not an authentication mechanism.

## Validation and follow-up gates

Focused validation from `cored`:

```sh
CGO_ENABLED=0 GOCACHE=/tmp/issue581-go-cache go test ./internal/bep
CGO_ENABLED=0 GOCACHE=/tmp/issue581-go-cache go build \
  -o /tmp/issue581-daemonitor-cored ./cmd/daemonitor-cored
```

`rich_spike_test.go` checks real JSON/binary parity for selected lifecycle,
command, memory/garbage, action/cache/runner, timing, test, and failure fields;
post-finish metrics ordering; existing JSON outcomes; malformed lengths/varints;
and skipping unknown fixed/varint/bytes fields. Both commands passed. Deliberately
changing the buildMetrics tag and removing the frame-size guard each caused the
corresponding test to fail; the original source was restored and tests passed.
The proposed SQL also passed an in-memory SQLite check for creation, foreign-key
rejection, cascading deletion, and its UTF-8 byte limit. The existing watcher test needs
host process enumeration (the sandbox returns EPERM). No production behavior was
changed, so there is no red-before-fix production regression. The new tests make
the spike's mapping and fixture claims executable. The full Gradle suite is left
to the host as requested.

Before production: add real persistent-worker, profiled-heap, warm-cache and
supported-Bazel-version captures; names-only attribution/ancestry fixtures with
ambient and conflicting signals; incremental rotation and duplicate-source tests;
privacy canaries proving values never reach storage/logs/API; migration/retention
and HTTP/MCP contract tests; and stripped binary size measurements before/after
adding the chosen decoder. None of those untested cases is claimed implemented.
