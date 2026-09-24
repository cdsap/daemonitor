package render

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestWriteProcessesPlainNoANSI(t *testing.T) {
	cpu := 12.5
	xmx := int64(2048)
	var buf bytes.Buffer
	WriteProcessesPlain(&buf, model.Snapshot{
		SampledAtMs: 100,
		Processes: []model.Process{
			{PID: 1, Type: "GRADLE_DAEMON", RSSMemoryMB: 100, CPUPercent: &cpu, MaxHeapMB: &xmx, ProjectPath: strPtr("/a/b")},
		},
	})
	out := buf.String()
	if strings.Contains(out, "\x1b") {
		t.Fatalf("ANSI found in plain output: %q", out)
	}
	if !strings.Contains(out, "Gradle daemon") {
		t.Fatalf("missing type: %s", out)
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

func TestEmptyProcessesMessage(t *testing.T) {
	var buf bytes.Buffer
	WriteProcessesPlain(&buf, model.Snapshot{SampledAtMs: 1, Processes: nil})
	if !strings.Contains(buf.String(), "No Gradle-related processes") {
		t.Fatalf("unexpected: %s", buf.String())
	}
}

func strPtr(s string) *string { return &s }
