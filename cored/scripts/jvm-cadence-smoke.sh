#!/usr/bin/env bash
# Linux live-tool smoke: classified Gradle, Kotlin and worker JVMs.
# These idle fixtures exercise collection; they are not an overhead benchmark.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
pids=()
cleanup() {
  set +u
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
  rm -rf "$work"
}
trap cleanup EXIT
classes=(org.gradle.launcher.daemon.bootstrap.GradleDaemon org.jetbrains.kotlin.daemon.KotlinCompileDaemon org.gradle.process.internal.worker.GradleWorkerMain)
for name in "${classes[@]}"; do
  package=${name%.*}
  simple=${name##*.}
  cat > "$work/$simple.java" <<JAVA
package $package;
public class $simple {
  public static void main(String[] args) throws Exception {
    byte[] data = new byte[1024 * 1024];
    while (true) { data[0]++; Thread.sleep(100); }
  }
}
JAVA
  javac -d "$work/classes" "$work/$simple.java"
  java -Xms32m -Xmx64m -cp "$work/classes" "$name" "Gradle Worker Daemon" &
  pids+=("$!")
done
go build -o "$work/cored" ./cmd/daemonitor-cored
# Keep AF_UNIX paths short even when RUNNER_TEMP is deeply nested.
sock="$work/core.sock"
"$work/cored" -socket "$sock" -db "$work/core.db" -interval 200ms \
  -jvm-heap-interval 1s -jvm-advanced-interval 2s -jvm-probe-budget 2s >"$work/core.log" 2>&1 &
pids+=("$!")
python3 - "$sock" "${pids[0]}" "${pids[1]}" "${pids[2]}" <<'PY'
import http.client
import json
import socket
import sys
import time

endpoint = sys.argv[1]
expected = dict(zip(map(int, sys.argv[2:]), ["GRADLE_DAEMON", "KOTLIN_DAEMON", "GRADLE_WORKER"]))

def get(path):
    connection = http.client.HTTPConnection("localhost", timeout=3)
    connection.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.sock.settimeout(3)
    connection.sock.connect(endpoint)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        assert response.status == 200
        return json.load(response)
    finally:
        connection.close()

deadline = time.monotonic() + 45
last = {}
first = None
while time.monotonic() < deadline:
    try:
        last = get("/v1/processes")
        rows = {p["pid"]: p for p in last["processes"] if p["pid"] in expected}
        if all(pid in rows and rows[pid]["type"] == kind
               and rows[pid]["heap_available"] and rows[pid]["heap_used_mb"] is not None
               and rows[pid].get("code_cache")
               and rows[pid].get("classes") is not None
               and rows[pid].get("jvm_advanced_sampled_at_ms") is not None
               for pid, kind in expected.items()):
            stamps = {pid: (p["heap_sampled_at_ms"], p["jvm_advanced_sampled_at_ms"]) for pid, p in rows.items()}
            if first is None:
                first = stamps
            elif all(stamps[pid][0] > first[pid][0] and stamps[pid][1] > first[pid][1] for pid in expected):
                settings = get("/v1/self")
                assert settings["cadences"]["process_os_ms"] == 200
                assert settings["cadences"]["jvm_heap_gc_ms"] == 1000
                assert settings["cadences"]["jvm_jit_code_cache_ms"] == 2000
                assert settings["jvm_probe_budget_ms"] == 2000
                health = get("/v1/health")
                assert health["status"] == "ok" and health["process_sample_ready"]
                print("JVM cadence smoke passed: three JVMs, two completed samples per family")
                break
    except (OSError, http.client.HTTPException):
        pass
    time.sleep(0.2)
else:
    summary = [{key: row.get(key) for key in ("pid", "type", "heap_available", "heap_sampled_at_ms", "jvm_advanced_sampled_at_ms", "heap_probe_diagnostics")}
               for row in last.get("processes", []) if row["pid"] in expected]
    raise SystemExit(f"JVM samples did not become available: {summary}")
PY
