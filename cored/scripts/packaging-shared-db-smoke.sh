#!/usr/bin/env bash
# E2E: bundled daemonitor-cored + CLI --core-socket against a shared SQLite file.
# Proves packaging slice 4: installDist embeds cored, health db_path matches, CLI
# reports shared-DB ownership (skips sample/build writes).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

WORKDIR="${RUNNER_TEMP:-${TMPDIR:-$(pwd)/.packaging-e2e-tmp}}"
mkdir -p "$WORKDIR"
WORKDIR="$(cd "$WORKDIR" && pwd)"
SOCK="$WORKDIR/daemonitor-packaging-e2e.sock"
DB="$WORKDIR/daemonitor-packaging-e2e.sqlite"
CLI_ERR="$WORKDIR/cli-stderr.txt"
cored_pid=""
cli_pid=""
rm -f "$SOCK" "$DB" "$CLI_ERR" "${DB}-wal" "${DB}-shm"

echo "==> Building CLI installDist (embeds host-arch daemonitor-cored when go is available)"
./gradlew :cli:installDist --no-daemon -q

BIN="$REPO_ROOT/cli/build/install/daemonitor-cli/bin"
CORED="$BIN/daemonitor-cored"
CLI="$BIN/daemonitor-cli"
if [[ -x "$BIN/daemonitor-cored.exe" ]]; then
  CORED="$BIN/daemonitor-cored.exe"
fi
if [[ -x "$BIN/daemonitor-cli.bat" && ! -x "$CLI" ]]; then
  CLI="$BIN/daemonitor-cli.bat"
fi

if [[ ! -x "$CORED" ]]; then
  echo "e2e failed: bundled daemonitor-cored missing at $CORED (is go on PATH?)" >&2
  ls -la "$BIN" >&2 || true
  exit 1
fi
if [[ ! -x "$CLI" && ! -f "$CLI" ]]; then
  echo "e2e failed: bundled daemonitor-cli missing at $CLI" >&2
  exit 1
fi

echo "==> Starting bundled cored (shared temp DB) — warm samples, then slow poll"
"$CORED" -socket "$SOCK" -db "$DB" -interval 200ms -retention 1h &
cored_pid=$!
cleanup() {
  if [[ -n "${cli_pid:-}" ]]; then
    kill "$cli_pid" 2>/dev/null || true
    wait "$cli_pid" 2>/dev/null || true
  fi
  if [[ -n "${cored_pid:-}" ]]; then
    kill "$cored_pid" 2>/dev/null || true
    wait "$cored_pid" 2>/dev/null || true
  fi
  rm -f "$SOCK"
}
trap cleanup EXIT

for _ in $(seq 1 100); do
  if [[ -S "$SOCK" ]] || [[ -e "$SOCK" ]]; then
    break
  fi
  sleep 0.05
done
if [[ ! -e "$SOCK" ]]; then
  echo "e2e failed: socket not created at $SOCK" >&2
  exit 1
fi

# Prefer freshly built corectl from the spike module (installDist only ships cored).
CORECTL_DIR="$SCRIPT_DIR/../bin"
mkdir -p "$CORECTL_DIR"
(cd "$SCRIPT_DIR/.." && go build -o bin/daemonitor-corectl ./cmd/daemonitor-corectl)
CORECTL="$CORECTL_DIR/daemonitor-corectl"

health="$(JSON=1 "$CORECTL" -socket "$SOCK" health)"
echo "$health" | grep -q '"status": "ok"'
if ! echo "$health" | grep -q '"db_path"'; then
  echo "e2e failed: health missing db_path: $health" >&2
  exit 1
fi

sleep 0.8
# Restart cored with a long poll so only the CLI could add samples during the next window.
kill "$cored_pid" 2>/dev/null || true
wait "$cored_pid" 2>/dev/null || true
cored_pid=""
rm -f "$SOCK"
"$CORED" -socket "$SOCK" -db "$DB" -interval 1h -retention 1h &
cored_pid=$!
for _ in $(seq 1 100); do
  if [[ -S "$SOCK" ]] || [[ -e "$SOCK" ]]; then
    break
  fi
  sleep 0.05
done
if [[ ! -e "$SOCK" ]]; then
  echo "e2e failed: socket not recreated after slow-poll restart" >&2
  exit 1
fi
# One immediate sample on start is OK; wait for it, then freeze the count.
sleep 0.3
before_count="$(sqlite3 "$DB" 'SELECT COUNT(*) FROM process_samples;')"

echo "==> CLI --collect-only against shared DB (expect core-owns-writes banner; no new samples)"
"$CLI" --plain --collect-only --core-socket "$SOCK" --db "$DB" >"$WORKDIR/cli-stdout.txt" 2>"$CLI_ERR" &
cli_pid=$!
sleep 3.5
kill "$cli_pid" 2>/dev/null || true
wait "$cli_pid" 2>/dev/null || true
cli_pid=""

if ! grep -q "shared DB" "$CLI_ERR"; then
  echo "e2e failed: CLI stderr missing shared-DB ownership message" >&2
  echo "----- cli stderr -----" >&2
  cat "$CLI_ERR" >&2 || true
  exit 1
fi
if ! grep -Eqi "core owns sample/build writes|owns sample/build writes" "$CLI_ERR"; then
  echo "e2e failed: CLI stderr missing write-ownership phrase" >&2
  cat "$CLI_ERR" >&2 || true
  exit 1
fi

after_count="$(sqlite3 "$DB" 'SELECT COUNT(*) FROM process_samples;')"
if [[ "$after_count" != "$before_count" ]]; then
  echo "e2e failed: process_samples changed during shared CLI run (before=$before_count after=$after_count); CLI should not persist samples" >&2
  exit 1
fi

# WAL sidecars may exist while cored still runs; schema must be readable.
tables="$(sqlite3 "$DB" "SELECT name FROM sqlite_master WHERE type='table' ORDER BY 1;")"
echo "$tables" | grep -q 'builds'
echo "$tables" | grep -q 'process_samples'

echo "packaging shared-db e2e ok (cored=$CORED db=$DB samples $before_count->$after_count)"
