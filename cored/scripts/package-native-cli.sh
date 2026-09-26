#!/usr/bin/env bash
# Package a native Go CLI zip: daemonitor-cli + daemonitor-cored (no JDK).
# Usage: package-native-cli.sh <version> <os-label> <arch-label> [output-dir]
# Example: package-native-cli.sh 1.2.0 macos arm64 build/native-cli
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "Usage: $0 <version> <os-label> <arch-label> [output-dir]" >&2
  exit 2
fi

VERSION=$1
OS_LABEL=$2
ARCH_LABEL=$3
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GO_CORE="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="${4:-$GO_CORE/bin/native-pkg}"
if [[ "$OUT" != /* ]]; then
  OUT="$(pwd)/$OUT"
fi
mkdir -p "$OUT"

case "$OS_LABEL" in
  macos) goos=darwin ;;
  linux) goos=linux ;;
  windows) goos=windows ;;
  *)
    echo "Unsupported OS label: $OS_LABEL (expected macos, linux, or windows)" >&2
    exit 2
    ;;
esac

case "$ARCH_LABEL" in
  x64) goarch=amd64 ;;
  arm64) goarch=arm64 ;;
  *)
    echo "Unsupported architecture label: $ARCH_LABEL (expected x64 or arm64)" >&2
    exit 2
    ;;
esac

cli_name=daemonitor-cli
cored_name=daemonitor-cored
if [[ "$OS_LABEL" == "windows" ]]; then
  cli_name=daemonitor-cli.exe
  cored_name=daemonitor-cored.exe
fi

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
root="$stage/daemonitor-cli-${VERSION}"
mkdir -p "$root/bin"

echo "==> Building native CLI ($OS_LABEL/$ARCH_LABEL)"
cd "$GO_CORE"
CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
  go build -trimpath -ldflags="-s -w" -o "$root/bin/$cli_name" ./cmd/daemonitor-cli
CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
  go build -trimpath -ldflags="-s -w" -o "$root/bin/$cored_name" ./cmd/daemonitor-cored
chmod +x "$root/bin/$cli_name" "$root/bin/$cored_name" 2>/dev/null || true

zip_name="daemonitor-cli-${VERSION}-${OS_LABEL}-${ARCH_LABEL}.zip"
(cd "$stage" && zip -qry "$OUT/$zip_name" "$(basename "$root")")
echo "==> Wrote $OUT/$zip_name"
ls -la "$OUT/$zip_name"
