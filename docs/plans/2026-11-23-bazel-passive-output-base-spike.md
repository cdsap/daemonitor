# Bazel passive output_base observation (#579)

## Decision and scope

Passive observation is feasible, with a bounded snapshot reader for profiles and
a separate tail for each log. Do not open `<output_base>/lock`, invoke `bazel`,
or infer a successful build from a readable profile. Ship the follow-on reader
only after the ownership and native OS gates below pass. This spike adds
source-shaped fixtures and executable experiments, with no production watcher,
database, API or UI change.

Evidence collected on macOS (Darwin arm64), 2026-11-23:

- Reviewed version-pinned Bazel 7.4.1, 8.0.0 and 9.0.0 source, linked below.
- Added reduced fixtures under
  `cored/internal/bep/testdata/passive/`. These are constructed examples, not
  captured builds. Numeric values, UUIDs and paths are invented; optional
  events intentionally exercise shapes rather than default availability.
- `passive_spike*_test.go` exercises gzip EOF/CRC validation, read bounds,
  metadata differences, counter units, phase boundaries, same-size log
  replacement, symlink retargeting, truncation, CRLF and existing `RedactLogLine` behavior.
- No Bazel command was executed. Native Bazel builds and Linux/Windows runtime
  tests were not performed. Cross-compilation does not prove Windows sharing.

## Profiles: format and meaning

The envelope is `{"otherData": {...}, "traceEvents": [...]}`. Metadata contains
`build_id` (invocation UUID), `bazel_version` (for example `release 8.0.0`),
`output_base`, and an ISO-8601 `date`. The timestamp metadata differs:

| Fixture / upstream tag | Timestamp field | Meaning in writer source |
| --- | --- | --- |
| 7 / 7.4.1 | `profile_finish_ts` | `Instant.now()` before events are drained; seconds × 1000 |
| 8 / 8.0.0 | `profile_start_ts` | Writer start wall time in milliseconds |
| 9 / 9.0.0 | `profile_start_ts` | Writer start wall time in milliseconds |

The 7.x field name does **not** establish build completion time. The newer field
also does not establish the exact invocation start: trace timestamps are
relative to the profiler's monotonic origin, whereas metadata samples wall time
in the writer thread. Retain the metadata as a profile timestamp with its
provenance; do not derive authoritative epoch start/end from `date + ts`.
Unknown version strings and absent timestamp fields must remain nullable.
Sources: [7.4.1 writer](https://github.com/bazelbuild/bazel/blob/7.4.1/src/main/java/com/google/devtools/build/lib/profiler/JsonTraceFileWriter.java),
[8.0.0 writer](https://github.com/bazelbuild/bazel/blob/8.0.0/src/main/java/com/google/devtools/build/lib/profiler/JsonTraceFileWriter.java),
[9.0.0 writer](https://github.com/bazelbuild/bazel/blob/9.0.0/src/main/java/com/google/devtools/build/lib/profiler/JsonTraceFileWriter.java).

Trace `ts` and `dur` are microseconds, not milliseconds. `pid: 1` is a trace
process lane, **not the Bazel OS PID**. `tid` is likewise a trace lane. `ph: M`
contains thread metadata, `ph: i` instant markers, `ph: X` complete duration
events, and `ph: C` sampled counters. Tolerate unknown fields/events.

Phase markers use category `build phase marker`. Instant markers such as
`Build artifacts` and `Complete build` delimit an interval; they generally do
not carry `dur`. Preserve ordered markers; derive an interval only when both
boundaries exist. Launch can be a complete event at a negative timestamp.
Interleaved loading/analysis/execution must retain its own phase name rather
than inventing separate durations. Nested action durations cannot be summed to
get wall time. Critical-path component durations are a distinct trace metric.
Sources: [phase definitions](https://github.com/bazelbuild/bazel/blob/9.0.0/src/main/java/com/google/devtools/build/lib/profiler/ProfilePhase.java),
[task serialization](https://github.com/bazelbuild/bazel/blob/7.4.1/src/main/java/com/google/devtools/build/lib/profiler/Profiler.java).

| Counter/event | Interpretation for follow-on schema |
| --- | --- |
| `CPU usage (Bazel)`, `args.cpu` | Core equivalents: 1.5 means approximately 150% of one CPU, not 1.5% |
| `Memory usage (Bazel)`, `args.memory` | JVM heap used in MiB; not RSS or total build memory |
| `action count`, `args.action` | Bucketed action concurrency; may be fractional, not total completed actions |
| `gc notification`, duration event | Observed GC notification event duration; no guaranteed cumulative count/time series |
| Worker/system CPU/memory counters | Separate scopes; never substitute for server heap/RSS |

Counter presence depends on command, options, duration and platform. Missing
means unknown, not zero. The 7.4.1 serializer can omit zero-valued arguments.
Worker lane labels change between 8 and 9. Match an allowlisted lane **and**
argument key, not an arbitrary `memory` key or lane spelling alone. Optional GC
events do not establish a complete GC history; the spike does not promise
default GC totals. Sources: [7.4.1 counter writer](https://github.com/bazelbuild/bazel/blob/7.4.1/src/main/java/com/google/devtools/build/lib/profiler/CounterSeriesTraceData.java),
[8.0.0 collector](https://github.com/bazelbuild/bazel/blob/8.0.0/src/main/java/com/google/devtools/build/lib/profiler/CollectLocalResourceUsage.java),
[9.0.0 collector](https://github.com/bazelbuild/bazel/blob/9.0.0/src/main/java/com/google/devtools/build/lib/profiler/CollectLocalResourceUsage.java).

## Profile publication and completion contract

Do not assume atomic rename of a completed gzip. The writer streams events and
closes the JSON array/object during shutdown. Bazel 8's default profile manager
selects `command-<UUID>.profile.gz`; the convenience name `command.profile.gz`
can be a symlink. Link creation itself uses delete/create. Missing links,
retention deletions and incomplete targets are normal races.
Sources: [profile management](https://github.com/bazelbuild/bazel/blob/8.0.0/src/main/java/com/google/devtools/build/lib/runtime/BlazeRuntime.java),
[local instrumentation output](https://github.com/bazelbuild/bazel/blob/9.0.0/src/main/java/com/google/devtools/build/lib/runtime/LocalInstrumentationOutput.java).

Settled reader sequence for the follow-on:

1. Derive the base only from an owned `BAZEL_SERVER`'s raw argv
   `--output_base=` argument. Use argument boundaries, not whitespace splitting
   of rendered argv. No directory-wide discovery or `bazel info`.
2. Validate the base and selected file under the ownership policy below. Permit
   the convenience symlink only to a same-base regular profile target; reject
   escapes, nested link chains and reparse points not explicitly supported.
3. Observe target identity, size and mtime unchanged across two polls at least
   one second apart. This is a work-avoidance heuristic, not proof of closure.
4. Open read-only; compare handle identity with the selected target. Read a
   bounded snapshot; restat the handle and path after reading. Reject changed
   identity, size, mtime or link target, even for a same-sized replacement.
5. Consume gzip through EOF to validate CRC/trailer, then require exactly one
   complete JSON object with a valid UUID and an event array. A JSON decoder
   returning at `}` and `gzip.Close()` alone do not validate the gzip trailer.
6. Publish only the accepted snapshot. Failed/incomplete snapshots yield no row
   and are retried on later polls; never mark their UUID as consumed. Cap repeat
   decoding to once per five seconds per unchanged failed fingerprint. Bound
   tracked bases to live servers and discard state when their incarnation ends.

The tests show a stable truncated gzip fails validation, then succeeds after
completion. No passive metadata check proves the producer has closed its file;
full validation prevents partial streams from becoming records. On filesystems
with weak identity/mtime semantics, skip observation rather than claim safety.

`--nogenerate_json_trace_profile`, custom `--profile`, redirected instrumentation,
non-profiled commands and retention may leave no new default profile. A previous
profile may remain. Absence is not failure; never manufacture a new build from
a stale mtime, log summary or polling timestamp. An older accepted UUID may be
retained as historical evidence, but cannot be assigned to the current server
incarnation without independent correlation.

## Logs and OS caveats

`command.log` contains the latest command's interleaved output. Bazel 7/8 delete
the old file and open with append disabled; Bazel 9 does the same for local
instrumentation. `clean`, disabled logging and redirected instrumentation may
leave it absent. A tail must detect replacement using file identity as well as
size, reset offsets and pending partial lines on replacement/truncation, and
handle same-inode rewrites without assuming append-only behavior. Same-size
rewrites that evade identity/mtime checks cannot provide reliable boundaries.
Sources: [7.4.1 command log](https://github.com/bazelbuild/bazel/blob/7.4.1/src/main/java/com/google/devtools/build/lib/runtime/CommandLogModule.java),
[9.0.0 command log](https://github.com/bazelbuild/bazel/blob/9.0.0/src/main/java/com/google/devtools/build/lib/runtime/CommandLogModule.java).

| OS | Evidence / required behavior |
| --- | --- |
| macOS | Synthetic read handle + unlink/recreate and truncation tests pass. Reopen by pathname on the next poll; an old descriptor can still refer to the removed file. Native Bazel concurrency remains untested. |
| Linux | Same POSIX strategy proposed. Run the fixture replacement tests and native concurrent Bazel probe on Linux before claiming verification. |
| Windows | Use a read handle with `FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE`, `OPEN_EXISTING`, no lock. Ordinary Go `os.Open` omits delete sharing and can block Bazel's unlink. A test-only opener demonstrates the intended flags; native writer compatibility is still a release gate. Close promptly and skip sharing violations. |

Never hold a reader across polls. No advisory locks or Windows byte-range locks
are needed. A Windows reader must allow deletion even though it only reads.
Delete sharing alone does not guarantee immediate recreation: Windows
`DeleteFile` may leave the name delete-pending until the last handle closes.
The Windows experiment closes its reader after deletion before recreating the
file; it does not prove that Bazel can immediately recreate it with an open
reader. Keep Windows `command.log` observation disabled until that native
overlap test passes for the actual Bazel writer.
Compare Windows volume/file IDs, not invented inode values. Validate the opened
handle's final path, owner and DACL; Go permission bits alone do not establish
Windows privacy. The Windows server-output writer requests read sharing and
may truncate or append depending on startup settings, so read errors/restarts
must be tolerated. Source:
[native Windows daemon output](https://github.com/bazelbuild/bazel/blob/9.0.0/src/main/cpp/blaze_util_windows.cc).

`INFO: Invocation ID: <UUID>` is useful when present, but not guaranteed for
every command/logging mode. `INFO: Elapsed time: ... Critical Path: ...`,
`Build completed successfully` and `FAILED: Build did NOT complete successfully`
are parseable English summary forms, not a stable machine protocol. ANSI,
progress carriage returns, timestamp prefixes, suppressed output and user
actions printing similar text must be considered. Only attach summary evidence
inside an explicitly identified invocation segment. Without its UUID, retain
an optional live diagnostic tail, not a build-history row or authoritative
outcome. Never associate a new `command.log` summary with an old profile by
mtime proximity. See the [Bazel user manual](https://bazel.build/run/build#info).

`server/jvm.out` is JVM stdout/stderr, separate from Bazel's Java debug log. It
can be empty, contain only startup warnings, or contain optional GC output.
Treat it as a server-incarnation tail with replacement/truncation handling;
it is not a universal source of build UUIDs, outcomes or invocation boundaries.
The fixture intentionally contains no Bazel version or UUID.

## Server identity and version

Use `(OS PID, process start time)` for server incarnation, plus the validated
output-base path for correlation. `--install_base=` is a cache/install path,
often ending in an opaque installation hash; neither that hash nor a
user-chosen basename is a reliable semantic version. Do not execute binaries
or recursively inspect installation contents to derive a version. Sources:
[output directory layout](https://bazel.build/remote/output-directories),
[startup argument handling](https://github.com/bazelbuild/bazel/blob/9.0.0/src/main/java/com/google/devtools/build/lib/runtime/BlazeRuntime.java).

The profile's explicit `bazel_version` reliably describes **that invocation**.
Normalize a recognized `release <version>` string, retaining unknown otherwise;
only propagate it to the server when the invocation's server association is
confirmed. An unambiguous version banner in `jvm.out` may be supplementary
evidence, but no required banner was established. Version must remain unknown
when neither source supplies reliable evidence. Never use the embedded JDK
version as the Bazel version.

## Settled schema and deduplication contract

This is the contract for follow-on migrations/ingestion, not an implemented API.

| Record / field | Contract |
| --- | --- |
| Invocation key | Existing `build_id`: validated UUID, normalized lowercase, shared by profile and BEP `started.uuid`; `tool=BAZEL` |
| `sources` | Set of `PROFILE`, `BEP`, optionally `COMMAND_LOG`; retain field provenance, not a single last-writer label |
| Server association | Nullable PID/incarnation, validated base; never trace `pid=1` |
| Command, workspace, epoch start/end, duration, outcome | BEP authoritative when present; profile does not invent them |
| Profile timestamp | Nullable writer wall time plus original metadata field name and version; separate from invocation start/end |
| Profile metrics | Nullable `heap_peak_mib`, `cpu_peak_cores`, `action_concurrency_peak`, phase intervals in microseconds, observed GC durations and critical-path duration; missing values remain null |
| Version | Nullable explicit version with source; base/hash is identity evidence only |
| Log evidence | Bounded, redacted summary attached only through an explicit matching UUID; no raw profile/event payload retention |

Do not place profile heap into existing RSS `peak_memory_mb` or counter CPU into
`peak_cpu_percent` without an explicit unit/scope conversion. Existing
`builds.Build.StartTimeMs` is non-nullable: follow-on schema work must support
unknown epoch times (and ordering by separate observation time) before profile
rows ship. Profile closure alone has `COMPLETED_NO_OUTCOME`, never `SUCCESS`.
A log-derived outcome is supplementary and must not override BEP.

Merge rules, independent of arrival order:

1. Upsert one invocation per UUID. Profile first creates partial evidence; BEP
   later enriches the same row. BEP first creates the authoritative row; profile
   later adds only its metrics/version evidence. Repeats are idempotent.
2. Merge per field: non-null BEP values win on command, workspace, timestamps,
   duration and outcome; keep profile-only metrics. Missing BEP fields must not
   erase profile evidence. Repeated identical profiles must not downgrade a row.
3. Never deduplicate by PID, output base, mtime, command text or time proximity.
   Distinct UUIDs remain distinct. Missing/invalid IDs produce no durable row.
   A contradictory base/server for an existing UUID is rejected as conflicting
   evidence rather than silently reassigned.
4. Update merged evidence and the build row in one transaction. Persistent UUID
   uniqueness handles restart/replay; bounded in-memory caches only avoid work.
   A profile-seen cache must not suppress later BEP enrichment.

Current `bep.Watcher.seenIDs` and `store.InsertBuild` are **not sufficient** for
this contract: the former drops repeated IDs, while the latter overwrites some
fields with nulls and leaves association/source/start fields unchanged on
conflict. Do not connect a profile reader to these unchanged. Required follow-on
tests must cover both arrival orders, repeat/restart, null enrichment, distinct
UUIDs and conflicting association, through persistence rather than a mock merge.

## Privacy and resource bounds

Settled initial limits: 16 MiB compressed profile, 64 MiB decompressed, 200,000
trace events, at most one profile decode per base per poll; skip larger profiles
without partial metrics. A production parser must stream/skip events to enforce
the event cap without allocating an entire decoded object. The test decoder
only demonstrates byte limits and full gzip validation.

For each log use a 256 KiB initial suffix, 16 KiB subsequent read per poll,
16 KiB maximum pending line, and a retained tail of at most 100 lines / 16,000
characters. Discard an initial partial line and oversized lines in full; handle
split UTF-8/CRLF before presentation. Apply redaction before any retained buffer,
IPC, database, debug output or error context. No raw paths/content in diagnostics.

`poll.RedactLogLine` masks URL user/password and sensitive `--name=value`,
`-Dname=value`, `-Pname=value` tokens. It does **not** sanitize arbitrary action
stdout, bearer headers, split option/value pairs or every quoting form. The
tests pin that limitation. Therefore default passive retention is restricted to
allowlisted summaries; arbitrary action output needs a separate privacy design.
Profile event names/args may contain labels, paths, commands and secrets:
extract allowlisted numeric fields and fixed metadata only; drop raw names,
paths, unknown args and environment values. Redaction is not anonymization.

Before opening, require the discovered server to belong to the current user.
On POSIX, require current effective UID ownership and no group/other access on
the base and selected regular file (owner-only base/files; never chmod user
files). Reject symlink escapes, special files and writable ancestor attacks;
use descriptor-relative no-follow opens and validate descriptor metadata to
avoid stat/open races. For the allowed profile convenience link, open its
validated direct same-base target with no-follow semantics. Check the owned
`server` directory for `jvm.out` as well. Fail closed on unverifiable ownership.
Windows needs equivalent owner SID/DACL and reparse-point validation.

These ownership checks are requirements for the production reader, not claims
about the current Gradle/BEP watchers or this test-only spike. No generic
ownership implementation is added here. Strict owner-only policy can skip
otherwise readable default Bazel output; that is an explicit privacy tradeoff.

## Validation and remaining gates

Run from `cored`:

```sh
go test ./internal/bep -run PassiveSpike -count=1
go test ./internal/bep ./internal/poll -count=1
GOOS=windows GOARCH=amd64 go test -c -o /tmp/daemonitor-bep-windows.test.exe ./internal/bep
```

The spike tests and both focused packages passed on macOS with Go 1.26.2. The
existing watcher integration test needs host process enumeration: it returned
`operation not permitted` under the sandbox and passed outside it. The full
repository/Gradle suite is left to the trusted host as requested.
The Windows amd64 test binary cross-compiled successfully; it was not executed.

Before implementation rollout, capture sanitized real builds for the three
pinned major versions on macOS/Linux/Windows; include success, failure,
no-op/query, disabled/custom profile, server restart, and simultaneous readers.
Record metadata/identity before/during/after writes and compare writer completion
with accepted snapshots. Exercise symlink switches, same-size rewrites, interrupted
gzip, owner/DACL rejection, hostile paths, oversized files and gzip bombs. On
Windows verify Bazel command-log deletion while the shared-delete reader is open,
not just replacement by a Go writer. Measure build latency with/without readers;
this spike makes no zero-contention timing claim. A failed sharing check disables
that file source rather than risking a build.

Non-goals: production scheduling, recursive output-base discovery, custom profile
path following, binary BEP decoding, new IPC/UI, database migration, exact epoch
times from trace offsets, complete historical recovery, arbitrary log retention,
live metrics from an unfinished gzip, reliable outcomes without BEP, Bazel
execution, lock acquisition, and claiming native OS support from fixtures alone.
