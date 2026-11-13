package bep_test

import (
	"os"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/bep"
	"github.com/cdsap/daemonitor/cored/internal/builds"
)

func TestParseJSONStreamFixture(t *testing.T) {
	path := "/tmp/daemonitor-bep-spike-fixture/bep.json"
	data, err := os.ReadFile(path)
	if err != nil {
		t.Skip("spike fixture missing:", err)
	}
	closed, err := bep.ParseJSONStream(strings.NewReader(string(data)))
	if err != nil {
		t.Fatal(err)
	}
	if len(closed) != 1 {
		t.Fatalf("closed=%d want 1", len(closed))
	}
	b := closed[0]
	if b.BuildID != "42988224-5d1a-465e-9c1f-0e3a2fdf65cd" {
		t.Fatalf("build_id=%q", b.BuildID)
	}
	if b.DaemonPID != 76097 {
		t.Fatalf("daemon_pid=%d", b.DaemonPID)
	}
	if b.ProjectPath != "/private/tmp/daemonitor-bep-spike-fixture" {
		t.Fatalf("project=%q", b.ProjectPath)
	}
	if b.FinalStatus != builds.StatusSuccess {
		t.Fatalf("status=%s", b.FinalStatus)
	}
	if b.InferredSource != builds.SourceBEP || b.Tool != builds.ToolBazel {
		t.Fatalf("source=%s tool=%s", b.InferredSource, b.Tool)
	}
	if b.DurationSeconds == nil || *b.DurationSeconds <= 0 {
		t.Fatalf("duration=%v", b.DurationSeconds)
	}
}

func TestParseJSONStreamIncomplete(t *testing.T) {
	onlyStart := `{"started":{"uuid":"abc","startTimeMillis":"1","serverPid":"9","workspaceDirectory":"/ws","workingDirectory":"/ws","command":"build"}}` + "\n"
	closed, err := bep.ParseJSONStream(strings.NewReader(onlyStart))
	if err != nil {
		t.Fatal(err)
	}
	if len(closed) != 0 {
		t.Fatalf("incomplete should not close, got %v", closed)
	}
}

func TestParseJSONStreamFailed(t *testing.T) {
	ndjson := strings.Join([]string{
		`{"started":{"uuid":"fail-1","startTimeMillis":"10","serverPid":"3","workspaceDirectory":"/w","workingDirectory":"/w","command":"build"}}`,
		`{"finished":{"finishTimeMillis":"20","exitCode":{"name":"BUILD_FAILURE","code":1}}}`,
	}, "\n") + "\n"
	closed, err := bep.ParseJSONStream(strings.NewReader(ndjson))
	if err != nil {
		t.Fatal(err)
	}
	if len(closed) != 1 || closed[0].FinalStatus != builds.StatusFailed {
		t.Fatalf("closed=%v", closed)
	}
}
