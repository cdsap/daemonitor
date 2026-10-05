package render

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestWriteProcessesPlainNoANSI(t *testing.T) {
	cpu := 12.5
	xmx := int64(2048)
	used := int64(4500)
	gc := "G1"
	var buf bytes.Buffer
	WriteProcessesPlain(&buf, model.Snapshot{
		SampledAtMs: 100,
		Processes: []model.Process{
			{PID: 1, Type: "GRADLE_DAEMON", RSSMemoryMB: 4500, CPUPercent: &cpu, MaxHeapMB: &xmx, HeapUsedMB: &used, GC: &gc, Automated: true, ProjectPath: strPtr("/a/b")},
		},
	})
	out := buf.String()
	if strings.Contains(out, "\x1b") {
		t.Fatalf("ANSI found in plain output: %q", out)
	}
	if !strings.Contains(out, "Gradle daemon") {
		t.Fatalf("missing type: %s", out)
	}
	if !strings.Contains(out, "G1") {
		t.Fatalf("missing GC type: %s", out)
	}
	if !strings.Contains(out, "GC") {
		t.Fatalf("missing GC column: %s", out)
	}
	if !strings.Contains(out, "HIGH MEM") || !strings.Contains(out, "AUTOMATED") {
		t.Fatalf("missing process signals: %s", out)
	}
}

func TestWriteProcessesDetailedPlainIncludesExtendedMetrics(t *testing.T) {
	threads, readBytes, writeBytes := int64(12), int64(1024), int64(2048)
	metaspace, youngTime, oldTime := int64(64), int64(7), int64(9)
	javaVersion, javaVendor := "21", "Temurin"
	var buf bytes.Buffer
	WriteProcessesDetailedPlain(&buf, model.Snapshot{SampledAtMs: 100, Processes: []model.Process{{
		PID: 7, Type: "GRADLE_DAEMON", RSSMemoryMB: 256, ThreadCount: &threads,
		ReadBytes: &readBytes, WriteBytes: &writeBytes, MetaspaceUsedMB: &metaspace,
		YoungGCTimeMs: &youngTime, OldGCTimeMs: &oldTime, JavaVersion: &javaVersion, JavaVendor: &javaVendor,
	}}})
	out := buf.String()
	for _, want := range []string{"pid=7", "threads=12", "read_bytes=1024", "write_bytes=2048", "metaspace_used_mb=64", "young_gc_time_ms=7", "old_gc_time_ms=9", "java_version=21", "java_vendor=Temurin"} {
		if !strings.Contains(out, want) {
			t.Fatalf("detailed output missing %q: %s", want, out)
		}
	}
	if !strings.Contains(out, "open_file_descriptors=n/a") {
		t.Fatalf("unavailable metric was not rendered cleanly: %s", out)
	}
}

func TestWriteJSONStableSchema(t *testing.T) {
	var buf bytes.Buffer
	snap := model.Snapshot{SampledAtMs: 42, Processes: []model.Process{}}
	if err := WriteJSON(&buf, snap); err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(buf.Bytes(), &decoded); err != nil {
		t.Fatal(err)
	}
	if _, ok := decoded["sampled_at_ms"]; !ok {
		t.Fatalf("missing sampled_at_ms: %v", decoded)
	}
	if _, ok := decoded["processes"]; !ok {
		t.Fatalf("missing processes: %v", decoded)
	}
}

func TestStructuredProcessExportsIncludeExtendedMetrics(t *testing.T) {
	threads := int64(12)
	snap := model.Snapshot{Processes: []model.Process{{PID: 7, ThreadCount: &threads}}}
	for name, write := range map[string]func(*bytes.Buffer) error{
		"json":  func(buf *bytes.Buffer) error { return WriteJSON(buf, snap) },
		"jsonl": func(buf *bytes.Buffer) error { return WriteJSONLine(buf, snap) },
	} {
		var buf bytes.Buffer
		if err := write(&buf); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		var decoded struct {
			Processes []map[string]any `json:"processes"`
		}
		if err := json.Unmarshal(buf.Bytes(), &decoded); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if got := decoded.Processes[0]["thread_count"]; got != float64(12) {
			t.Fatalf("%s thread_count=%v", name, got)
		}
	}
}

func TestEmptyProcessesMessage(t *testing.T) {
	var buf bytes.Buffer
	WriteProcessesPlain(&buf, model.Snapshot{SampledAtMs: 1, Processes: nil})
	if !strings.Contains(buf.String(), "No Gradle-related processes") {
		t.Fatalf("unexpected: %s", buf.String())
	}
}

func TestHeapTextFormatsLiveAndUnavailableValues(t *testing.T) {
	used := int64(1536)
	if got := HeapText(&used); got != "1.5 GB" {
		t.Fatalf("live heap=%q want 1.5 GB", got)
	}
	if got := HeapText(nil); got != "n/a" {
		t.Fatalf("unavailable heap=%q want n/a", got)
	}
}

func TestProcessSignalsUseDesktopThresholds(t *testing.T) {
	if got := ProcessSignals(model.Process{RSSMemoryMB: MemoryWarnMB - 1}); len(got) != 0 {
		t.Fatalf("below warning threshold=%v", got)
	}
	if got := ProcessSignals(model.Process{RSSMemoryMB: MemoryWarnMB}); strings.Join(got, " ") != "HIGH MEM" {
		t.Fatalf("warning signals=%v", got)
	}
	if got := ProcessSignals(model.Process{RSSMemoryMB: MemoryCritMB, Automated: true}); strings.Join(got, " ") != "CRIT MEM AUTOMATED" {
		t.Fatalf("critical signals=%v", got)
	}
}

func TestWriteBuildsPlainIncludesOriginAndAgent(t *testing.T) {
	var buf bytes.Buffer
	WriteBuildsPlain(&buf, client.BuildsPayload{
		Count:  1,
		Builds: []client.BuildRecord{{BuildID: "build-1", DaemonPID: 9, InferredSource: "IDE", Agent: "Claude Code", AgentProvider: "Anthropic", FinalStatus: "SUCCESS"}},
	})
	out := buf.String()
	if !strings.Contains(out, "source=IDE") || !strings.Contains(out, "agent=Claude Code") || !strings.Contains(out, "provider=Anthropic") {
		t.Fatalf("missing build origin or agent: %s", out)
	}
}

func TestWriteBuildsJSONKeepsBuildMetadataForScopedResult(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteJSON(&buf, client.BuildsPayload{
		Count:  1,
		Builds: []client.BuildRecord{{BuildID: "build-42", DaemonPID: 42, FinalStatus: "SUCCESS", InferredSource: "IDE", ProjectPath: "/work/demo"}},
	}); err != nil {
		t.Fatal(err)
	}
	var payload client.BuildsPayload
	if err := json.Unmarshal(buf.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Count != 1 || len(payload.Builds) != 1 || payload.Builds[0].DaemonPID != 42 || payload.Builds[0].ProjectPath != "/work/demo" {
		t.Fatalf("scoped build payload=%+v", payload)
	}
}

func TestRowOrientedExports(t *testing.T) {
	threads, readBytes, writeBytes := int64(4), int64(10), int64(20)
	var buf bytes.Buffer
	if err := WriteProcessesCSV(&buf, model.Snapshot{SampledAtMs: 7, Processes: []model.Process{{PID: 9, Type: "GRADLE_DAEMON", RSSMemoryMB: 512, ThreadCount: &threads, ReadBytes: &readBytes, WriteBytes: &writeBytes, ProjectPath: strPtr("/tmp/demo")}}}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "sampled_at_ms,pid,parent_pid,type") || !strings.Contains(buf.String(), "7,9,0,GRADLE_DAEMON") {
		t.Fatalf("unexpected process CSV: %s", buf.String())
	}
	if !strings.Contains(buf.String(), "thread_count") || !strings.Contains(buf.String(), "read_bytes") || !strings.Contains(buf.String(), "write_bytes") || !strings.Contains(buf.String(), ",4,10,20,") {
		t.Fatalf("extended process metrics missing from CSV: %s", buf.String())
	}

	buf.Reset()
	if err := WriteJSONLine(&buf, map[string]string{"kind": "snapshot"}); err != nil {
		t.Fatal(err)
	}
	if strings.Count(strings.TrimSpace(buf.String()), "\n") != 0 || !strings.HasSuffix(buf.String(), "\n") {
		t.Fatalf("expected one JSON line: %q", buf.String())
	}
}

func strPtr(s string) *string { return &s }
