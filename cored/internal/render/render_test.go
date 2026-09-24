package render_test

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
)

func TestProcessesPlainNoANSI(t *testing.T) {
	cpu := 12.5
	xmx := int64(512)
	project := "/tmp/demo"
	snap := model.Snapshot{
		SampledAtMs: 1_700_000_000_000,
		Processes: []model.Process{
			{PID: 42, Type: "GRADLE_DAEMON", Name: "Daemon", RSSMemoryMB: 256, CPUPercent: &cpu, MaxHeapMB: &xmx, ProjectPath: &project},
		},
	}
	var buf bytes.Buffer
	if err := render.ProcessesPlain(&buf, snap); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if render.ContainsANSI(out) {
		t.Fatalf("plain output must not contain ANSI: %q", out)
	}
	if !strings.Contains(out, "pid=42") || !strings.Contains(out, "GRADLE_DAEMON") {
		t.Fatalf("unexpected plain output:\n%s", out)
	}
}

func TestProcessesJSONSchemaStable(t *testing.T) {
	cpu := 1.0
	snap := model.Snapshot{
		SampledAtMs: 100,
		Processes: []model.Process{
			{PID: 7, Type: "KOTLIN_DAEMON", Name: "k", RSSMemoryMB: 64, CPUPercent: &cpu, Status: "running"},
		},
	}
	var buf bytes.Buffer
	if err := render.JSON(&buf, snap); err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(buf.Bytes(), &decoded); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"sampled_at_ms", "processes"} {
		if _, ok := decoded[key]; !ok {
			t.Fatalf("missing top-level key %q in %v", key, decoded)
		}
	}
	procs, ok := decoded["processes"].([]any)
	if !ok || len(procs) != 1 {
		t.Fatalf("processes=%v", decoded["processes"])
	}
	row, ok := procs[0].(map[string]any)
	if !ok {
		t.Fatalf("row type %T", procs[0])
	}
	required := []string{
		"pid", "parent_pid", "type", "name", "command_line",
		"rss_memory_mb", "cpu_percent", "start_time_ms", "status", "automated", "sampled_at_ms",
	}
	for _, key := range required {
		if _, ok := row[key]; !ok {
			t.Fatalf("missing process key %q in %v", key, row)
		}
	}
	if render.ContainsANSI(buf.String()) {
		t.Fatal("JSON must not contain ANSI")
	}
}

func TestEmptySnapshotOK(t *testing.T) {
	var buf bytes.Buffer
	if err := render.ProcessesPlain(&buf, model.Snapshot{SampledAtMs: 1}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "processes=0") {
		t.Fatalf("got %q", buf.String())
	}
}
