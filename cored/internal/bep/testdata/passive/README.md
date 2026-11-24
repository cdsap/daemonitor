# Passive Bazel spike fixtures

These are hand-reduced, source-shaped examples for issue #579, **not captured
builds**. IDs, paths, dates, actions and values are invented. No fixture proves
native OS sharing behavior or that optional counters appear on every build.
The Windows path in the Bazel 9 example tests JSON portability only.

`bazel-{7,8,9}.profile.json` model Bazel 7.4.1, 8.0.0 and 9.0.0 respectively.
Keep JSON reviewable; the tests compress it in memory and exercise real gzip
trailers, truncation, corruption and size limits without binary artifacts.
The log `.txt` files model `command.log` success/failure and optional HotSpot
`server/jvm.out` diagnostics. `jvm.out` need not contain a Bazel version, build
UUID or any build output. It is distinct from the Java server debug log.

Provenance: version-pinned Bazel sources under
`https://github.com/bazelbuild/bazel/blob/<version>/src/main/java/com/google/devtools/build/lib/`:

- `profiler/JsonTraceFileWriter.java`: metadata and trace envelope; 7.4.1 has
  `profile_finish_ts`, 8.0.0/9.0.0 have `profile_start_ts`.
- `profiler/Profiler.java`, `ProfilePhase.java`, `ProfilerTask.java`: phase,
  action count and GC event shapes.
- `profiler/CounterSeriesTraceData.java`, `CollectLocalResourceUsage.java`:
  counter arguments, units and optional worker series. Worker lane name changes
  from `Workers memory usage` in 8.0.0 to `Total worker memory usage` in 9.0.0.
- `runtime/CommandLogModule.java`, `BuildSummaryStatsModule.java` and
  `buildtool/BuildResultPrinter.java`: command log lifecycle and summary text.

See [the report](../../../../../docs/plans/2026-11-23-bazel-passive-output-base-spike.md)
for schema decisions, source links, limits and remaining native validation.
