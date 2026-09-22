package logs_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/logs"
)

func TestParseLogPath(t *testing.T) {
	log, ok := logs.ParseLogPath("/Users/dev/.gradle/daemon/8.9/daemon-12914.out.log")
	if !ok {
		t.Fatal("expected parse ok")
	}
	if log.PID != 12914 || log.GradleVersion != "8.9" {
		t.Fatalf("got %+v", log)
	}
	if _, ok := logs.ParseLogPath("/x/8.9/registry.bin"); ok {
		t.Fatal("expected reject")
	}
}

func TestDiscoverAndTailWithRedaction(t *testing.T) {
	root := t.TempDir()
	versionDir := filepath.Join(root, "daemon", "8.10")
	if err := os.MkdirAll(versionDir, 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(versionDir, "daemon-42.out.log")
	content := "hello\nrunning with -Ptoken=topsecret now\nBUILD SUCCESSFUL in 1s\n"
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}

	w := logs.NewWatcher(root)
	if _, err := w.Poll(nil); err != nil {
		t.Fatal(err)
	}
	list := w.List()
	if len(list) != 1 || list[0].PID != 42 {
		t.Fatalf("list=%+v", list)
	}
	tail, ok := w.TailFor(42)
	if !ok {
		t.Fatal("missing tail")
	}
	joined := strings.Join(tail.Lines, "\n")
	if !strings.Contains(joined, "-Ptoken=***") {
		t.Fatalf("expected redaction: %s", joined)
	}
	if strings.Contains(joined, "topsecret") {
		t.Fatalf("secret leaked: %s", joined)
	}
	if !strings.Contains(joined, "BUILD SUCCESSFUL") {
		t.Fatalf("missing build line: %s", joined)
	}
	if len(tail.Events) == 0 {
		t.Fatal("expected parsed build events from tail lines")
	}
	var sawOutcome bool
	for _, ev := range tail.Events {
		if ev.Kind == logs.KindOutcome && ev.Success != nil && *ev.Success {
			sawOutcome = true
		}
	}
	if !sawOutcome {
		t.Fatalf("events=%+v", tail.Events)
	}
}

func TestIncrementalAppend(t *testing.T) {
	root := t.TempDir()
	versionDir := filepath.Join(root, "daemon", "8.10")
	_ = os.MkdirAll(versionDir, 0o755)
	path := filepath.Join(versionDir, "daemon-7.out.log")
	if err := os.WriteFile(path, []byte("line-one\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	w := logs.NewWatcher(root)
	_, _ = w.Poll(nil)
	tail, _ := w.TailFor(7)
	if len(tail.Lines) != 1 {
		t.Fatalf("first poll lines=%v", tail.Lines)
	}

	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = f.WriteString("line-two\n")
	_ = f.Close()

	_, _ = w.Poll(nil)
	tail, _ = w.TailFor(7)
	if len(tail.Lines) != 2 || tail.Lines[1] != "line-two" {
		t.Fatalf("after append=%v", tail.Lines)
	}
}

func TestPollActivePIDsOnly(t *testing.T) {
	root := t.TempDir()
	versionDir := filepath.Join(root, "daemon", "8.10")
	_ = os.MkdirAll(versionDir, 0o755)
	activePath := filepath.Join(versionDir, "daemon-1.out.log")
	idlePath := filepath.Join(versionDir, "daemon-2.out.log")
	if err := os.WriteFile(activePath, []byte("running with -Ptoken=shh now\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(idlePath, []byte("idle with -Ptoken=shh now\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	w := logs.NewWatcher(root)
	if _, err := w.Poll(map[int64]struct{}{1: {}}); err != nil {
		t.Fatal(err)
	}
	if len(w.List()) != 2 {
		t.Fatalf("expected both logs listed, got %+v", w.List())
	}
	active, _ := w.TailFor(1)
	if len(active.Lines) != 1 || !strings.Contains(active.Lines[0], "-Ptoken=***") {
		t.Fatalf("active tail=%v", active.Lines)
	}
	// Idle PID was not polled; TailFor lazily seeds.
	idle, ok := w.TailFor(2)
	if !ok || len(idle.Lines) != 1 {
		t.Fatalf("lazy idle tail=%v ok=%v", idle.Lines, ok)
	}
}
