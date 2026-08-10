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

infer_platform() {
  local name=$1
  if [[ "$name" == *"-macos-"* || "$name" == *"-macos."* ]]; then
    printf 'macos\n'
  elif [[ "$name" == *"-windows-"* || "$name" == *"-windows."* ]]; then
    printf 'windows\n'
  elif [[ "$name" == *"-linux-"* || "$name" == *"-linux."* ]]; then
    printf 'linux\n'
  else
    return 1
  fi
}

infer_arch() {
  local name=$1
  if [[ "$name" == *"-arm64."* || "$name" == *"-arm64-"* || "$name" == *".arm64."* ]]; then
    printf 'arm64\n'
  elif [[ "$name" == *"-x64."* || "$name" == *"-x64-"* || "$name" == *"-amd64."* || "$name" == *"-x86_64."* ]]; then
    printf 'x64\n'
  else
    printf 'unknown\n'
  fi
}

infer_role() {
  local name=$1
  case "$name" in
    *.zip|*.tar.gz|*.tgz) printf 'update\n' ;;
    *) printf 'installer\n' ;;
  esac
}

mkdir -p "$output_dir"

shopt -s nullglob
asset_files=()
for path in "$assets_dir"/*; do
  name=$(basename "$path")
  case "$name" in
    *.sha256|latest.json|update.json|checksums.txt) continue ;;
  esac
  if [[ -f "$path" && -s "$path" ]]; then
    asset_files+=("$path")
  fi
done

if (( ${#asset_files[@]} == 0 )); then
  echo "No release assets found in $assets_dir" >&2
  exit 1
fi

IFS=$'\n' asset_files=($(printf '%s\n' "${asset_files[@]}" | LC_ALL=C sort))
unset IFS

checksums_file="$output_dir/checksums.txt"
: > "$checksums_file"

declare -a platforms
declare -a arches
declare -a roles
declare -a names
declare -a checksums
declare -a sizes

for asset in "${asset_files[@]}"; do
  name=$(basename "$asset")
  platform=$(infer_platform "$name") || {
    echo "Unable to infer platform for asset: $name" >&2
    exit 1
  }
  arch=$(infer_arch "$name")
  role=$(infer_role "$name")
  checksum=$(sha256_file "$asset")
  size=$(file_size "$asset")

  platforms+=("$platform")
  arches+=("$arch")
  roles+=("$role")
  names+=("$name")
  checksums+=("$checksum")
  sizes+=("$size")

  printf '%s  %s\n' "$checksum" "$name" >> "$checksums_file"
  printf '%s\n' "$checksum" > "$assets_dir/${name}.sha256"
done

metadata_file="$output_dir/latest.json"
{
  printf '{\n'
  printf '  "schemaVersion": 2,\n'
  printf '  "name": "Daemonitor",\n'
  printf '  "version": "%s",\n' "$version"
  printf '  "tag": "%s",\n' "$tag"
  printf '  "repository": "https://github.com/%s",\n' "$repository"
  printf '  "assets": [\n'
  for index in "${!names[@]}"; do
    comma=","
    if [[ "$index" == "$((${#names[@]} - 1))" ]]; then
      comma=""
    fi
    printf '    {\n'
    printf '      "platform": "%s",\n' "${platforms[$index]}"
    printf '      "arch": "%s",\n' "${arches[$index]}"
    printf '      "role": "%s",\n' "${roles[$index]}"
    printf '      "fileName": "%s",\n' "${names[$index]}"
    printf '      "url": "https://github.com/%s/releases/download/%s/%s",\n' "$repository" "$tag" "${names[$index]}"
    printf '      "sha256": "%s",\n' "${checksums[$index]}"
    printf '      "size": %s\n' "${sizes[$index]}"
    printf '    }%s\n' "$comma"
  done
  printf '  ]\n'
  printf '}\n'
} > "$metadata_file"

cp "$metadata_file" "$output_dir/update.json"

echo "Wrote release metadata to $output_dir"
