#!/usr/bin/env bash
# Cross-platform smoke for the Go core spike (macOS / Linux / Windows+Git Bash).
set -euo pipefail
cd "$(dirname "$0")/.."

WORKDIR="${RUNNER_TEMP:-${TMPDIR:-$(pwd)/.smoke-tmp}}"
mkdir -p "$WORKDIR"
# Normalize Windows paths from Git Bash / Actions for AF_UNIX + Go.
WORKDIR="$(cd "$WORKDIR" && pwd)"
SOCK="$WORKDIR/daemonitor-core-smoke.sock"
DB="$WORKDIR/daemonitor-core-smoke.sqlite"
rm -f "$SOCK" "$DB"

mkdir -p bin
go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
go build -o bin/daemonitor-corectl ./cmd/daemonitor-corectl

./bin/daemonitor-cored -socket "$SOCK" -db "$DB" -interval 200ms -retention 1h &
pid=$!
cleanup() {
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  rm -f "$SOCK"
}
trap cleanup EXIT

for _ in $(seq 1 80); do
  if [[ -S "$SOCK" ]] || [[ -e "$SOCK" ]]; then
    break
  fi
  sleep 0.05
done
if [[ ! -e "$SOCK" ]]; then
  echo "smoke failed: socket not created at $SOCK" >&2
  exit 1
fi

./bin/daemonitor-corectl -socket "$SOCK" health | grep -q '"status": "ok"'
./bin/daemonitor-corectl -socket "$SOCK" processes >/dev/null
sleep 0.5
./bin/daemonitor-corectl -socket "$SOCK" history >/dev/null

# JVM Unix-socket client (proves IPC from the desktop/CLI language family)
javac clients/jvm/UnixHttpClient.java
java -cp clients/jvm UnixHttpClient "$SOCK" /v1/health | grep -q '"status": "ok"'

echo "smoke ok (socket=$SOCK db=$DB)"
