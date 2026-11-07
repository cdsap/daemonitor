# Cross-platform telemetry validation matrix

This is the release and change-validation checklist for the telemetry contract. A
successful cross-compile proves only that a binary can be produced; it is not
runtime evidence for the target OS or architecture.

## Requirement-to-evidence map

| Requirement | Evidence required | Automated gate |
|---|---|---|
| TEL-01 Process discovery and creation timestamps | A live process row has a PID, non-zero creation time when the OS exposes it, and the expected process kind | `poll` collector tests and the OS matrix's collector tests |
| TEL-02 PID reuse does not merge histories | A new `(host, PID, create time)` incarnation starts a new counter/history series | `poll` incarnation/CPU tests and lifecycle store tests |
| TEL-03 CPU cores-used is consistent | Cumulative CPU seconds are converted to cores independently of host-percent normalization | `poll` CPU tests, JSON snapshot tests, and the Linux dual-run smoke |
| TEL-04 Platform memory semantics are preserved | RSS remains RSS; platform-specific values are nullable with an explicit availability state | platform collector tests, capability API test, and serialization tests |
| TEL-05 JVM metrics are optional and isolated | Missing tools, attach failures, and timeouts retain the process row and report a diagnostic | heap probe tests and the JVM compatibility matrix |
| TEL-06 JSON schemas and capabilities are consistent | `/v1/processes`, `/v1/host`, `/v1/self`, and `/v1/capabilities` decode on every supported OS | API/client tests and native IPC smoke |
| TEL-07 Long sessions do not grow without bound | Run a long-session soak and record process, probe, and database-size observations | manual/integration checklist; attach the result to the tracking issue |

## Required environment matrix

| Environment | Required coverage | CI evidence | Status |
|---|---|---|---|
| macOS Intel | CLI, process collector, JVM probes | `macos-13` test and cored jobs | automated |
| macOS Apple Silicon | CLI, process collector, JVM probes | `macos-14` test and cored jobs | automated |
| Ubuntu x64 | CLI, JVM, `/proc`, optional PSS | `ubuntu-latest` test/cored jobs; Linux capability response | automated; PSS may be `not_collected` |
| Ubuntu ARM64 | CLI, JVM, `/proc`, optional PSS | `ubuntu-24.04-arm` cored job | automated where the hosted runner is available; PSS may be `not_collected` |
| Linux cgroup v2 | CPU and memory constraints | Linux cored job verifies `/sys/fs/cgroup/cgroup.controllers`; constraint results are recorded in the job summary | automated host gate; constrained soak is manual |
| Windows x64 | CLI, IPC, Win32 counters, JVM probes | `windows-latest` test/cored jobs and Windows normalization tests | automated |
| Windows ARM64 | Build/run parity where supported | CI validates ARM64 PE artifacts; no supported hosted Windows ARM64 runtime runner is configured | **unsupported for runtime CI**; do not infer runtime support from cross-compile success |
| JDK 17 / 21 / 25 | JVM telemetry compatibility | JVM probe compatibility matrix on Ubuntu for all three versions | automated |

The Windows ARM64 state must be revisited when a supported hosted or dedicated
runner is available. Until then, a release may carry a Windows ARM64 artifact
only as a cross-compiled artifact; it has no runtime validation claim.

## Performance gate

Issue #398 owns the benchmark harness. Do not add an overhead budget based on
intuition: first capture matched Daemonitor-on and Daemonitor-off baselines on
the same host and workload, then record the numeric budget and comparison in the
tracking issue. The `/v1/self` metrics (`poll_duration_ms`, probe counters, and
storage size) are supporting evidence, not a substitute for the benchmark.

## Manual/integration checklist for the tracking issue

- [ ] Attach one result for each required OS/architecture row, including runner and JDK versions.
- [ ] Confirm discovery, creation time, PID reuse, CPU cores-used, and memory semantics with a real process session.
- [ ] Confirm unavailable JVM tools leave OS rows present and expose a diagnostic/capability state.
- [ ] Exercise `/v1/processes`, `/v1/host`, `/v1/self`, and `/v1/capabilities` and attach representative JSON.
- [ ] Run a cgroup v2 constrained session and record CPU/memory observations.
- [ ] Run the long-session soak; attach process and database-size observations.
- [ ] Attach #398 baseline data before setting or changing numeric overhead budgets.
- [ ] If Windows ARM64 runtime evidence is unavailable, leave it marked unsupported and include the runner limitation as the reason.
