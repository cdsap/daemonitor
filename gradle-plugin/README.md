# Daemonitor Gradle plugin (`io.github.cdsap.daemonitor`)

## Why a Gradle plugin?

Daemonitor’s desktop app and CLI already watch Gradle JVMs on the machine (daemons,
wrappers, Kotlin daemons, workers). That live view does **not** know which samples belong
to **this** Gradle invocation—especially in CI, where nobody is staring at a monitor.

This plugin exists to mark the build window:

1. At build start it opens a named **observation session** on a running `daemonitor-cored`.
2. During the build, cored keeps sampling as usual.
3. At build end it closes the session and receives one summary for processes in that window
   (RSS, and heap/GC when HotSpot probing succeeded), including process types such as
   `GRADLE_DAEMON` and `KOTLIN_DAEMON`.

You need the plugin when you want **per-build** process metrics—local report, optional
[GBOS](https://github.com/cdsap/build-observability-core) / Develocity custom values, and
MCP-searchable history—without an interactive Daemonitor session during the build.

You do **not** need the plugin for interactive Activity Monitor use of the desktop app or
`daemonitor-cli`. Those work without applying anything to the project.

## Requirements

- `daemonitor-cored` reachable on the default local socket (install the desktop app or CLI
  package from [Releases](https://github.com/cdsap/daemonitor/releases/latest)), **or** set
  `-Pdaemonitor.cored.socket=/path/to/cored.sock`.
- If cored is missing, the plugin warns and skips measurement; the Gradle build still succeeds.

## Apply

```kotlin
// settings.gradle.kts — Settings plugin (apply here, not in build.gradle.kts)
plugins {
    id("io.github.cdsap.daemonitor") version "0.1.2"
}
```


Single GBOS switch (default **on**):

```properties
daemonitor.gbos.enabled=true
```

On a successful measurement the plugin writes
`build/daemonitor/observation-summary.json`. When Develocity is on the classpath, the same
summary is projected as GBOS custom values.

## More

- Product overview: [README](../README.md)
- Website: https://cdsap.github.io/daemonitor/#gradle-plugin
- Release / Portal smoke: [docs/gradle-plugin-release-smoke.md](../docs/gradle-plugin-release-smoke.md)
