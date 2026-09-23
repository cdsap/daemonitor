#!/usr/bin/env bash
# Updates Formula/daemonitor-cli.rb for a multi-arch release.
# Usage:
#   update-homebrew-formula.sh <version> <tap-dir> \
#     <macos-arm64.zip> <macos-x64.zip> <linux-x64.zip>
set -euo pipefail

usage() {
  echo "Usage: $0 <version> <tap-checkout-dir> <macos-arm64.zip> <macos-x64.zip> <linux-x64.zip>" >&2
  exit 2
}

if [[ $# -ne 5 ]]; then
  usage
fi

version=$1
tap_dir=$2
macos_arm64_zip=$3
macos_x64_zip=$4
linux_x64_zip=$5
formula="$tap_dir/Formula/daemonitor-cli.rb"

if [[ ! -f "$formula" ]]; then
  echo "Formula not found: $formula" >&2
  exit 1
fi

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-].+)?$ ]]; then
  echo "Unexpected version: $version" >&2
  exit 1
fi

for z in "$macos_arm64_zip" "$macos_x64_zip" "$linux_x64_zip"; do
  if [[ ! -s "$z" ]]; then
    echo "Missing or empty archive: $z" >&2
    exit 1
  fi
done

sha() { sha256sum "$1" | awk '{print $1}'; }

macos_arm64_sha=$(sha "$macos_arm64_zip")
macos_x64_sha=$(sha "$macos_x64_zip")
linux_x64_sha=$(sha "$linux_x64_zip")

base="https://github.com/cdsap/daemonitor/releases/download/v${version}"
macos_arm64_url="${base}/daemonitor-cli-${version}-macos-arm64.zip"
macos_x64_url="${base}/daemonitor-cli-${version}-macos-x64.zip"
linux_x64_url="${base}/daemonitor-cli-${version}-linux-x64.zip"

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

python3 - "$formula" "$tmp" \
  "$macos_arm64_url" "$macos_arm64_sha" \
  "$macos_x64_url" "$macos_x64_sha" \
  "$linux_x64_url" "$linux_x64_sha" <<'PY'
import pathlib
import sys

src, dst = sys.argv[1], sys.argv[2]
mac_arm_url, mac_arm_sha = sys.argv[3], sys.argv[4]
mac_x64_url, mac_x64_sha = sys.argv[5], sys.argv[6]
linux_url, linux_sha = sys.argv[7], sys.argv[8]

# Rewrite the whole formula so multi-arch URL blocks stay idempotent across bumps.
text = f'''class DaemonitorCli < Formula
  desc "Terminal monitor for local Gradle daemons"
  homepage "https://github.com/cdsap/daemonitor"
  license "MIT"

  depends_on "openjdk@21"

  on_macos do
    on_arm do
      url "{mac_arm_url}"
      sha256 "{mac_arm_sha}"
    end
    on_intel do
      url "{mac_x64_url}"
      sha256 "{mac_x64_sha}"
    end
  end

  on_linux do
    on_intel do
      url "{linux_url}"
      sha256 "{linux_sha}"
    end
  end

  def install
    libexec.install Dir["*"]
    (bin/"daemonitor-cli").write_env_script libexec/"bin/daemonitor-cli",
                                            Language::Java.overridable_java_home_env("21")
    bin.install_symlink libexec/"bin/daemonitor-cored"
  end

  test do
    assert_match "Usage", shell_output("#{{bin}}/daemonitor-cli --help")
    assert_predicate bin/"daemonitor-cored", :exist?
  end
end
'''
# Fix double-brace from f-string escaping for #{bin}
text = text.replace("#{{bin}}", "#{bin}")
pathlib.Path(dst).write_text(text)
PY

mv "$tmp" "$formula"
trap - EXIT

echo "Updated $formula"
echo "  macos-arm64: $macos_arm64_url ($macos_arm64_sha)"
echo "  macos-x64:   $macos_x64_url ($macos_x64_sha)"
echo "  linux-x64:   $linux_x64_url ($linux_x64_sha)"
