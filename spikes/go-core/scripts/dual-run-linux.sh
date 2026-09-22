#!/usr/bin/env bash
# Live dual-run honesty on Linux: start Go core + a Gradle daemon, compare JVM vs Go snapshots.
#
# Native Linux:
#   ./spikes/go-core/scripts/dual-run-linux.sh
#
# From macOS via Docker (Linux VM):
#   ./spikes/go-core/scripts/dual-run-linux.sh --docker
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
USE_DOCKER=0
if [[ "${1:-}" == "--docker" ]]; then
  USE_DOCKER=1
fi

if [[ "$(uname -s)" != "Linux" && "$USE_DOCKER" -ne 1 ]]; then
  echo "Not Linux — re-run with --docker to validate inside a Linux container." >&2
  exit 2
fi

if [[ "$USE_DOCKER" -eq 1 ]]; then
  GOARCH="$(uname -m)"
  case "$GOARCH" in
    arm64|aarch64) GO_TARBALL_ARCH=arm64 ;;
    x86_64|amd64) GO_TARBALL_ARCH=amd64 ;;
    *) echo "unsupported arch: $GOARCH" >&2; exit 2 ;;
  esac
  docker run --rm \
    -v "$ROOT:/work" \
    -v daemonitor-dual-run-gradle:/root/.gradle \
    -v daemonitor-dual-run-go-mod:/go/pkg/mod \
    -w /work \
    -e GO_TARBALL_ARCH="$GO_TARBALL_ARCH" \
    eclipse-temurin:21-jdk-jammy \
    bash -lc '/work/spikes/go-core/scripts/dual-run-linux.sh --inside-docker'
  exit $?
fi

if [[ "${1:-}" == "--inside-docker" ]]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq curl ca-certificates git gcc >/dev/null
  if ! command -v go >/dev/null 2>&1; then
    GO_VER=1.25.0
    curl -fsSL "https://go.dev/dl/go${GO_VER}.linux-${GO_TARBALL_ARCH}.tar.gz" \
      | tar -C /usr/local -xz
    export PATH="/usr/local/go/bin:$PATH"
  fi
fi

cd "$ROOT"
SOCK="${TMPDIR:-/tmp}/daemonitor-dual-run-linux.sock"
DB="${TMPDIR:-/tmp}/daemonitor-dual-run-linux.sqlite"
rm -f "$SOCK" "$DB"

echo "==> warming Gradle daemon (creates GRADLE_DAEMON to compare)"
./gradlew help -q

echo "==> building Go core"
(
  cd spikes/go-core
  mkdir -p bin
  go build -o bin/daemonitor-cored ./cmd/daemonitor-cored
  go build -o bin/daemonitor-corectl ./cmd/daemonitor-corectl
)

echo "==> starting daemonitor-cored"
spikes/go-core/bin/daemonitor-cored -socket "$SOCK" -db "$DB" -interval 500ms -retention 1h &
CORE_PID=$!
cleanup() {
  kill "$CORE_PID" 2>/dev/null || true
  wait "$CORE_PID" 2>/dev/null || true
  rm -f "$SOCK"
}
trap cleanup EXIT

for _ in $(seq 1 100); do
  [[ -e "$SOCK" ]] && break
  sleep 0.05
done
[[ -e "$SOCK" ]] || { echo "cored socket missing: $SOCK" >&2; exit 1; }

# Second poll window so Go CPU deltas and daemon classification settle.
sleep 2
spikes/go-core/bin/daemonitor-corectl -socket "$SOCK" health | grep -q '"status": "ok"'
echo "==> Go snapshot"
spikes/go-core/bin/daemonitor-corectl -socket "$SOCK" processes
spikes/go-core/bin/daemonitor-corectl -socket "$SOCK" logs | head -20 || true
spikes/go-core/bin/daemonitor-corectl -socket "$SOCK" builds | head -20 || true

LOG="${TMPDIR:-/tmp}/daemonitor-dual-run-linux.log"
echo "==> JVM vs Go honesty test"
set +e
DAEMONITOR_DUAL_RUN=1 DAEMONITOR_CORE_SOCKET="$SOCK" \
  ./gradlew :cli:test --tests 'io.github.cdsap.daemonitor.coreipc.DualRunHonestyTest' 2>&1 \
  | tee "$LOG"
status=${PIPESTATUS[0]}
set -e

grep -E 'dual-run honesty:|type_match=|rss_delta|BUILD|DualRunHonesty|FAILED|PASSED' "$LOG" || true

if [[ "$status" -eq 0 ]]; then
  echo "dual-run-linux: ok (log=$LOG)"
  exit 0
fi
echo "dual-run-linux: failed — see $LOG" >&2
exit 1
