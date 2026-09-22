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
| `cmd/daemonitor-corectl` | Thin client (`health`, `processes`, `history`, `logs`, `log-tail`) |
| `internal/poll` | gopsutil + classifier + Redactor subset |
| `internal/logs` | Daemon log discover + redacted tails |
| `internal/store` | SQLite samples + retention purge |
| `internal/api` | `/v1/health`, `/v1/processes`, `/v1/processes/history`, `/v1/daemon-logs` |
| `clients/jvm` | Zero-dep Unix HTTP GET (desktop language family) |
| `scripts/smoke.sh` | Build + Go client + JVM client round-trip |

## Non-goals (still)

Live heap Attach, packaging, replacing shipping JVM app, build-event parsing in Go.

## Current evidence

- Live macOS poll returned `GRADLE_DAEMON` / `KOTLIN_DAEMON` / `GRADLE_WRAPPER`
- Smoke covers health, processes, history, daemon-logs, and JVM socket client

## Follow-on

1. Optionally have Kotlin dual-run consume `/v1/builds`
2. Packaging / shared SQLite beyond the spike

Go core now parses U3 events and aggregates confirmed builds (`build.Aggregator`, `GET /v1/builds`).
Side-by-side checklist: `spikes/go-core/docs/dual-run.md`.
