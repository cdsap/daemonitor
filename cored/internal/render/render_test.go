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
	var buf bytes.Buffer
	WriteProcessesPlain(&buf, model.Snapshot{
		SampledAtMs: 100,
		Processes: []model.Process{
			{PID: 1, Type: "GRADLE_DAEMON", RSSMemoryMB: 4500, CPUPercent: &cpu, MaxHeapMB: &xmx, HeapUsedMB: &used, Automated: true, ProjectPath: strPtr("/a/b")},
		},
	})
	out := buf.String()
	if strings.Contains(out, "\x1b") {
		t.Fatalf("ANSI found in plain output: %q", out)
	}
	if !strings.Contains(out, "Gradle daemon") {
		t.Fatalf("missing type: %s", out)
	}
	if !strings.Contains(out, "HIGH MEM") || !strings.Contains(out, "AUTOMATED") {
		t.Fatalf("missing process signals: %s", out)
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

func strPtr(s string) *string { return &s }
