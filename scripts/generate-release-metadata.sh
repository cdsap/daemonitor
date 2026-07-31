#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <assets-dir> <output-dir> <version> <tag> <repository>" >&2
  exit 2
fi

assets_dir=$1
output_dir=$2
version=$3
tag=$4
repository=$5

if [[ ! -d "$assets_dir" ]]; then
  echo "Assets directory does not exist: $assets_dir" >&2
  exit 1
fi

sha256_file() {
  local checksum
  if command -v sha256sum >/dev/null 2>&1; then
    checksum=$(sha256sum "$1" | awk '{print $1}')
  else
    checksum=$(shasum -a 256 "$1" | awk '{print $1}')
  fi
  printf '%s\n' "${checksum#\\}"
}

file_size() {
  wc -c < "$1" | tr -d '[:space:]'
}

asset_for() {
  local platform=$1
  local extension=$2
  local asset="$assets_dir/Daemonitor-${version}-${platform}.${extension}"
  if [[ ! -s "$asset" ]]; then
    echo "Missing non-empty release asset: $asset" >&2
    exit 1
  fi
  printf '%s\n' "$asset"
}

mkdir -p "$output_dir"

platforms=(linux windows macos)
extensions=(deb msi dmg)
assets=()
checksums=()
sizes=()

for index in "${!platforms[@]}"; do
  asset=$(asset_for "${platforms[$index]}" "${extensions[$index]}")
  assets+=("$asset")
  checksums+=("$(sha256_file "$asset")")
  sizes+=("$(file_size "$asset")")
done

{
  for index in "${!assets[@]}"; do
    printf '%s  %s\n' "${checksums[$index]}" "$(basename "${assets[$index]}")"
  done
} > "$output_dir/checksums.txt"

metadata_file="$output_dir/latest.json"
{
  printf '{\n'
  printf '  "schemaVersion": 1,\n'
  printf '  "name": "Daemonitor",\n'
  printf '  "version": "%s",\n' "$version"
  printf '  "tag": "%s",\n' "$tag"
  printf '  "repository": "https://github.com/%s",\n' "$repository"
  printf '  "assets": [\n'
  for index in "${!assets[@]}"; do
    asset_name=$(basename "${assets[$index]}")
    comma=","
    if [[ "$index" == "$((${#assets[@]} - 1))" ]]; then
      comma=""
    fi
    printf '    {\n'
    printf '      "platform": "%s",\n' "${platforms[$index]}"
    printf '      "fileName": "%s",\n' "$asset_name"
    printf '      "url": "https://github.com/%s/releases/download/%s/%s",\n' "$repository" "$tag" "$asset_name"
    printf '      "sha256": "%s",\n' "${checksums[$index]}"
    printf '      "size": %s\n' "${sizes[$index]}"
    printf '    }%s\n' "$comma"
  done
  printf '  ]\n'
  printf '}\n'
} > "$metadata_file"

cp "$metadata_file" "$output_dir/update.json"

echo "Wrote release metadata to $output_dir"
