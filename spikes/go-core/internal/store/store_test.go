package store_test

import (
	"database/sql"
	"path/filepath"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/builds"
	"github.com/cdsap/daemonitor/spikes/go-core/internal/model"
	"github.com/cdsap/daemonitor/spikes/go-core/internal/poll"
	"github.com/cdsap/daemonitor/spikes/go-core/internal/store"
	_ "modernc.org/sqlite"
)

func TestInsertHistoryAndPurge(t *testing.T) {
	dir := t.TempDir()
	s, err := store.Open(filepath.Join(dir, "t.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	now := time.Now().UnixMilli()
	cpu := 1.5
	maxHeap := int64(1024)
	snap := model.Snapshot{
		SampledAtMs: now,
		Processes: []model.Process{{
			PID: 42, Type: "GRADLE_DAEMON", Name: "java",
			CommandLine: "GradleDaemon", RSSMemoryMB: 100, CPUPercent: &cpu,
			MaxHeapMB: &maxHeap, SampledAtMs: now, Status: "R",
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
	if hist[0].MaxHeapMB == nil || *hist[0].MaxHeapMB != 1024 {
		t.Fatalf("max heap=%v", hist[0].MaxHeapMB)
	}

	old := model.Snapshot{
		SampledAtMs: now - int64((48 * time.Hour).Milliseconds()),
		Processes: []model.Process{{
			PID: 7, Type: "KOTLIN_DAEMON", Name: "java",
			CommandLine: "Kotlin", RSSMemoryMB: 50, SampledAtMs: now, Status: "R",
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

func TestClassifyUsedByStoreRoundTrip(t *testing.T) {
	if poll.Classify("java org.gradle.launcher.daemon.bootstrap.GradleDaemon") != "GRADLE_DAEMON" {
		t.Fatal("classifier regression")
	}
}

func TestBuildsTableMatchesAppWatcherColumns(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "builds.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	cols, err := s.BuildsColumns()
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"build_id", "daemon_pid", "daemon_identity", "command_line",
		"working_directory", "project_path", "start_time", "end_time",
		"duration_seconds", "peak_memory_mb", "avg_memory_mb", "peak_cpu_percent",
		"inferred_source", "final_status", "log_snippet", "agent", "agent_provider",
	}
	if len(cols) != len(want) {
		t.Fatalf("cols=%v want=%v", cols, want)
	}
	for i := range want {
		if cols[i] != want[i] {
			t.Fatalf("col[%d]=%q want %q (full=%v)", i, cols[i], want[i], cols)
		}
	}

	cmd := "./gradlew assemble -Ptoken=***"
	end := int64(2_000)
	dur := 1.5
	if err := s.InsertBuild(builds.Build{
		BuildID: "b1", DaemonPID: 9, CommandLine: &cmd,
		WorkingDirectory: "/proj", ProjectPath: "/proj",
		StartTimeMs: 1_000, EndTimeMs: &end, DurationSeconds: &dur,
		InferredSource: builds.SourceTerminal, FinalStatus: builds.StatusSuccess,
		LogSnippet: "BUILD SUCCESSFUL",
	}); err != nil {
		t.Fatal(err)
	}
	rows, err := s.ListBuilds(10)
	if err != nil || len(rows) != 1 {
		t.Fatalf("list=%d err=%v", len(rows), err)
	}
	if rows[0].CommandLine == nil || *rows[0].CommandLine != cmd {
		t.Fatalf("command_line=%v", rows[0].CommandLine)
	}
	if rows[0].StartTimeMs != 1_000 {
		t.Fatalf("start=%d", rows[0].StartTimeMs)
	}
}

func TestMigratesLegacyBuildsColumns(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "legacy.sqlite")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`
CREATE TABLE builds (
  build_id TEXT PRIMARY KEY,
  daemon_pid INTEGER NOT NULL,
  daemon_identity TEXT,
  working_directory TEXT,
  project_path TEXT,
  start_time_ms INTEGER NOT NULL,
  end_time_ms INTEGER,
  duration_seconds REAL,
  peak_memory_mb INTEGER,
  avg_memory_mb INTEGER,
  peak_cpu_percent REAL,
  inferred_source TEXT NOT NULL,
  final_status TEXT NOT NULL,
  log_snippet TEXT,
  agent TEXT,
  agent_provider TEXT
);
INSERT INTO builds(build_id, daemon_pid, working_directory, project_path, start_time_ms, end_time_ms,
  duration_seconds, inferred_source, final_status, log_snippet)
VALUES ('legacy-1', 3, '/old', '/old', 100, 200, 0.1, 'IDE', 'SUCCESS', 'ok');
`); err != nil {
		t.Fatal(err)
	}
	_ = db.Close()

	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	cols, err := s.BuildsColumns()
	if err != nil {
		t.Fatal(err)
	}
	for _, banned := range []string{"start_time_ms", "end_time_ms"} {
		for _, c := range cols {
			if c == banned {
				t.Fatalf("legacy column %q still present: %v", banned, cols)
			}
		}
	}
	rows, err := s.ListBuilds(10)
	if err != nil || len(rows) != 1 || rows[0].BuildID != "legacy-1" {
		t.Fatalf("migrated rows=%v err=%v", rows, err)
	}
	if rows[0].StartTimeMs != 100 || rows[0].EndTimeMs == nil || *rows[0].EndTimeMs != 200 {
		t.Fatalf("times start=%d end=%v", rows[0].StartTimeMs, rows[0].EndTimeMs)
	}
}
