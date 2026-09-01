<p align="center">
  <img src="icons/daemonitor.png" width="128" alt="Daemonitor icon">
</p>

# Daemonitor

> Activity Monitor for your Gradle daemons — what's building now, and what built recently.

Daemonitor is a local desktop app for Gradle on your machine. It shows which daemons, wrappers, and
test workers are running, how much memory and CPU they use, recent daemon-log lines, and a searchable
history of past builds — including which coding agent started them when that can be detected.

Data stays on your machine. Command lines and logs are redacted before storage. The only outbound
network use is GitHub Releases update checks (at startup or from Settings) and installer downloads
you approve.

[![CI](https://github.com/cdsap/daemonitor/actions/workflows/ci.yml/badge.svg)](https://github.com/cdsap/daemonitor/actions/workflows/ci.yml)
![Platforms](https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-blue)

Website: <https://cdsap.github.io/daemonitor/>

---

## Features

### Live Monitor

![Live monitor](docs/images/live-monitor.png)

- Running Gradle-related JVMs, by type:
  - 🐘 Gradle daemon · 🐘+🔧 wrapper · Kotlin daemon · 🧪 test worker · ☕ other related JVM
- RSS, CPU, and uptime per process
- Summary stats: active processes, total RSS, highest-memory PID, active projects
- Badges for high/critical memory, `MULTI-BUILD`, and `AUTOMATED`
- Detail panel: `-Xmx`, GC, working dir, redacted command line, live daemon-log tail
- Toolbar action to switch to headless collection (and MCP status when enabled)

### Visual

![Visual memory chart](docs/images/process-visual.png)

- Rolling RSS and configured-heap (`-Xmx`) timelines
- Solid lines for RSS, dashed for `-Xmx` when known
- Click the legend to hide or focus a series

### Historical

![Build history](docs/images/build-history.png)

- Reconstructed builds with time, project, duration, peak RSS, status, source, and agent
- Filter by project and time range
- Detail view with resource peaks and a log excerpt

### Settings

- History retention (default 15 days, range 1–90). Lowering it deletes older entries right away.

### Headless collection

Keep collecting without the desktop window via the toolbar or `--headless`. Same local database and
settings as the desktop app. On supported OSes, a tray/menu-bar icon offers **Open Daemonitor** and
**Quit Daemonitor**.

### MCP access

Optional local, read-only MCP server so agent tools can inspect retained history and current
Gradle-related processes. Same SQLite database as the desktop app. Tools:

- `daemonitor_search_history` — search builds by id, command, project, status, source, or agent
- `daemonitor_builds_for_process` — builds and samples for a daemon PID or process text
- `daemonitor_current_processes` — Gradle-related processes visible right now

### Agent attribution

Daemonitor guesses the coding agent from environment-variable *names* in the daemon log (never
values). It knows Claude Code, Cursor, Codex, Gemini CLI, and Aider, and marks unknown agents
explicitly.

It also ignores its own ambient environment. If Daemonitor is running inside Claude Code, those
variables are not treated as proof that every build came from Claude.

---

## Requirements

- **JDK 17+** to run Gradle from source (the build uses a Java 21 toolchain; Gradle can provision it)
- macOS, Linux, or Windows
- Permission to read your own process list and Gradle daemon logs under `~/.gradle/daemon/<version>/`

## Install

| OS | First-time installer | In-app update package |
|----|----------------------|------------------------|
| macOS | `.dmg` | `.zip` app bundle |
| Windows | `.msi` | `.zip` app directory |
| Linux | `.deb` | `.tar.gz` standalone image |

Release asset names include the CPU architecture (`x64` or `arm64`), e.g.
`Daemonitor-1.0.7-macos-arm64.dmg`.

No project-local Gradle setup is required. The app only reads process metadata and daemon logs for
the current user.

On Linux, prefer the GitHub Releases `.deb` for installs and `.tar.gz` for writable standalone
updates. Package-managed prompts stay advisory; see
[Linux Update Distribution](docs/linux-update-distribution.md).

## Updates

Daemonitor checks GitHub Releases once at startup and marks Settings with `(1)` when a newer version
exists. You can also check manually from Settings. After you approve a download, it picks the
matching artifact for your OS and arch, verifies the SHA-256 checksum, then either stages a
**Restart and Update** package or opens the platform installer. It does not install updates silently
while running.

## Run from source

```bash
./gradlew run
```

Headless (no Compose / display):

```bash
./gradlew runHeadless
```

Packaged launchers also accept `--headless`. Desktop and headless share the database and retention
setting. Stop a source-run headless process with `Ctrl+C`.

## Connect MCP

From the desktop app:

1. Open Daemonitor → Settings
2. Enable MCP
3. Copy the local URL and token into your MCP client

Example HTTP config:

```json
{
  "mcpServers": {
    "daemonitor": {
      "url": "http://127.0.0.1:17333/mcp",
      "headers": {
        "Authorization": "Bearer <token from Daemonitor Settings>"
      }
    }
  }
}
```

The server binds to `127.0.0.1` only and needs the Settings token. Keep Daemonitor open while
connected. It is read-only — it does not start, stop, or change builds.

For stdio clients, use the packaged launcher with `--mcp`. Do not point a stdio client at
`./gradlew run`; Gradle's own stdout will corrupt MCP messages. For local development, package first
and use the launcher under `build/compose/binaries/main/app/`.

```json
{
  "mcpServers": {
    "daemonitor": {
      "command": "/Applications/Daemonitor.app/Contents/MacOS/Daemonitor",
      "args": ["--mcp"]
    }
  }
}
```

Replace `command` with your install path. The stdio server exits when the client disconnects.

## Build a native distribution

Build on the target OS:

```bash
./gradlew packageDmg          # macOS (.dmg)
./gradlew packageMsi          # Windows (.msi)
./gradlew packageDeb          # Linux (.deb)
```

Mac App Store experiments use a separate channel (see
[docs/mac-app-store-distribution.md](docs/mac-app-store-distribution.md)):

```bash
./gradlew packagePkg -Pdaemonitor.distribution=APP_STORE
```

Tag-triggered releases also publish `latest.json`, `update.json`, and `checksums.txt`. See
[docs/update-metadata.md](docs/update-metadata.md).

## Test

```bash
./gradlew test
```

Unit tests cover collection, classification, and aggregation. Compose UI tests mount the real
screens. CI runs on Linux, Windows, and macOS (Linux uses a virtual display for UI tests).

### Updating README screenshots

Screenshots are rendered from synthetic sample state at the app's 1180×760 window size. After a UI
change:

```bash
./gradlew captureReadmeScreenshots test
```

Review `docs/images/` before committing. Keep samples synthetic — don't capture a live session with
personal paths or secrets.

---

## How it works

Two signals:

1. **Process polling** ([OSHI](https://github.com/oshi/oshi), every 2s) — RSS, CPU, uptime, and JVM
   flags for your Gradle-related JVMs.
2. **Daemon-log parsing** — build start/end, outcome, working directory, and env-var names for
   source/agent attribution.

A build is reconstructed by matching a daemon's busy→idle window (with a real build start inside it)
to resource samples in that window. Confirmed builds go to a local SQLite database (owner-only
permissions; excluded from Time Machine on macOS) and are purged by the retention setting.

### Where data lives

| OS | Path |
|----|------|
| macOS | `~/Library/Application Support/Daemonitor` |
| Linux | `$XDG_DATA_HOME/Daemonitor` (or `~/.local/share/Daemonitor`) |
| Windows | `%LOCALAPPDATA%\Daemonitor` |

Contains `watcher.db` (history) and `settings.properties` (retention).

## Tech stack

Kotlin · Compose for Desktop (Material 3) · OSHI · SQLDelight + JDBC SQLite · Kotlin Coroutines

## Privacy

Everything is local. Command lines and daemon-log lines are redacted before storage; the database is
owner-only. Outbound requests are limited to GitHub Releases update checks and downloads you
approve.

## Status

Early (v1.0.7). Heuristics are still evolving; see `requirements.md` for the original spec.
