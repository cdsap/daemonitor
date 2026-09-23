#!/usr/bin/env bash
# Cross-compile daemonitor-cored for common release targets (CGO_ENABLED=0).
# Usage: cross-compile-cored.sh [output-dir]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GO_CORE="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="${1:-$GO_CORE/bin/cross}"
mkdir -p "$OUT"

# goos/goarch/filename
targets=(
  "darwin/arm64/daemonitor-cored-darwin-arm64"
  "darwin/amd64/daemonitor-cored-darwin-amd64"
  "linux/amd64/daemonitor-cored-linux-amd64"
  "linux/arm64/daemonitor-cored-linux-arm64"
  "windows/amd64/daemonitor-cored-windows-amd64.exe"
  "windows/arm64/daemonitor-cored-windows-arm64.exe"
)

echo "==> Cross-compiling daemonitor-cored into $OUT"
cd "$GO_CORE"
for spec in "${targets[@]}"; do
  IFS=/ read -r goos goarch name <<<"$spec"
  echo "  - $goos/$goarch → $name"
  CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
    go build -trimpath -ldflags="-s -w" \
    -o "$OUT/$name" ./cmd/daemonitor-cored
done

echo "==> Done"
ls -la "$OUT"
