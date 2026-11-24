package bazelprofile

import (
	"compress/gzip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

const trace = `{"otherData":{"build_id":"build-1","bazel_version":"release 8.0.0","date":"2026-11-23T01:00:00Z","profile_start_ts":1795395600000},"traceEvents":[
 {"cat":"build phase marker","name":"Launch Blaze","ph":"X","ts":-100000,"dur":100000},
 {"cat":"build phase marker","name":"Loading","ph":"X","ts":0,"dur":1000000},
 {"cat":"build phase marker","name":"Analysis","ph":"X","ts":1000000,"dur":2000000},
 {"cat":"build phase marker","name":"Execution","ph":"X","ts":3000000,"dur":3000000},
 {"cat":"critical path component","name":"action","ph":"X","ts":4000000,"dur":1500000},
 {"name":"Memory usage (heap)","ph":"C","ts":0,"args":{"used":42}},
 {"name":"Memory usage (heap)","ph":"C","ts":5000000,"args":{"used":84}}
]}`

func writeProfile(t *testing.T, path, body string) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	z := gzip.NewWriter(f)
	if _, err := z.Write([]byte(body)); err != nil {
		t.Fatal(err)
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestWatcherRecordsPlainBuild(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "command.profile.gz")
	writeProfile(t, path, trace)
	if err := os.WriteFile(filepath.Join(dir, "command.log"), []byte("INFO: Invocation ID: build-1\nINFO: Build completed successfully, 1 total action\n"), 0600); err != nil {
		t.Fatal(err)
	}
	processes := []model.Process{{PID: 42, Type: "BAZEL_SERVER", CommandLine: `java -jar /x/A-server.jar --output_base="` + dir + `"`, StartTimeMs: 1795395600100}}
	w := NewWatcher()
	if got := w.Poll(t.Context(), processes); len(got) != 0 {
		t.Fatal("read before stability check", got)
	}
	var got []builds.Build
	deadline := time.Now().Add(3 * time.Second)
	for len(got) == 0 && time.Now().Before(deadline) {
		got = w.Poll(t.Context(), processes)
		time.Sleep(time.Millisecond)
	}
	if len(got) != 1 {
		t.Fatalf("builds=%+v", got)
	}
	b := got[0]
	if b.BuildID != "build-1" || b.DaemonPID != 42 || b.Tool != builds.ToolBazel || b.InferredSource != builds.SourceProfile || b.FinalStatus != builds.StatusSuccess {
		t.Fatalf("build=%+v", b)
	}
	if b.DurationSeconds == nil || *b.DurationSeconds != 6.1 {
		t.Fatalf("duration=%v", b.DurationSeconds)
	}
	if b.BazelProfile == nil || b.BazelProfile.PhaseSeconds["Loading"] != 1 || b.BazelProfile.PhaseSeconds["Analysis"] != 2 || b.BazelProfile.PhaseSeconds["Execution"] != 3 || b.BazelProfile.CriticalPathSeconds != 1.5 || b.BazelProfile.PeakCounters["Memory usage (heap)/used"] != 84 {
		t.Fatalf("metrics=%+v", b.BazelProfile)
	}
	for i := 0; i < 10; i++ {
		if got := w.Poll(t.Context(), processes); len(got) != 0 {
			t.Fatal("duplicate", got)
		}
	}
}

func TestInstantPhaseMarkers(t *testing.T) {
	body := `{"otherData":{"build_id":"instant","date":"2026-11-23T01:00:00Z"},"traceEvents":[
 {"cat":"build phase marker","name":"Build artifacts","ph":"i","ts":3000000},
 {"cat":"build phase marker","name":"Load packages","ph":"i","ts":0},
 {"cat":"build phase marker","name":"Analyze dependencies","ph":"i","ts":1000000},
 {"cat":"build phase marker","name":"Complete build","ph":"i","ts":6000000}]}`
	b, err := parse([]byte(body))
	if err != nil {
		t.Fatal(err)
	}
	if b.BazelProfile.PhaseSeconds["Loading"] != 1 || b.BazelProfile.PhaseSeconds["Analysis"] != 2 || b.BazelProfile.PhaseSeconds["Execution"] != 3 {
		t.Fatalf("phases=%+v", b.BazelProfile.PhaseSeconds)
	}
}

func TestCompletionStatus(t *testing.T) {
	for _, tc := range []struct {
		name, body string
		want       builds.FinalStatus
	}{
		{"success", "INFO: Invocation ID: build-1\nINFO: Build completed successfully, 1 total action", builds.StatusSuccess},
		{"failure", "INFO: Invocation ID: build-1\nINFO: Build did NOT complete successfully", builds.StatusFailed},
		{"unknown", "INFO: Invocation ID: build-1\nINFO: Elapsed time: 1s", builds.StatusCompletedNoOutcome},
		{"stale", "INFO: Invocation ID: other\nINFO: Build completed successfully", builds.StatusCompletedNoOutcome},
		{"unidentified", "INFO: Build completed successfully", builds.StatusCompletedNoOutcome},
		{"test failure", "INFO: Invocation ID: build-1\nINFO: Build completed, 1 test FAILED, 2 total actions", builds.StatusFailed},
		{"new invocation", "INFO: Invocation ID: build-1\nINFO: Build completed successfully\nINFO: Invocation ID: other", builds.StatusCompletedNoOutcome},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "command.log")
			if err := os.WriteFile(path, []byte(tc.body), 0600); err != nil {
				t.Fatal(err)
			}
			if got := completionStatus(path, "build-1"); got != tc.want {
				t.Fatalf("got=%s want=%s", got, tc.want)
			}
		})
	}
}

func TestInvalidProfiles(t *testing.T) {
	for _, tc := range []struct{ name, body string }{
		{"truncated JSON", trace[:len(trace)-4]},
		{"missing id", `{"otherData":{"date":"2026-11-23T01:00:00Z"},"traceEvents":[]}`},
		{"missing timing", `{"otherData":{"build_id":"id"},"traceEvents":[]}`},
		{"oversized decompressed", strings.Replace(trace, `"otherData":`, `"padding":"`+strings.Repeat("x", maxProfileBytes)+`","otherData":`, 1)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "command.profile.gz")
			writeProfile(t, path, tc.body)
			if _, err := readProfile(path); err == nil {
				t.Fatal("invalid profile accepted")
			}
		})
	}
	t.Run("truncated gzip", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "command.profile.gz")
		writeProfile(t, path, trace)
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, data[:len(data)-5], 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := readProfile(path); err == nil {
			t.Fatal("truncated gzip accepted")
		}
	})
	t.Run("oversized compressed", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "command.profile.gz")
		f, err := os.Create(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := f.Truncate(maxProfileBytes + 1); err != nil {
			t.Fatal(err)
		}
		f.Close()
		if _, err := readProfile(path); err == nil {
			t.Fatal("oversized profile accepted")
		}
	})
}

func TestWatcherMissingDisabledAndChanging(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "command.profile.gz")
	p := []model.Process{{PID: 42, Type: "BAZEL_SERVER", CommandLine: "java --output_base=" + dir}}
	w := NewWatcher()
	if got := w.Poll(t.Context(), p); len(got) != 0 {
		t.Fatal(got)
	}
	writeProfile(t, path, trace[:len(trace)-4])
	w.Poll(t.Context(), p)
	writeProfile(t, path, trace)
	if got := w.Poll(t.Context(), p); len(got) != 0 || w.pending != nil {
		t.Fatal("changed file read before stable")
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if got := w.Poll(t.Context(), p); len(got) > 0 {
			if got[0].FinalStatus != builds.StatusCompletedNoOutcome {
				t.Fatal(got)
			}
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("completed replacement not ingested")
}

func TestWatcherIgnoresOtherProcessesAndMissingOutputBase(t *testing.T) {
	w := NewWatcher()
	p := []model.Process{{PID: 1, Type: "BAZEL_SERVER", CommandLine: "java -jar A-server.jar"}, {PID: 2, Type: "GRADLE_DAEMON", CommandLine: "java --output_base=/tmp"}}
	w.Poll(t.Context(), p)
	if len(w.files) != 0 {
		t.Fatal(w.files)
	}
}

func TestWatcherRecoversFromTruncatedProfileAndLateCompletion(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "command.profile.gz")
	writeProfile(t, path, trace[:len(trace)-4])
	p := []model.Process{{PID: 42, Type: "BAZEL_SERVER", CommandLine: `java --output_base="` + dir + `"`}}
	w := NewWatcher()
	w.Poll(t.Context(), p)
	w.Poll(t.Context(), p)
	deadline := time.Now().Add(3 * time.Second)
	for w.pending != nil && time.Now().Before(deadline) {
		if got := w.Poll(t.Context(), p); len(got) != 0 {
			t.Fatal("truncated profile emitted", got)
		}
		time.Sleep(time.Millisecond)
	}
	if w.pending != nil {
		t.Fatal("invalid profile blocked watcher")
	}
	writeProfile(t, path, trace)
	var got []builds.Build
	for len(got) == 0 && time.Now().Before(deadline) {
		got = w.Poll(t.Context(), p)
		time.Sleep(time.Millisecond)
	}
	if len(got) != 1 || got[0].FinalStatus != builds.StatusCompletedNoOutcome {
		t.Fatalf("replacement=%+v", got)
	}
	if err := os.WriteFile(filepath.Join(dir, "command.log"), []byte("INFO: Invocation ID: build-1\nERROR: Build did NOT complete successfully\n"), 0600); err != nil {
		t.Fatal(err)
	}
	got = nil
	for len(got) == 0 && time.Now().Before(deadline) {
		got = w.Poll(t.Context(), p)
		time.Sleep(time.Millisecond)
	}
	if len(got) != 1 || got[0].FinalStatus != builds.StatusFailed {
		t.Fatalf("late outcome=%+v", got)
	}
}
