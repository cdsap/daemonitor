#!/usr/bin/env bash
# Cross-compile daemonitor-cli + daemonitor-cored for common release targets (CGO_ENABLED=0).
# Usage: cross-compile-cli.sh [output-dir]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GO_CORE="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="${1:-$GO_CORE/bin/cross-cli}"
if [[ "$OUT" != /* ]]; then
  OUT="$(pwd)/$OUT"
fi
mkdir -p "$OUT"

# goos/goarch/suffix
targets=(
  "darwin/arm64/darwin-arm64"
  "darwin/amd64/darwin-amd64"
  "linux/amd64/linux-amd64"
  "linux/arm64/linux-arm64"
  "windows/amd64/windows-amd64"
  "windows/arm64/windows-arm64"
)

echo "==> Cross-compiling daemonitor-cli + daemonitor-cored into $OUT"
cd "$GO_CORE"
for spec in "${targets[@]}"; do
  IFS=/ read -r goos goarch suffix <<<"$spec"
  ext=""
  if [[ "$goos" == "windows" ]]; then
    ext=".exe"
  fi
  echo "  - $goos/$goarch"
  CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
    go build -trimpath -ldflags="-s -w" \
    -o "$OUT/daemonitor-cli-${suffix}${ext}" ./cmd/daemonitor-cli
  CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
    go build -trimpath -ldflags="-s -w" \
    -o "$OUT/daemonitor-cored-${suffix}${ext}" ./cmd/daemonitor-cored
done

echo "==> Done"
ls -la "$OUT"
