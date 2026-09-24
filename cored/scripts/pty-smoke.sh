#!/usr/bin/env bash
# PTY smoke: start TUI in a pseudo-terminal, send q without Enter, expect clean exit.
# Requires python3 (pty) and a running or autostartable core.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GO_CORE="$(cd "$SCRIPT_DIR/.." && pwd)"
BIN="${1:-$GO_CORE/bin/daemonitor-cli}"
SOCK="${2:-}"

if [[ ! -x "$BIN" ]]; then
  echo "==> Building daemonitor-cli"
  mkdir -p "$GO_CORE/bin"
  (cd "$GO_CORE" && go build -o bin/daemonitor-cli ./cmd/daemonitor-cli && go build -o bin/daemonitor-cored ./cmd/daemonitor-cored)
  BIN="$GO_CORE/bin/daemonitor-cli"
fi

if [[ -z "$SOCK" ]]; then
  SOCK="$(mktemp "${TMPDIR:-/tmp}/daemonitor-pty.XXXXXX")"
  rm -f "$SOCK"
  SOCK="${SOCK}.sock"
fi
DB="$(mktemp "${TMPDIR:-/tmp}/daemonitor-pty.XXXXXX.db")"
cleanup() {
  [[ -n "${CORED_PID:-}" ]] && kill "$CORED_PID" 2>/dev/null || true
  rm -f "$SOCK" "$DB"
}
trap cleanup EXIT

echo "==> Starting daemonitor-cored on $SOCK"
"$GO_CORE/bin/daemonitor-cored" -socket "$SOCK" -db "$DB" -interval 200ms >/dev/null 2>&1 &
CORED_PID=$!
for _ in $(seq 1 50); do
  if [[ -S "$SOCK" ]]; then
    break
  fi
  sleep 0.05
done

echo "==> PTY smoke: launch TUI and quit with q (no Enter)"
python3 - "$BIN" "$SOCK" <<'PY'
import os, pty, select, sys, time

cli, sock = sys.argv[1], sys.argv[2]
pid, fd = pty.fork()
if pid == 0:
    os.environ["TERM"] = "xterm-256color"
    os.execv(cli, [cli, "top", "--socket", sock, "--no-autostart", "--poll-interval", "500ms"])

# Wait briefly for alt-screen, then send q without newline.
deadline = time.time() + 5
saw_output = False
while time.time() < deadline:
    r, _, _ = select.select([fd], [], [], 0.1)
    if fd in r:
        try:
            data = os.read(fd, 4096)
        except OSError:
            break
        if data:
            saw_output = True
            break
if not saw_output:
    # Still try quit — model may be waiting on first paint.
    pass
os.write(fd, b"q")
# Wait for child exit
for _ in range(50):
    wpid, status = os.waitpid(pid, os.WNOHANG)
    if wpid == pid:
        code = os.WEXITSTATUS(status) if os.WIFEXITED(status) else 1
        sys.exit(code)
    time.sleep(0.1)
# Force
os.kill(pid, 9)
os.waitpid(pid, 0)
print("PTY smoke timed out waiting for exit", file=sys.stderr)
sys.exit(1)
PY

echo "==> PTY smoke OK"
