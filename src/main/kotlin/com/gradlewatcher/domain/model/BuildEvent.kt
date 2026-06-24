package com.gradlewatcher.domain.model

/**
 * Events parsed from a Gradle daemon `.out.log` (U3). A daemon serves many builds over its
 * lifetime, so these events — not process polling — are the authoritative per-build source
 * (KTD-1). Two line grammars produce them: prefixed structured lines (busy/idle/start/env/context)
 * and the bare, unprefixed outcome line.
 */
sealed interface BuildEvent

/** `DefaultDaemonContext[uid=…,…,daemonOpts=…]` — supplies the daemon's stable identity (HTD). */
data class DaemonContextEvent(
    val timestampMs: Long,
    val uid: String?,
    val daemonOpts: String?,
) : BuildEvent

/** `Marking the daemon as busy` — opens a candidate window (not yet a confirmed build). */
data class BusyMark(val timestampMs: Long) : BuildEvent

/** `Marking the daemon as idle` — closes the current window. */
data class IdleMark(val timestampMs: Long) : BuildEvent

/**
 * Positive build-start marker that *qualifies* a candidate window as a real build (KTD-1):
 * either `Daemon is about to start building Build{id=…, currentDir=…}` or
 * `Starting [Nth] build in [new] daemon`. `buildId`/`currentDir` are null for the latter form.
 */
data class BuildStart(
    val timestampMs: Long,
    val buildId: String?,
    val currentDir: String?,
) : BuildEvent

/** `Configuring env variables: [NAME, NAME, …]` — env-var NAMES only, for source detection (KTD-8). */
data class BuildEnvNames(
    val timestampMs: Long,
    val envNames: List<String>,
) : BuildEvent

/** Bare `BUILD SUCCESSFUL|FAILED in <dur>` line. Has no timestamp; bound to the open window in U5. */
data class Outcome(
    val success: Boolean,
    val durationSeconds: Double,
) : BuildEvent
