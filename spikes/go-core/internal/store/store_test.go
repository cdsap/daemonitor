package store_test

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/model"
	"github.com/cdsap/daemonitor/spikes/go-core/internal/store"
)

func TestInsertHistoryAndPurge(t *testing.T) {
	dir := t.TempDir()
	s, err := store.Open(filepath.Join(dir, "t.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	now := time.Now().UnixMilli()
	snap := model.Snapshot{
		SampledAtMs: now,
		Processes: []model.Process{{
			PID: 42, Type: "GRADLE_DAEMON", Name: "java",
			CommandLine: "GradleDaemon", RSSMemoryMB: 100, CPUPercent: 1.5, SampledAtMs: now,
		}},
	}
	if err := s.InsertSnapshot(snap); err != nil {
		t.Fatal(err)
	}
	n, err := s.Count()
	if err != nil || n != 1 {
		t.Fatalf("count=%d err=%v", n, err)
	}

	hist, err := s.History(now-1000, 10)
	if err != nil || len(hist) != 1 {
		t.Fatalf("history=%d err=%v", len(hist), err)
	}

	// Insert an "old" row and purge.
	old := model.Snapshot{
		SampledAtMs: now - int64((48 * time.Hour).Milliseconds()),
		Processes: []model.Process{{
			PID: 7, Type: "KOTLIN_DAEMON", Name: "java",
			CommandLine: "Kotlin", RSSMemoryMB: 50, CPUPercent: 0, SampledAtMs: now,
		}},
	}
	if err := s.InsertSnapshot(old); err != nil {
		t.Fatal(err)
	}
	deleted, err := s.PurgeOlderThan(24 * time.Hour)
	if err != nil || deleted != 1 {
		t.Fatalf("deleted=%d err=%v", deleted, err)
	}
	n, _ = s.Count()
	if n != 1 {
		t.Fatalf("after purge count=%d", n)
	}
}
