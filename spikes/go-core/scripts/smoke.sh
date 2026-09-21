#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

SOCK="${TMPDIR:-/tmp}/daemonitor-core-smoke.sock"
DB="${TMPDIR:-/tmp}/daemonitor-core-smoke.sqlite"
rm -f "$SOCK" "$DB"

mkdir -p bin
go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
go build -o bin/daemonitor-corectl ./cmd/daemonitor-corectl

./bin/daemonitor-cored -socket "$SOCK" -db "$DB" -interval 200ms -retention 1h &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; rm -f "$SOCK"' EXIT

for _ in $(seq 1 50); do
  if [[ -S "$SOCK" ]]; then
    break
  fi
  sleep 0.05
done

./bin/daemonitor-corectl -socket "$SOCK" health | grep -q '"status": "ok"'
./bin/daemonitor-corectl -socket "$SOCK" processes >/dev/null
sleep 0.5
./bin/daemonitor-corectl -socket "$SOCK" history >/dev/null

# JVM Unix-socket client (proves IPC from the desktop/CLI language family)
javac clients/jvm/UnixHttpClient.java
java -cp clients/jvm UnixHttpClient "$SOCK" /v1/health | grep -q '"status": "ok"'

echo "smoke ok (socket=$SOCK db=$DB)"
