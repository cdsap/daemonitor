#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <launcher> <app-image>" >&2
  exit 2
fi

launcher=$1
app_image=$2

if [[ ! -x "$launcher" ]]; then
  echo "Packaged launcher is not executable: $launcher" >&2
  exit 1
fi
if [[ ! -d "$app_image" ]]; then
  echo "Application image does not exist: $app_image" >&2
  exit 1
fi

runtime_release=$(find "$app_image" -type f -name release -path '*/runtime/*' -print -quit)
if [[ -z "$runtime_release" ]]; then
  echo "Could not find the packaged runtime release file under: $app_image" >&2
  exit 1
fi

echo "Packaged runtime: $runtime_release"
# jpackage writes MODULES as a quoted, space-separated list in the runtime release file.
grep -Eq '(^|[ =",])jdk\.attach([ =",]|$)' "$runtime_release" || {
  echo "Packaged runtime does not contain jdk.attach: $runtime_release" >&2
  cat "$runtime_release" >&2
  exit 1
}

help_output=$("$launcher" --headless --help)
printf '%s\n' "$help_output"
[[ "$help_output" == *"Usage: daemonitor --headless"* ]]

dashboard_output=$( {
  sleep 3
  printf 'q'
} | "$launcher" --headless 2>&1 )
printf '%s\n' "$dashboard_output"

[[ "$dashboard_output" == *"DAEMONITOR — HEADLESS"* ]]
[[ "$dashboard_output" == *"HEAP USED"* ]]
[[ "$dashboard_output" == *"HEAP CMT"* ]]
[[ "$dashboard_output" == *"HEAP LIMIT"* ]]

# A process row may be unavailable on a clean runner, but any rendered heap value must use
# either a megabyte value or the explicit unavailable marker, never an invented zero.
if grep -qE 'Gradle daemon|Gradle wrapper|Kotlin daemon|Test worker|Java \(Gradle\)' <<< "$dashboard_output"; then
  heap_columns=$(grep -E 'Gradle daemon|Gradle wrapper|Kotlin daemon|Test worker|Java \(Gradle\)' <<< "$dashboard_output")
  grep -Eq '( [0-9]+ MB| —)' <<< "$heap_columns"
fi
