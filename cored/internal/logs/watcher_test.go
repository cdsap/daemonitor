package logs_test

import (
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/logs"
)

func TestListOrdersActiveThenRecentLogsDeterministically(t *testing.T) {
	root := t.TempDir()
	w := logs.NewWatcher(root)
	now := time.Unix(1700000000, 0)
	var want []logs.DaemonLog
	// Expected order: active even when older, recent history, then PID/path ties.
	for _, fixture := range []struct {
		version string
		pid     int64
		age     time.Duration
	}{
		{"9.0", 90, 3 * time.Hour},
		{"9.0", 80, 0},
		{"8.0", 10, time.Hour},
		{"9.0", 10, time.Hour},
		{"9.0", 20, time.Hour},
	} {
		dir := filepath.Join(root, "daemon", fixture.version)
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(dir, fmt.Sprintf("daemon-%d.out.log", fixture.pid))
		if err := os.WriteFile(path, []byte("line\n"), 0600); err != nil {
			t.Fatal(err)
		}
		mtime := now.Add(-fixture.age)
		if err := os.Chtimes(path, mtime, mtime); err != nil {
			t.Fatal(err)
		}
		want = append(want, logs.DaemonLog{PID: fixture.pid, GradleVersion: fixture.version, Path: path})
	}
	if _, err := w.Poll(map[int64]struct{}{90: {}}); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 50; i++ {
		got := w.List()
		if len(got) != len(want) {
			t.Fatalf("got %d logs, want %d", len(got), len(want))
		}
		// Compare exported metadata; ordering metadata is internal to the watcher.
		for j := range got {
			if got[j].PID != want[j].PID || got[j].Path != want[j].Path {
				t.Fatalf("list %d: got %+v, want %+v", i, got, want)
			}
		}
	}
	// Refresh must clear activity and pick up changed modification times.
	if err := os.Chtimes(want[4].Path, now.Add(time.Hour), now.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if _, err := w.Poll(map[int64]struct{}{}); err != nil {
		t.Fatal(err)
	}
	got := w.List()
	var pids []int64
	for _, log := range got {
		pids = append(pids, log.PID)
	}
	if !reflect.DeepEqual(pids, []int64{20, 80, 10, 10, 90}) {
		t.Fatalf("after refresh: %+v", got)
	}
}

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
