---
title: Go core IPC spike
created: 2026-09-21
status: spike-in-progress
---

# Go core + IPC spike

## Goal

Prove a **Go long-running core** can own Gradle-related process polling, persist samples, and serve
snapshots to thin clients over **Unix-domain-socket HTTP** — including a JVM client — as a path
toward CLI/desktop without embedding the JVM collector.

## Product framing

- **Core:** `daemonitor-cored` (Go)
- **Clients:** `daemonitor-corectl` (Go), `clients/jvm/UnixHttpClient` (JDK); later Kotlin CLI / Compose
- **Transport:** HTTP/1.1 over Unix socket

## Delivered in `spikes/go-core/`

| Piece | Role |
|-------|------|
| `cmd/daemonitor-cored` | Poll loop + SQLite + socket server |
| `cmd/daemonitor-corectl` | Thin client (`health`, `processes`, `history`) |
| `internal/poll` | gopsutil + classifier subset |
| `internal/store` | SQLite samples + retention purge |
| `internal/api` | `/v1/health`, `/v1/processes`, `/v1/processes/history` |
| `clients/jvm` | Zero-dep Unix HTTP GET (desktop language family) |
| `scripts/smoke.sh` | Build + Go client + JVM client round-trip |

## Non-goals (still)

Daemon log tail, live heap Attach, packaging, replacing shipping JVM app.

## Current evidence

- Live macOS poll returned `GRADLE_DAEMON` / `KOTLIN_DAEMON` / `GRADLE_WRAPPER`
- Smoke covers health, processes, history, and JVM socket client

## Follow-on

1. Parity checklist vs Kotlin collector (classification + RSS)
2. Dual-run migration plan only after parity looks honest
3. Optional: move daemon log tail into the Go core
