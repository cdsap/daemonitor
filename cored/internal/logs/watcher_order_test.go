package logs_test

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/logs"
)

func TestWatcherListIsDeterministicNewestFirst(t *testing.T) {
	home := t.TempDir()
	dir := filepath.Join(home, "daemon", "9.6.1")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	base := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)
	files := []struct {
		name string
		ts   time.Time
	}{
		{"daemon-101.out.log", base.Add(1 * time.Minute)},
		{"daemon-202.out.log", base.Add(3 * time.Minute)},
		{"daemon-303.out.log", base.Add(2 * time.Minute)},
		{"daemon-150.out.log", base.Add(2 * time.Minute)}, // tie with 303 -> lower PID first
	}
	for _, f := range files {
		p := filepath.Join(dir, f.name)
		if err := os.WriteFile(p, []byte("log\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := os.Chtimes(p, f.ts, f.ts); err != nil {
			t.Fatal(err)
		}
	}

	w := logs.NewWatcher(home)
	if _, err := w.Poll(map[int64]struct{}{}); err != nil {
		t.Fatal(err)
	}

	wantPIDs := []int64{202, 150, 303, 101}
	for attempt := 0; attempt < 25; attempt++ {
		got := w.List()
		if len(got) != len(wantPIDs) {
			t.Fatalf("len=%d want %d", len(got), len(wantPIDs))
		}
		for i, want := range wantPIDs {
			if got[i].PID != want {
				t.Fatalf("attempt %d: index %d pid=%d want %d (full=%+v)", attempt, i, got[i].PID, want, got)
			}
		}
	}
}
