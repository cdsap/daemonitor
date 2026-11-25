---
title: Spike — Kotlin Toolchain / Amper CLI classification
created: 2026-11-24
status: ready
surface: daemonitor-cored poll + TUI/CLI labels + Kotlin classifier parity
origin: Local investigation 2026-11-24 — Kotlin Toolchain has no daemon; CLI is Amper MainKt
related: docs/plans/2026-11-10-1830-feat-bazel-cli-support-plan.md (classify-first template); existing ProcessType.KOTLIN_DAEMON (Gradle/Maven KotlinCompileDaemon — different process)
target_release: local spike first; promote only if Live flash is useful enough for a release slice
---

# Spike: Kotlin Toolchain / Amper CLI classification

## Goal

Prove Daemonitor can treat the **Kotlin Toolchain CLI** (Amper under the hood) as a first-class classified process during `kotlin build|test|run`, with live RSS/CPU (and optional HotSpot heap), without inventing a “Toolchain daemon” that does not exist.

This spike produces: settled type name, argv markers, dual-classifier parity, TUI/CLI label, and a manual verify recipe against a real `kotlin build`. It may land as a local branch; shipping to main is a separate call.

## Product framing

| Myth | Reality (Toolchain 0.14.1, probed 2026-11-24) |
|------|-----------------------------------------------|
| “Include the Toolchain daemon” | **No daemon.** Docs map `./gradlew stop` → “doesn't have a daemon to stop.” |
| Kotlin compile daemon under Toolchain | **Not used** — compilation is in-process inside the CLI JVM. |
| What we can show | Short-lived `org.jetbrains.amper.cli.MainKt` JVM for the life of the command. |

**Do not conflate with `KOTLIN_DAEMON`.** That type already means JetBrains `KotlinCompileDaemon` (Gradle/Maven). Toolchain CLI is a different binary role.

| Signal | Today | Spike target |
|--------|-------|--------------|
| Toolchain CLI argv | Dropped (unclassified) | `KOTLIN_TOOLCHAIN` row while command runs |
| Gradle Kotlin daemon | `KOTLIN_DAEMON` | Unchanged |
| Persistent hierarchy root | N/A (ephemeral) | **Not** a daemon-style root; behave like wrapper / standalone row |

## Evidence (local, 2026-11-24)

Installed Kotlin CLI **0.14.1** (`curl -fsSL https://kotl.in/install.sh`). Built a minimal `jvm/app` under `/tmp/kt-toolchain-probe-*`.

**Observed cmdline (stable marker):**

```text
…/bin/java @…/kotlin-cli-0.14.1/kotlin-cli.args -cp …/kotlin-cli-0.14.1/lib/* org.jetbrains.amper.cli.MainKt build
```

| Observation | Detail |
|-------------|--------|
| Main class | `org.jetbrains.amper.cli.MainKt` |
| Dist cache | `~/Library/Caches/JetBrains/Kotlin/cli/kotlin-cli-<ver>/` |
| Children | None for plain `jvm/app` build (no `KotlinCompileDaemon`) |
| Lifetime | Process exits when the command finishes |
| RSS (cold) | Climbed to ~537 MB then exited (~7s including JDK provision) |
| RSS (warm) | ~146 MB sample; build ~1s |
| Project path | Process **cwd** (no `--workspace_directory=` on argv) |
| Env | `KOTLIN_TOOLCHAIN_DISTRIBUTION_DIR`, `KOTLIN_CLI_WRAPPER_PATH` |
| Android hint | Dist ships `gradle-tooling-api` / `amper-android-gradle-api` — Android builds may still surface **Gradle** daemons Daemonitor already classifies |

Docs: [Migrating from Gradle](https://kotlin-toolchain.org/latest/getting-started/migrating-from-gradle/).

## Spike questions → answers

### Q1 — Type code name?

| Option | Pros | Cons |
|--------|------|------|
| `KOTLIN_TOOLCHAIN` | Matches product branding | Slightly vague that it is the CLI JVM, not a daemon |
| `KOTLIN_TOOLCHAIN_CLI` | Explicitly ephemeral CLI | Longer API string |
| `AMPER_CLI` | Accurate to main class / jars | Users see “Kotlin Toolchain,” not Amper |

**Settled for spike:** `KOTLIN_TOOLCHAIN`  
**Display label:** `Kotlin Toolchain`  
Rationale: product-facing Live/TUI copy; marker remains Amper-specific so we do not collide with `KOTLIN_DAEMON`.

### Q2 — Match rule?

**Must match** (strong):

- `org.jetbrains.amper.cli.MainKt`

**Optional secondary** (only if needed for odd launchers; do not use alone):

- classpath / path containing `kotlin-cli-` **and** `JetBrains/Kotlin/cli` — weaker; prefer main-class-only for v1 to avoid false positives.

**Order in `Classify`:** after `KOTLIN_DAEMON`, before Gradle worker/wrapper fallbacks (Toolchain is unrelated to Gradle markers).

### Q3 — Hierarchy root?

**No.** Treat like `GRADLE_WRAPPER`: appear as a flat / nestable row, not `isHierarchyRootType` alongside `GRADLE_DAEMON` / `BAZEL_SERVER`. Ephemeral single-JVM builds do not own a durable child tree. If future Android/worker children are classified, existing parent-chain nesting still applies without making Toolchain a root.

### Q4 — Live heap probe?

**Yes for the spike.** Allowlist `KOTLIN_TOOLCHAIN` in `ShouldProbeLiveHeap` (and Kotlin live-heap type set). The CLI *is* the compiler host and routinely holds hundreds of MB; unlike `GRADLE_WRAPPER`, heap is product-relevant while the row is visible.

### Q5 — Project path?

Use existing **cwd** attribution (same as most non-Bazel types). No argv workspace flag on 0.14.1.

### Q6 — Desktop / observation / history?

**Spike:** cored classify + `TypeDisplay` + Kotlin enum/classifier parity + focused tests + manual verify.  
**Defer:** Desktop icon polish, observation session summaries, build history, Android-specific child types, promoting to a versioned release until product confirms the Live “flash” is worth it.

## Approach under test (classify-first)

Mirror the Bazel CLI pattern, scaled to an ephemeral CLI:

1. Add `KOTLIN_TOOLCHAIN` to Go `Classify` + Kotlin `ProcessType` / `GradleProcessClassifier`.
2. Fixture from the captured 0.14.1 cmdline (and a negative: plain `KotlinCompileDaemon` still → `KOTLIN_DAEMON`).
3. `TypeDisplay` → `Kotlin Toolchain`; wire PreferGo / desktop enum parsers so unknown types do not break.
4. Heap allowlist; no hierarchy-root change.
5. Manual: `kotlin build` in a temp module while `daemonitor-cored` / CLI polls — row appears then disappears.

## Implementation units

1. **U1.** Go `Classify` regex + `poll_test` fixtures (positive Amper MainKt; negative daemon / unrelated java).
2. **U2.** `ShouldProbeLiveHeap` + `TypeDisplay`.
3. **U3.** Kotlin `ProcessType.KOTLIN_TOOLCHAIN` + classifier + unit test; display label / LiveMetricLabels heap set if required for parity.
4. **U4.** PreferGo / JSON enum mapping touch-ups if needed so desktop/CLI do not reject the new code.
5. **U5.** Manual verify script notes (temp `module.yaml`, `kotlin build`, confirm row + RSS; confirm no false `KOTLIN_DAEMON`).

## Non-goals

- Claiming or inventing a Toolchain “daemon”
- Changing behavior of `KOTLIN_DAEMON` (Gradle compile daemon)
- BEP/history for Toolchain builds
- Wrapping or invoking `kotlin` for discovery (argv/cwd only)
- Android Gradle-bridge worker taxonomy
- Release version bump / Portal publish

## Success criteria

- [ ] Unit tests green: Amper MainKt cmdline → `KOTLIN_TOOLCHAIN`; `KotlinCompileDaemon` still → `KOTLIN_DAEMON`
- [ ] During a live `kotlin build`, cored/CLI process list shows one `KOTLIN_TOOLCHAIN` with cwd project path and non-zero RSS
- [ ] After the build exits, the row disappears on the next poll (ephemeral honesty)
- [ ] Unrelated JVMs still classify empty / drop
- [ ] Short spike note in this file (or linked) with go/no-go for promoting to a feat slice

## Go / no-go (fill after U5)

| Outcome | When |
|---------|------|
| **Go — feat slice** | Row is readable in Live/TUI during real builds; no false positives; dual classifiers stay aligned |
| **Hold** | Flash too brief to be useful on warm builds, or product prefers waiting for a future Toolchain daemon/AOT resident process |
| **Revise naming** | If JetBrains drops Amper main-class branding or adds a real daemon marker |

## Definition of Done (spike)

- U1–U5 complete locally (branch optional).
- Success criteria checked; go/no-go filled.
- Plan path: `docs/plans/2026-11-24-1552-spike-kotlin-toolchain-cli-classification-plan.md`
