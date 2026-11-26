package store_test

import (
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/ipc"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/poll"
	"github.com/cdsap/daemonitor/cored/internal/store"
	_ "modernc.org/sqlite"
)

func TestJVMMeasurementTimesSurviveHistoryAndLegacyMigration(t *testing.T) {
	path := filepath.Join(t.TempDir(), "times.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	measured, used, gc := int64(1000), int64(42), int64(7)
	for _, observed := range []int64{2000, 4000} {
		if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: observed, Processes: []model.Process{{
			PID: 7, Status: "R", HeapAvailable: true, HeapUsedMB: &used, YoungGCCount: &gc,
			HeapSampledAtMs: &measured, JVMMeasurementTimes: map[string]int64{"heap": 1000, "gc": 1100, "jit": 1200},
		}}}); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	history, err := s.History(0, 10)
	if err != nil || len(history) != 2 {
		t.Fatalf("history=%+v err=%v", history, err)
	}
	for i, p := range history {
		if p.SampledAtMs != int64((i+1)*2000) || p.HeapSampledAtMs == nil || *p.HeapSampledAtMs != measured || p.HeapUsedMB == nil || *p.HeapUsedMB != used || p.YoungGCCount == nil || *p.YoungGCCount != gc || !reflect.DeepEqual(p.JVMMeasurementTimes, map[string]int64{"heap": 1000, "gc": 1100}) {
			t.Fatalf("lost retained measurement: %+v", p)
		}
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	// Model an older store with values and a historical heap timestamp but no
	// trustworthy per-family acquisition times. Migration must not invent them.
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec("ALTER TABLE process_samples DROP COLUMN jvm_sampled_at_ms")
	_ = db.Close()
	if err != nil {
		t.Fatal(err)
	}
	s, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	history, err = s.History(0, 10)
	if err != nil || len(history) != 2 {
		t.Fatalf("legacy history=%+v err=%v", history, err)
	}
	if history[0].JVMMeasurementTimes != nil || history[0].HeapSampledAtMs == nil || *history[0].HeapSampledAtMs != measured {
		t.Fatalf("migration changed legacy semantics: %+v", history[0])
	}
}

func TestInsertHistoryAndPurge(t *testing.T) {
	dir := t.TempDir()
	s, err := store.Open(filepath.Join(dir, "t.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	now := time.Now().UnixMilli()
	cpu := 1.5
	cpuUser, cpuSystem, cpuTotal, coresUsed := 10.25, 2.75, 13.0, 1.5
	logicalProcessors := int64(8)
	maxHeap := int64(1024)
	vms, threads, readBytes, youngGC := int64(2048), int64(42), int64(12345), int64(7)
	fullGC, concurrentGC := int64(3), int64(4)
	javaVersion, javaRuntimeVersion, javaVendor := "21.0.1", "21.0.1+12", "Eclipse Adoptium"
	javaVMName, javaVMVersion, osName, osArch := "OpenJDK 64-Bit Server VM", "21.0.1+12", "Linux", "amd64"
	snap := model.Snapshot{
		SampledAtMs: now,
		Processes: []model.Process{{
			PID: 42, Type: "GRADLE_DAEMON", Name: "java",
			CommandLine: "GradleDaemon", RSSMemoryMB: 100, VirtualMemoryMB: &vms, ThreadCount: &threads,
			ReadBytes: &readBytes, YoungGCCount: &youngGC, FullGCCount: &fullGC, ConcurrentGCCount: &concurrentGC, JavaVersion: &javaVersion, JavaRuntimeVersion: &javaRuntimeVersion,
			JavaVendor: &javaVendor, JavaVMName: &javaVMName, JavaVMVersion: &javaVMVersion, OSName: &osName, OSArch: &osArch,
			CPUPercent:          &cpu,
			CPUUserSecondsTotal: &cpuUser, CPUSystemSecondsTotal: &cpuSystem, CPUTotalSecondsTotal: &cpuTotal,
			CPUCoresUsed: &coresUsed, LogicalProcessorCount: &logicalProcessors,
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
	if hist[0].VirtualMemoryMB == nil || *hist[0].VirtualMemoryMB != vms || hist[0].ThreadCount == nil || *hist[0].ThreadCount != threads {
		t.Fatalf("OS metrics: vms=%v threads=%v", hist[0].VirtualMemoryMB, hist[0].ThreadCount)
	}
	if hist[0].ReadBytes == nil || *hist[0].ReadBytes != readBytes || hist[0].YoungGCCount == nil || *hist[0].YoungGCCount != youngGC {
		t.Fatalf("counter metrics: read=%v young=%v", hist[0].ReadBytes, hist[0].YoungGCCount)
	}
	if hist[0].FullGCCount == nil || *hist[0].FullGCCount != fullGC || hist[0].ConcurrentGCCount == nil || *hist[0].ConcurrentGCCount != concurrentGC {
		t.Fatalf("GC counters: full=%v concurrent=%v", hist[0].FullGCCount, hist[0].ConcurrentGCCount)
	}
	if hist[0].JavaVersion == nil || *hist[0].JavaVersion != javaVersion || hist[0].JavaVendor == nil || *hist[0].JavaVendor != javaVendor {
		t.Fatalf("JVM identity: version=%v vendor=%v", hist[0].JavaVersion, hist[0].JavaVendor)
	}
	if hist[0].JavaRuntimeVersion == nil || *hist[0].JavaRuntimeVersion != javaRuntimeVersion || hist[0].JavaVMName == nil || *hist[0].JavaVMName != javaVMName || hist[0].JavaVMVersion == nil || *hist[0].JavaVMVersion != javaVMVersion || hist[0].OSName == nil || *hist[0].OSName != osName || hist[0].OSArch == nil || *hist[0].OSArch != osArch {
		t.Fatalf("allowlisted JVM metadata: runtime=%v vm=%v/%v os=%v/%v", hist[0].JavaRuntimeVersion, hist[0].JavaVMName, hist[0].JavaVMVersion, hist[0].OSName, hist[0].OSArch)
	}
	if hist[0].CPUUserSecondsTotal == nil || *hist[0].CPUUserSecondsTotal != cpuUser || hist[0].CPUSystemSecondsTotal == nil || *hist[0].CPUSystemSecondsTotal != cpuSystem || hist[0].CPUTotalSecondsTotal == nil || *hist[0].CPUTotalSecondsTotal != cpuTotal || hist[0].CPUCoresUsed == nil || *hist[0].CPUCoresUsed != coresUsed || hist[0].LogicalProcessorCount == nil || *hist[0].LogicalProcessorCount != logicalProcessors {
		t.Fatalf("CPU metrics: user=%v system=%v total=%v cores=%v logical=%v", hist[0].CPUUserSecondsTotal, hist[0].CPUSystemSecondsTotal, hist[0].CPUTotalSecondsTotal, hist[0].CPUCoresUsed, hist[0].LogicalProcessorCount)
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

func TestCommandLinesAreNormalizedWithoutChangingHistory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "commands.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	command := "java -cp " + strings.Repeat("/long/classpath.jar:", 500)
	for i := 0; i < 20; i++ {
		if err := s.InsertSnapshotForHost("host", model.Snapshot{SampledAtMs: int64(i + 1), Processes: []model.Process{{PID: 7, StartTimeMs: 1, CommandLine: command, Status: "R"}}}); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	var bytes int
	if err := db.QueryRow(`SELECT SUM(length(command_line)) FROM process_samples`).Scan(&bytes); err != nil {
		t.Fatal(err)
	}
	if bytes != 0 {
		t.Fatalf("sample command-line bytes=%d, want 0", bytes)
	}
	var commands int
	if err := db.QueryRow(`SELECT COUNT(*) FROM process_command_lines`).Scan(&commands); err != nil || commands != 1 {
		t.Fatalf("commands=%d err=%v", commands, err)
	}
	// Legacy rows and normalized rows must coexist without rewriting old history.
	if _, err := db.Exec(`INSERT INTO process_samples(timestamp, pid, parent_pid, process_type, command_line, rss_memory_mb, status) VALUES (21, 7, 0, 'JAVA', 'legacy', 1, 'R')`); err != nil {
		t.Fatal(err)
	}
	_ = db.Close()
	s, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, p := range []model.Process{{PID: 7, StartTimeMs: 1, CommandLine: "changed", Status: "R"}, {PID: 7, StartTimeMs: 2, CommandLine: "reused", Status: "R"}, {PID: 8, CommandLine: "unknown start", Status: "R"}} {
		if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: 22, Processes: []model.Process{p}}); err != nil {
			t.Fatal(err)
		}
	}
	history, err := s.History(0, 100)
	if err != nil || len(history) != 24 {
		t.Fatalf("history length=%d err=%v", len(history), err)
	}
	for i, p := range history {
		want := command
		if i >= 20 {
			want = []string{"legacy", "changed", "reused", "unknown start"}[i-20]
		}
		if p.CommandLine != want {
			t.Fatalf("row %d command line differs", i)
		}
	}
	// Keep one shared command alive while removing the old samples and commands.
	if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: time.Now().UnixMilli(), Processes: []model.Process{{PID: 9, CommandLine: command, Status: "R"}}}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.PurgeOlderThan(time.Hour); err != nil {
		t.Fatal(err)
	}
	history, err = s.History(0, 100)
	if err != nil || len(history) != 1 || history[0].CommandLine != command {
		t.Fatalf("retained history length=%d err=%v", len(history), err)
	}
	db, err = sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.QueryRow(`SELECT COUNT(*) FROM process_command_lines`).Scan(&commands); err != nil || commands != 1 {
		t.Fatalf("commands after purge=%d err=%v", commands, err)
	}
}

func TestPurgeOlderThanDeletesOldBuilds(t *testing.T) {
	dir := t.TempDir()
	s, err := store.Open(filepath.Join(dir, "builds-purge.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	now := time.Now().UnixMilli()
	oldStart := now - int64((48 * time.Hour).Milliseconds())
	keepStart := now - int64((1 * time.Hour).Milliseconds())
	secret := "bazel build //:app --token=super-secret"
	if err := s.InsertBuild(builds.Build{
		BuildID: "old-bep", DaemonPID: 9, StartTimeMs: oldStart, FinalStatus: builds.StatusSuccess,
		Tool: builds.ToolBazel, InferredSource: builds.SourceBEP, CommandLine: &secret,
	}); err != nil {
		t.Fatal(err)
	}
	if err := s.InsertBuild(builds.Build{
		BuildID: "keep-bep", DaemonPID: 9, StartTimeMs: keepStart, FinalStatus: builds.StatusSuccess,
		Tool: builds.ToolBazel, InferredSource: builds.SourceBEP,
	}); err != nil {
		t.Fatal(err)
	}

	deleted, err := s.PurgeOlderThan(24 * time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if deleted < 1 {
		t.Fatalf("expected builds purge rows, deleted=%d", deleted)
	}
	rows, err := s.ListBuilds(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].BuildID != "keep-bep" {
		t.Fatalf("after purge builds=%+v", rows)
	}
}

func TestOpenRestrictsDatabasePermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX file modes are not enforced on Windows")
	}
	path := filepath.Join(t.TempDir(), "private.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := info.Mode().Perm(); got != 0o600 {
		t.Fatalf("database permissions=%o want 600", got)
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
		"inferred_source", "final_status", "log_snippet", "agent", "agent_provider", "tool", "bazel_profile", "window_summaries",
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

func TestListBuildsFilteredAppliesDaemonProjectAndStatus(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "builds.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, b := range []builds.Build{
		{BuildID: "match", DaemonPID: 42, DaemonIdentity: "current-uid", ProjectPath: "/work/demo", FinalStatus: builds.StatusFailed},
		{BuildID: "other-pid", DaemonPID: 7, DaemonIdentity: "current-uid", ProjectPath: "/work/demo", FinalStatus: builds.StatusFailed},
		{BuildID: "other-status", DaemonPID: 42, DaemonIdentity: "current-uid", ProjectPath: "/work/demo", FinalStatus: builds.StatusSuccess},
	} {
		if err := s.InsertBuild(b); err != nil {
			t.Fatal(err)
		}
	}
	rows, err := s.ListBuildsFiltered(store.BuildFilters{PID: 42, Project: "DEMO", Status: "failed"}, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].BuildID != "match" {
		t.Fatalf("filtered builds=%+v", rows)
	}
}

func TestListBuildsForDaemonMatchesIdentityAsWellAsPID(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "scoped-builds.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, b := range []builds.Build{
		{BuildID: "other", DaemonPID: 7, DaemonIdentity: "daemon-a", StartTimeMs: 400, FinalStatus: builds.StatusSuccess},
		{BuildID: "old", DaemonPID: 42, DaemonIdentity: "daemon-a", StartTimeMs: 100, FinalStatus: builds.StatusSuccess},
		{BuildID: "newest", DaemonPID: 42, DaemonIdentity: "daemon-a", StartTimeMs: 300, FinalStatus: builds.StatusSuccess},
		{BuildID: "different-identity", DaemonPID: 42, DaemonIdentity: "daemon-b", StartTimeMs: 500, FinalStatus: builds.StatusSuccess},
		{BuildID: "middle", DaemonPID: 42, DaemonIdentity: "daemon-a", StartTimeMs: 200, FinalStatus: builds.StatusSuccess},
	} {
		if err := s.InsertBuild(b); err != nil {
			t.Fatal(err)
		}
	}
	rows, err := s.ListBuildsForDaemon(42, "daemon-a", 2)
	if err != nil {
		t.Fatal(err)
	}
	if got := []string{rows[0].BuildID, rows[1].BuildID}; !equalStrings(got, []string{"newest", "middle"}) {
		t.Fatalf("build ids=%v", got)
	}
}

func TestListBuildsForDaemonSinceExcludesEarlierPIDLifetime(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "scoped-since.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, b := range []builds.Build{
		{BuildID: "old", DaemonPID: 9, StartTimeMs: 100, ProjectPath: "/old", FinalStatus: builds.StatusSuccess},
		{BuildID: "current", DaemonPID: 9, StartTimeMs: 200, ProjectPath: "/current", FinalStatus: builds.StatusSuccess},
		{BuildID: "other", DaemonPID: 10, StartTimeMs: 300, ProjectPath: "/other", FinalStatus: builds.StatusSuccess},
	} {
		if err := s.InsertBuild(b); err != nil {
			t.Fatal(err)
		}
	}
	rows, err := s.ListBuildsForDaemonSince(9, 200, 20)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].BuildID != "current" {
		t.Fatalf("scoped rows=%v", rows)
	}
}

func TestListBuildsForDaemonReturnsEmptyForInvalidIdentifiers(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "builds.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	rows, err := s.ListBuildsForDaemon(42, "missing", 10)
	if err != nil || len(rows) != 0 {
		t.Fatalf("rows=%v err=%v", rows, err)
	}
	for _, tc := range []struct {
		pid      int64
		identity string
	}{
		{pid: 0, identity: "daemon-a"},
		{pid: -1, identity: "daemon-a"},
		{pid: 42, identity: ""},
		{pid: 42, identity: "   "},
	} {
		if rows, err := s.ListBuildsForDaemon(tc.pid, tc.identity, 10); err != nil || len(rows) != 0 {
			t.Errorf("pid=%d identity=%q rows=%v err=%v", tc.pid, tc.identity, rows, err)
		}
	}
}

func equalStrings(got, want []string) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		if got[i] != want[i] {
			return false
		}
	}
	return true
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

func TestProcessSamplesIncludesAppWatcherColumns(t *testing.T) {
	dir := t.TempDir()
	s, err := store.Open(filepath.Join(dir, "samples.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	cols, err := s.ProcessSamplesColumns()
	if err != nil {
		t.Fatal(err)
	}
	for _, banned := range []string{"timestamp_ms"} {
		for _, c := range cols {
			if c == banned {
				t.Fatalf("legacy column %q present: %v", banned, cols)
			}
		}
	}
	required := []string{
		"timestamp", "pid", "parent_pid", "process_type", "command_line",
		"working_directory", "project_path", "cpu_percent", "rss_memory_mb", "max_heap_mb",
		"heap_used_mb", "heap_committed_mb", "heap_max_mb", "heap_sampled_at_ms", "heap_available",
		"status",
	}
	have := map[string]bool{}
	for _, c := range cols {
		have[c] = true
	}
	for _, col := range required {
		if !have[col] {
			t.Fatalf("missing app column %q in %v", col, cols)
		}
	}
	// Go-only additive columns retained for History IPC.
	for _, col := range []string{"name", "min_heap_mb", "gc", "start_time_ms", "automated"} {
		if !have[col] {
			t.Fatalf("missing Go additive column %q in %v", col, cols)
		}
	}
}

func TestMigratesLegacyProcessSamplesTimestamp(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "legacy-samples.sqlite")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`
CREATE TABLE process_samples (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp_ms INTEGER NOT NULL,
  pid INTEGER NOT NULL,
  parent_pid INTEGER NOT NULL DEFAULT 0,
  process_type TEXT NOT NULL,
  name TEXT NOT NULL,
  command_line TEXT NOT NULL,
  working_directory TEXT,
  project_path TEXT,
  rss_memory_mb INTEGER NOT NULL,
  cpu_percent REAL,
  max_heap_mb INTEGER,
  min_heap_mb INTEGER,
  gc TEXT,
  start_time_ms INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'RUNNING',
  automated INTEGER NOT NULL DEFAULT 0
);
INSERT INTO process_samples(
  timestamp_ms, pid, parent_pid, process_type, name, command_line,
  rss_memory_mb, start_time_ms, status, automated
) VALUES (500, 11, 1, 'GRADLE_DAEMON', 'java', 'GradleDaemon', 64, 1, 'R', 0);
`); err != nil {
		t.Fatal(err)
	}
	_ = db.Close()

	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	cols, err := s.ProcessSamplesColumns()
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range cols {
		if c == "timestamp_ms" {
			t.Fatalf("timestamp_ms still present: %v", cols)
		}
	}
	hist, err := s.History(0, 10)
	if err != nil || len(hist) != 1 {
		t.Fatalf("hist=%d err=%v", len(hist), err)
	}
	if hist[0].SampledAtMs != 500 || hist[0].PID != 11 || hist[0].CommandLine != "GradleDaemon" {
		t.Fatalf("row=%+v", hist[0])
	}
	if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: 600, Processes: []model.Process{{PID: 11, StartTimeMs: 1, CommandLine: "GradleDaemon", Status: "R"}}}); err != nil {
		t.Fatal(err)
	}
	hist, err = s.History(0, 10)
	if err != nil || len(hist) != 2 {
		t.Fatalf("migrated history length=%d err=%v", len(hist), err)
	}
	for _, p := range hist {
		if p.CommandLine != "GradleDaemon" {
			t.Fatalf("migrated command line=%q", p.CommandLine)
		}
	}
}

func TestOpenEnablesWAL(t *testing.T) {
	dir := t.TempDir()
	s, err := store.Open(filepath.Join(dir, "nested", "wal.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	mode, err := s.JournalMode()
	if err != nil {
		t.Fatal(err)
	}
	if mode != "wal" {
		t.Fatalf("journal_mode=%q", mode)
	}
}

func TestOpensAppCreatedDBAndAddsGoColumns(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "watcher.db")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	// Minimal app Watcher.sq shape (no Go-only additive columns).
	if _, err := db.Exec(`
CREATE TABLE process_samples (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp INTEGER NOT NULL,
  pid INTEGER NOT NULL,
  parent_pid INTEGER NOT NULL,
  process_type TEXT NOT NULL,
  command_line TEXT NOT NULL,
  working_directory TEXT,
  project_path TEXT,
  cpu_percent REAL,
  rss_memory_mb INTEGER NOT NULL,
  max_heap_mb INTEGER,
  heap_used_mb INTEGER,
  heap_committed_mb INTEGER,
  heap_max_mb INTEGER,
  heap_sampled_at_ms INTEGER,
  heap_available INTEGER,
  status TEXT NOT NULL
);
CREATE TABLE builds (
  build_id TEXT PRIMARY KEY,
  daemon_pid INTEGER NOT NULL,
  daemon_identity TEXT,
  command_line TEXT,
  working_directory TEXT,
  project_path TEXT,
  start_time INTEGER NOT NULL,
  end_time INTEGER,
  duration_seconds REAL,
  peak_memory_mb INTEGER,
  avg_memory_mb INTEGER,
  peak_cpu_percent REAL,
  inferred_source TEXT NOT NULL DEFAULT 'UNKNOWN',
  final_status TEXT NOT NULL,
  log_snippet TEXT,
  agent TEXT,
  agent_provider TEXT
);
`); err != nil {
		t.Fatal(err)
	}
	_ = db.Close()

	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	cols, err := s.ProcessSamplesColumns()
	if err != nil {
		t.Fatal(err)
	}
	for _, col := range []string{"name", "min_heap_mb", "gc", "start_time_ms", "automated"} {
		found := false
		for _, c := range cols {
			if c == col {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("missing additive column %q in %v", col, cols)
		}
	}

	now := time.Now().UnixMilli()
	if err := s.InsertSnapshot(model.Snapshot{
		SampledAtMs: now,
		Processes: []model.Process{{
			PID: 99, Type: "GRADLE_DAEMON", Name: "java",
			CommandLine: "GradleDaemon", RSSMemoryMB: 10, SampledAtMs: now, Status: "R",
		}},
	}); err != nil {
		t.Fatal(err)
	}
	n, err := s.Count()
	if err != nil || n != 1 {
		t.Fatalf("count=%d err=%v", n, err)
	}
}

func TestDefaultWatcherDBPathEndsWithWatcherDB(t *testing.T) {
	p := store.DefaultWatcherDBPath()
	if filepath.Base(p) != "watcher.db" {
		t.Fatalf("path=%q", p)
	}
	if filepath.Base(filepath.Dir(p)) != "Daemonitor" {
		t.Fatalf("parent=%q", filepath.Dir(p))
	}
}

func TestDefaultSocketPathBesideAppSupport(t *testing.T) {
	sock := store.DefaultSocketPath()
	if runtime.GOOS == "windows" {
		if !ipc.IsNamedPipe(sock) {
			t.Fatalf("sock=%q want named pipe", sock)
		}
		if !strings.Contains(sock, `daemonitor-core`) {
			t.Fatalf("sock=%q", sock)
		}
		return
	}
	if filepath.Base(sock) != "daemonitor-core.sock" {
		t.Fatalf("sock=%q", sock)
	}
	if filepath.Dir(sock) != store.DefaultAppSupportDir() {
		t.Fatalf("dir=%q want %q", filepath.Dir(sock), store.DefaultAppSupportDir())
	}
}

func TestBuildsToolColumnDefaultsAndFilters(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "tool.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, b := range []builds.Build{
		{BuildID: "g", DaemonPID: 1, StartTimeMs: 100, InferredSource: builds.SourceTerminal, FinalStatus: builds.StatusSuccess},
		{BuildID: "b", DaemonPID: 2, StartTimeMs: 200, InferredSource: builds.SourceBEP, FinalStatus: builds.StatusSuccess, Tool: builds.ToolBazel},
	} {
		if err := s.InsertBuild(b); err != nil {
			t.Fatal(err)
		}
	}
	rows, err := s.ListBuildsFiltered(store.BuildFilters{Tool: "bazel"}, 10)
	if err != nil || len(rows) != 1 || rows[0].BuildID != "b" ||
		rows[0].Tool != builds.ToolBazel || rows[0].InferredSource != builds.SourceBEP {
		t.Fatalf("bazel rows=%+v err=%v", rows, err)
	}
	rows, err = s.ListBuildsFiltered(store.BuildFilters{Tool: "GRADLE"}, 10)
	if err != nil || len(rows) != 1 || rows[0].BuildID != "g" || rows[0].Tool != builds.ToolGradle {
		t.Fatalf("gradle rows=%+v err=%v", rows, err)
	}
	rows, err = s.ListBuildsFiltered(store.BuildFilters{}, 10)
	if err != nil || len(rows) != 2 {
		t.Fatalf("all rows=%+v err=%v", rows, err)
	}
}

func TestMigratesBuildsWithoutToolColumn(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pre-tool.sqlite")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`
CREATE TABLE builds (
  build_id TEXT PRIMARY KEY, daemon_pid INTEGER NOT NULL, daemon_identity TEXT, command_line TEXT,
  working_directory TEXT, project_path TEXT, start_time INTEGER NOT NULL, end_time INTEGER,
  duration_seconds REAL, peak_memory_mb INTEGER, avg_memory_mb INTEGER, peak_cpu_percent REAL,
  inferred_source TEXT NOT NULL DEFAULT 'UNKNOWN', final_status TEXT NOT NULL, log_snippet TEXT,
  agent TEXT, agent_provider TEXT
);
INSERT INTO builds(build_id, daemon_pid, start_time, inferred_source, final_status)
VALUES ('pre-1', 3, 100, 'TERMINAL', 'SUCCESS');`); err != nil {
		t.Fatal(err)
	}
	_ = db.Close()
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	rows, err := s.ListBuildsFiltered(store.BuildFilters{Tool: "GRADLE"}, 10)
	if err != nil || len(rows) != 1 || rows[0].BuildID != "pre-1" || rows[0].Tool != builds.ToolGradle {
		t.Fatalf("rows=%+v err=%v", rows, err)
	}
}

func TestBazelBuildSamplesUseSubtreeWithoutChangingOwnHistory(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "bazel.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	rss := int64(500)
	cpu := 25.0
	ownCPU := 5.0
	snap := model.Snapshot{SampledAtMs: 1000, Processes: []model.Process{{PID: 10, Type: "BAZEL_SERVER", RSSMemoryMB: 100, CPUPercent: &ownCPU, SubtreeRSSMemoryMB: &rss, SubtreeCPUPercent: &cpu}}}
	if err := s.InsertSnapshot(snap); err != nil {
		t.Fatal(err)
	}
	samples := s.SamplesAsBuildSamples(10, 0, 2000)
	if len(samples) != 1 || samples[0].RSSMemoryMB != 500 || samples[0].CPUPercent == nil || *samples[0].CPUPercent != 25 {
		t.Fatalf("build samples=%+v", samples)
	}
	history, err := s.History(0, 10)
	if err != nil || len(history) != 1 || history[0].RSSMemoryMB != 100 || *history[0].CPUPercent != 5 {
		t.Fatalf("own history=%+v err=%v", history, err)
	}
	// Missing subtree CPU is unavailable, even when the server CPU is known.
	snap.SampledAtMs = 1500
	snap.Processes[0].SubtreeCPUPercent = nil
	if err := s.InsertSnapshot(snap); err != nil {
		t.Fatal(err)
	}
	samples = s.SamplesAsBuildSamples(10, 1500, 2000)
	if len(samples) != 1 || samples[0].CPUPercent != nil {
		t.Fatalf("unknown subtree CPU=%+v", samples)
	}
}

func TestBazelSubtreeColumnsMigrateExistingDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: 1, Processes: []model.Process{{PID: 10, Type: "BAZEL_SERVER", RSSMemoryMB: 100}}}); err != nil {
		t.Fatal(err)
	}
	s.Close()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{"ALTER TABLE process_samples DROP COLUMN subtree_rss_memory_mb", "ALTER TABLE process_samples DROP COLUMN subtree_cpu_percent", "PRAGMA user_version = 7"} {
		if _, err := db.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	db.Close()
	s, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	samples := s.SamplesAsBuildSamples(10, 0, 2)
	if len(samples) != 1 || samples[0].RSSMemoryMB != 100 {
		t.Fatalf("old samples lost: %+v", samples)
	}
	rss := int64(500)
	if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: 2, Processes: []model.Process{{PID: 10, Type: "BAZEL_SERVER", RSSMemoryMB: 100, SubtreeRSSMemoryMB: &rss}}}); err != nil {
		t.Fatal(err)
	}
	samples = s.SamplesAsBuildSamples(10, 2, 2)
	if len(samples) != 1 || samples[0].RSSMemoryMB != 500 {
		t.Fatalf("migrated samples=%+v", samples)
	}
}

func TestJVMCountersRoundTripAfterReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "jvm.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	count, zero := int64(9007199254740993), int64(0)
	loaded, unloaded, total := int64(17), int64(3), int64(20)
	jitTime, classTime := 1.23456789, 2.34567891
	young, full, concurrent, gcTotal := 0.00012345, 3.45678912, 0.0, 4.56789123
	sampled := int64(123456)
	probeTime, legacyYoungMs, legacyOldMs := sampled-200, int64(999), int64(555)
	populated := model.Process{PID: 42, StartTimeMs: 100, SampledAtMs: sampled, Type: "GRADLE_DAEMON", Status: "R",
		HeapSampledAtMs: &probeTime, HeapAvailable: true,
		YoungGCTimeMs: &legacyYoungMs, OldGCTimeMs: &legacyOldMs,
		JIT:                &model.JITMetrics{CompiledCount: &count, FailedCount: &zero, InvalidCount: &unloaded, CompilationTimeSeconds: &jitTime},
		Classes:            &model.ClassMetrics{LoadedClasses: &loaded, UnloadedClasses: &unloaded, LoadedClassesTotal: &total, ClassTimeSeconds: &classTime},
		YoungGCTimeSeconds: &young, FullGCTimeSeconds: &full, ConcurrentGCTimeSeconds: &concurrent, TotalGCTimeSeconds: &gcTotal,
	}
	empty := model.Process{PID: 42, StartTimeMs: 200, SampledAtMs: sampled, Type: "GRADLE_DAEMON", Status: "R", JIT: &model.JITMetrics{}, Classes: &model.ClassMetrics{}}
	unavailable := model.Process{PID: 43, StartTimeMs: 300, SampledAtMs: sampled, Type: "GRADLE_DAEMON", Status: "R"}
	want := []model.Process{populated, empty, unavailable}
	if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: sampled, Processes: want}); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	got, err := s.History(0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("JVM history mismatch\ngot: %s\nwant: %s", mustJSON(t, got), mustJSON(t, want))
	}
}

func mustJSON(t *testing.T, value any) string {
	t.Helper()
	b, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestLegacyJVMCountersRemainUnavailable(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy-jvm.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: 1000, Processes: []model.Process{{PID: 7, StartTimeMs: 10, Status: "R"}}}); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	// Recreate the pre-v12 shape, retaining a legacy millisecond measurement.
	for _, col := range []string{"jit_present", "jit_compiled_count", "jit_failed_count", "jit_invalid_count", "jit_compilation_time_seconds", "classes_present", "classes_loaded", "classes_unloaded_total", "classes_loaded_total", "classes_time_seconds", "young_gc_time_seconds", "full_gc_time_seconds", "concurrent_gc_time_seconds", "total_gc_time_seconds"} {
		if _, err := db.Exec("ALTER TABLE process_samples DROP COLUMN " + col); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := db.Exec("UPDATE process_samples SET young_gc_time_ms = 123; PRAGMA user_version = 11"); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		s, err = store.Open(path)
		if err != nil {
			t.Fatal(err)
		}
		rows, err := s.History(0, 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(rows) != 1 {
			t.Fatalf("rows=%d", len(rows))
		}
		p := rows[0]
		if p.JIT != nil || p.Classes != nil || p.YoungGCTimeSeconds != nil || p.FullGCTimeSeconds != nil || p.ConcurrentGCTimeSeconds != nil || p.TotalGCTimeSeconds != nil {
			t.Fatalf("legacy values invented: %s", mustJSON(t, p))
		}
		if p.YoungGCTimeMs == nil || *p.YoungGCTimeMs != 123 || p.StartTimeMs != 10 {
			t.Fatalf("legacy data lost: %s", mustJSON(t, p))
		}
		if err := s.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestKotlinSchemaJVMCountersReadableByGo(t *testing.T) {
	path := filepath.Join(t.TempDir(), "kotlin.sqlite")
	schema, err := os.ReadFile("../../../core/src/main/sqldelight/io/github/cdsap/daemonitor/store/db/Watcher.sq")
	if err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(strings.SplitN(string(schema), "insertSample:", 2)[0]); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO process_samples(timestamp, pid, parent_pid, process_type, command_line, rss_memory_mb, status, start_time_ms,
 jit_present, jit_compiled_count, classes_present, classes_loaded, classes_unloaded_total, classes_loaded_total, young_gc_time_seconds, total_gc_time_seconds)
 VALUES(200, 42, 1, 'GRADLE_DAEMON', 'java', 100, 'R', 123, 1, 0, 1, 17, 3, 20, 0.0, 1.23456789)`); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	rows, err := s.History(0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("rows=%d", len(rows))
	}
	p := rows[0]
	if p.StartTimeMs != 123 || p.SampledAtMs != 200 || p.HeapAvailable || p.JIT == nil || p.JIT.CompiledCount == nil || *p.JIT.CompiledCount != 0 || p.JIT.FailedCount != nil || p.Classes == nil || p.Classes.LoadedClassesTotal == nil || *p.Classes.LoadedClassesTotal != 20 || p.YoungGCTimeSeconds == nil || *p.YoungGCTimeSeconds != 0 || p.TotalGCTimeSeconds == nil || *p.TotalGCTimeSeconds != 1.23456789 {
		t.Fatalf("Kotlin JVM row changed: %s", mustJSON(t, p))
	}
}

func TestHistoryReturnsMostRecentAndFiltersByPIDAndStartTime(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "history-filter.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	const targetPID = int32(50527)
	const targetStart = int64(1000)
	const reusedStart = int64(2000)
	baseTs := int64(1_700_000_000_000)

	// Simulate 12 concurrent processes across 60 poll ticks (720 total rows > 500)
	// spanning 10 minutes (600s > 120s minimum analysis window).
	for tick := 0; tick < 60; tick++ {
		ts := baseTs + int64(tick)*10_000
		procs := make([]model.Process, 0, 12)
		for p := 0; p < 11; p++ {
			procs = append(procs, model.Process{
				PID:         int32(40000 + p),
				Type:        "GRADLE_DAEMON",
				CommandLine: "java GradleDaemon",
				RSSMemoryMB: 200 + int64(tick),
				StartTimeMs: 500,
				Status:      "R",
				SampledAtMs: ts,
			})
		}
		heapMB := int64(100 + tick)
		procs = append(procs, model.Process{
			PID:           targetPID,
			Type:          "GRADLE_DAEMON",
			CommandLine:   "java GradleDaemon",
			RSSMemoryMB:   500 + int64(tick)*2,
			HeapUsedMB:    &heapMB,
			HeapAvailable: true,
			StartTimeMs:   targetStart,
			Status:        "R",
			SampledAtMs:   ts,
		})
		if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: ts, Processes: procs}); err != nil {
			t.Fatal(err)
		}
	}
	// Also insert a different incarnation with the same PID to verify start_time_ms filtering.
	if err := s.InsertSnapshot(model.Snapshot{
		SampledAtMs: baseTs + 61*10_000,
		Processes: []model.Process{{
			PID:         targetPID,
			Type:        "GRADLE_DAEMON",
			CommandLine: "java GradleDaemon",
			RSSMemoryMB: 999,
			StartTimeMs: reusedStart,
			Status:      "R",
			SampledAtMs: baseTs + 61*10_000,
		}},
	}); err != nil {
		t.Fatal(err)
	}

	// Unfiltered History with limit=24 must return the MOST RECENT 24 rows in chronological ASC order.
	recent, err := s.History(baseTs, 24)
	if err != nil {
		t.Fatal(err)
	}
	if len(recent) != 24 {
		t.Fatalf("len(recent)=%d want 24", len(recent))
	}
	if recent[len(recent)-1].SampledAtMs != baseTs+61*10_000 {
		t.Fatalf("newest timestamp=%d want %d", recent[len(recent)-1].SampledAtMs, baseTs+61*10_000)
	}
	for i := 1; i < len(recent); i++ {
		if recent[i].SampledAtMs < recent[i-1].SampledAtMs {
			t.Fatalf("rows out of chronological order at %d: %d < %d", i, recent[i].SampledAtMs, recent[i-1].SampledAtMs)
		}
	}

	// Filtered by PID + StartTimeMs returns all 60 ticks spanning 590s for that exact incarnation.
	scoped, err := s.HistoryFiltered(store.HistoryFilter{
		SinceMs:     baseTs,
		Limit:       500,
		PID:         int64(targetPID),
		StartTimeMs: targetStart,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(scoped) != 60 {
		t.Fatalf("len(scoped)=%d want 60", len(scoped))
	}
	if scoped[0].SampledAtMs != baseTs || scoped[len(scoped)-1].SampledAtMs != baseTs+59*10_000 {
		t.Fatalf("scoped span=[%d..%d] want [%d..%d]", scoped[0].SampledAtMs, scoped[len(scoped)-1].SampledAtMs, baseTs, baseTs+59*10_000)
	}
	for _, row := range scoped {
		if row.PID != targetPID || row.StartTimeMs != targetStart {
			t.Fatalf("unexpected incarnation leaked: pid=%d start=%d", row.PID, row.StartTimeMs)
		}
	}
}
