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
