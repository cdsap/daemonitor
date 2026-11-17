# Daemon history correlation

Daemon build history uses the Gradle `DefaultDaemonContext` UID as the daemon-lifetime
identity. The UID is preferred over the operating-system PID because a PID can be reused
after a daemon exits. If a new UID is observed for a PID while a build window is open, the
old window is closed as `INTERRUPTED` before events for the new lifetime are processed.

Rows created by older database versions, or rows whose log context did not contain a UID,
remain queryable by PID for compatibility. They are not treated as exact lifetime matches:
machine-readable build responses expose `daemonIdentityConfidence: "UNKNOWN"` and a null
`daemonIdentity`. Rows with a parsed UID expose `daemonIdentityConfidence: "EXACT"`.

PID searches therefore remain useful for historical data, but clients must inspect the
confidence field before combining rows. Daemon identities and confidence metadata do not
change the existing redaction boundary: command lines and log snippets are still redacted
before persistence and JSON exposure.

## Live recent builds and Desktop History

Under PreferGo (default Desktop / CLI), `daemonitor-cored` owns writes to the shared SQLite
`builds` table. Desktop **History** and Live daemon-scoped **recent builds** both read that
same retained-build persistence path:

- History: `HistoryService` → `BuildRepository.recent()` on the shared database.
- Live recent builds / CLI `builds --pid` / `GET /v1/builds`: cored queries the same table
  (Desktop uses `DaemonBuildSource` over the core socket).

When cored inserts a finished build, PreferGo polling fingerprints the shared table and sets
`buildsChanged`. Desktop then refreshes History and, if a Gradle daemon is selected, reloads
Live recent builds within one poll cycle—without leaving History or reselecting the process.
