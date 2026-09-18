package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.BuildEnvNames
import io.github.cdsap.daemonitor.domain.model.BuildEvent
import io.github.cdsap.daemonitor.domain.model.BuildStart
import io.github.cdsap.daemonitor.domain.model.BusyMark
import io.github.cdsap.daemonitor.domain.model.DaemonContextEvent
import io.github.cdsap.daemonitor.domain.model.IdleMark
import io.github.cdsap.daemonitor.domain.model.Outcome
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Pure parser for Gradle daemon `.out.log` lines (U3). Uses two grammars, because the outcome line
 * is structurally different from marker lines (verified against real logs):
 *  - prefixed: `<ISO-ts> [LEVEL] [logger] message`
 *  - bare:     `BUILD SUCCESSFUL|FAILED in <dur>`  (no timestamp/level/logger prefix)
 */
object DaemonLogParser {

    // e.g. 2026-06-24T14:42:12.402-0700 [INFO] [org.gradle...DaemonRegistryUpdater] message
    private val PREFIXED = Regex("""^(\S+) \[(\w+)\] \[([^\]]+)\] (.*)$""")
    private val OUTCOME = Regex("""^BUILD (SUCCESSFUL|FAILED) in (.+?)\s*$""")
    private val BUILD_ID_DIR = Regex("""Build\{id=([^,]+), currentDir=([^}]+)\}""")
    private val STARTING_BUILD = Regex("""^Starting (\d+(?:st|nd|rd|th)|build in new) (?:build in )?daemon""")
    private val ENV_LIST = Regex("""Configuring env variables: \[([^\]]*)\]""")
    private val CONTEXT_UID = Regex("""uid=([^,]+)""")
    private val CONTEXT_OPTS = Regex("""daemonOpts=([^\]]*)""")

    // Tolerate 0..9 fractional-second digits (Gradle uses 3, but be defensive) and a numeric offset.
    private val TS_FORMAT = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .appendPattern("Z")
        .toFormatter()

    /** Parse a full log (or an incremental chunk) into the events it contains, in order. */
    fun parse(lines: Sequence<String>): List<BuildEvent> =
        lines.mapNotNull { parseLine(it) }.toList()

    fun parseLine(line: String): BuildEvent? {
        OUTCOME.matchEntire(line)?.let { m ->
            return Outcome(
                success = m.groupValues[1] == "SUCCESSFUL",
                durationSeconds = parseDuration(m.groupValues[2]),
            )
        }

        val pre = PREFIXED.matchEntire(line) ?: return null
        val ts = parseTimestamp(pre.groupValues[1]) ?: return null
        val msg = pre.groupValues[4]

        return when {
            msg.startsWith("Marking the daemon as busy") -> BusyMark(ts)
            msg.startsWith("Marking the daemon as idle") -> IdleMark(ts)

            msg.contains("about to start building Build{") -> {
                val m = BUILD_ID_DIR.find(msg)
                BuildStart(ts, buildId = m?.groupValues?.get(1)?.trim(), currentDir = m?.groupValues?.get(2)?.trim())
            }

            STARTING_BUILD.containsMatchIn(msg) -> BuildStart(ts, buildId = null, currentDir = null)

            msg.contains("Configuring env variables: [") -> {
                val names = ENV_LIST.find(msg)?.groupValues?.get(1)
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: emptyList()
                BuildEnvNames(ts, names)
            }

            msg.contains("DefaultDaemonContext[") ->
                DaemonContextEvent(
                    ts,
                    uid = CONTEXT_UID.find(msg)?.groupValues?.get(1),
                    daemonOpts = CONTEXT_OPTS.find(msg)?.groupValues?.get(1),
                )

            else -> null
        }
    }

    /** Parse Gradle duration shapes: `38s`, `280ms`, `1m 2s`, `2m`. Returns seconds. */
    fun parseDuration(text: String): Double {
        val t = text.trim()
        Regex("""^(\d+)ms$""").matchEntire(t)?.let { return it.groupValues[1].toLong() / 1000.0 }

        var seconds = 0.0
        Regex("""(\d+)m(?![s])""").find(t)?.let { seconds += it.groupValues[1].toLong() * 60 }
        Regex("""(\d+(?:\.\d+)?)s""").find(t)?.let { seconds += it.groupValues[1].toDouble() }
        return seconds
    }

    private fun parseTimestamp(raw: String): Long? =
        runCatching { OffsetDateTime.parse(raw, TS_FORMAT).toInstant().toEpochMilli() }.getOrNull()
}
