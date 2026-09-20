#!/usr/bin/env bash
# Updates Formula/daemonitor-cli.rb in a homebrew-tap checkout for a published release.
set -euo pipefail

usage() {
  echo "Usage: $0 <version> <sha256> <tap-checkout-dir>" >&2
  echo "Example: $0 1.0.8 abcdef... /tmp/homebrew-tap" >&2
  exit 2
}

if [[ $# -ne 3 ]]; then
  usage
fi

version=$1
sha256=$2
tap_dir=$3
formula="$tap_dir/Formula/daemonitor-cli.rb"

if [[ ! -f "$formula" ]]; then
  echo "Formula not found: $formula" >&2
  exit 1
fi

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-].+)?$ ]]; then
  echo "Unexpected version: $version" >&2
  exit 1
fi

if [[ ! "$sha256" =~ ^[a-f0-9]{64}$ ]]; then
  echo "Unexpected sha256: $sha256" >&2
  exit 1
fi

url="https://github.com/cdsap/daemonitor/releases/download/v${version}/daemonitor-cli-${version}.zip"

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

python3 - "$formula" "$tmp" "$url" "$sha256" <<'PY'
import pathlib
import re
import sys

src, dst, url, sha256 = sys.argv[1:5]
text = pathlib.Path(src).read_text()
text, url_n = re.subn(
    r'(url\s+")[^"]+(")',
    lambda m: f"{m.group(1)}{url}{m.group(2)}",
    text,
    count=1,
)
text, sha_n = re.subn(
    r'(sha256\s+")[a-f0-9]{64}(")',
    lambda m: f"{m.group(1)}{sha256}{m.group(2)}",
    text,
    count=1,
)
if url_n != 1 or sha_n != 1:
    raise SystemExit(f"Failed to rewrite formula (url={url_n}, sha256={sha_n})")
pathlib.Path(dst).write_text(text)
PY

mv "$tmp" "$formula"
trap - EXIT

echo "Updated $formula"
echo "  url: $url"
echo "  sha256: $sha256"
