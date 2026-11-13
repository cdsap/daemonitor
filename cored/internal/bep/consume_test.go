package bep

import (
	"os"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
)

func TestConsumeJSONBytesStreaming(t *testing.T) {
	part1 := []byte(`{"started":{"uuid":"u1","startTimeMillis":"100","serverPid":"5","workspaceDirectory":"/a","workingDirectory":"/a","command":"test"}}` + "\n")
	part2 := []byte(`{"finished":{"finishTimeMillis":"250","exitCode":{"name":"SUCCESS","code":0}}}` + "\n")
	state := &partial{}
	closed, left, err := ConsumeJSONBytes(state, nil, part1)
	if err != nil || len(closed) != 0 || len(left) != 0 {
		t.Fatalf("after start closed=%v left=%q err=%v", closed, left, err)
	}
	if state.BuildID != "u1" {
		t.Fatalf("state=%+v", state)
	}
	closed, left, err = ConsumeJSONBytes(state, left, part2)
	if err != nil || len(left) != 0 || len(closed) != 1 {
		t.Fatalf("after finish closed=%v left=%q err=%v", closed, left, err)
	}
	if closed[0].FinalStatus != builds.StatusSuccess || closed[0].DaemonPID != 5 {
		t.Fatalf("build=%+v", closed[0])
	}
}

func TestWatcherIngestsCompletedFile(t *testing.T) {
	dir := t.TempDir()
	path := dir + "/bep.json"
	body := `{"started":{"uuid":"watch-1","startTimeMillis":"1","serverPid":"11","workspaceDirectory":"/ws","workingDirectory":"/ws","command":"build"}}` + "\n" +
		`{"finished":{"finishTimeMillis":"2","exitCode":{"name":"SUCCESS"}}}` + "\n"
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	w := NewWatcher()
	w.ExtraPaths = func() []Sink {
		return []Sink{{Path: path, Format: FormatJSON}}
	}
	closed, err := w.Poll(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if len(closed) != 1 || closed[0].BuildID != "watch-1" {
		t.Fatalf("closed=%v", closed)
	}
	closed, err = w.Poll(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if len(closed) != 0 {
		t.Fatalf("duplicate emit: %v", closed)
	}
}

func TestParseJSONStreamFixtures(t *testing.T) {
	f, err := os.Open("testdata/build_success.ndjson")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	got, err := ParseJSONStream(f)
	if err != nil || len(got) != 1 {
		t.Fatalf("got=%v err=%v", got, err)
	}
	b := got[0]
	if b.Tool != builds.ToolBazel || b.InferredSource != builds.SourceBEP ||
		b.FinalStatus != builds.StatusSuccess || b.DaemonPID != 4242 ||
		b.ProjectPath != "/work/app" || b.StartTimeMs != 1700000000000 {
		t.Fatalf("build=%+v", b)
	}
	if b.CommandLine == nil || *b.CommandLine != "bazel build" {
		t.Fatalf("command=%v", b.CommandLine)
	}
	if b.DurationSeconds == nil || *b.DurationSeconds != 12.5 {
		t.Fatalf("duration=%v", b.DurationSeconds)
	}

	f2, err := os.Open("testdata/build_failed_then_incomplete.ndjson")
	if err != nil {
		t.Fatal(err)
	}
	defer f2.Close()
	got, err = ParseJSONStream(f2)
	if err != nil || len(got) != 1 || got[0].FinalStatus != builds.StatusFailed {
		t.Fatalf("incomplete trailing build must not emit: got=%v err=%v", got, err)
	}
}
