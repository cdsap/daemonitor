package api

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestDaemonLogListMergesActiveAndRecentLogsBeforeLimit(t *testing.T) {
	home, base := t.TempDir(), t.TempDir()
	now := time.Unix(1700000000, 0)
	write := func(path string, age time.Duration) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("line\n"), 0600); err != nil {
			t.Fatal(err)
		}
		mtime := now.Add(-age)
		if err := os.Chtimes(path, mtime, mtime); err != nil {
			t.Fatal(err)
		}
	}
	// A recent historical Gradle log must not hide either tool's active logs.
	write(filepath.Join(home, "daemon", "9.0", "daemon-1.out.log"), 0)
	write(filepath.Join(home, "daemon", "9.0", "daemon-90.out.log"), 2*time.Hour)
	write(filepath.Join(base, "command.log"), time.Hour)
	write(filepath.Join(base, "server", "jvm.out"), time.Hour)
	s := &Server{Logs: logs.NewWatcher(home), BazelLogs: logs.NewBazelWatcher()}
	if _, err := s.Logs.Poll(map[int64]struct{}{90: {}}); err != nil {
		t.Fatal(err)
	}
	s.BazelLogs.Poll([]model.Process{{PID: 42, Type: "BAZEL_SERVER", BazelOutputBase: base}})
	wantPIDs := []int64{42, 42, 90, 1}
	wantSources := []string{"command.log", "server/jvm.out", "", ""}
	var previous string
	for i := 0; i < 50; i++ {
		rec := httptest.NewRecorder()
		s.handleDaemonLogs(rec, httptest.NewRequest("GET", "/v1/daemon-logs", nil))
		var payload struct {
			Logs []logs.DaemonLog `json:"logs"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
			t.Fatal(err)
		}
		if rec.Code != 200 || len(payload.Logs) != len(wantPIDs) {
			t.Fatalf("list: %d %s", rec.Code, rec.Body)
		}
		for j, log := range payload.Logs {
			if log.PID != wantPIDs[j] || log.Source != wantSources[j] {
				t.Fatalf("position %d: %+v", j, payload.Logs)
			}
		}
		if i > 0 && rec.Body.String() != previous {
			t.Fatalf("list changed without a poll: %s -> %s", previous, rec.Body)
		}
		previous = rec.Body.String()
	}
}

func TestBazelDaemonLogEndpoints(t *testing.T) {
	base := t.TempDir()
	if err := os.Mkdir(filepath.Join(base, "server"), 0700); err != nil {
		t.Fatal(err)
	}
	for _, source := range []string{"command.log", "server/jvm.out"} {
		if err := os.WriteFile(filepath.Join(base, filepath.FromSlash(source)), []byte(source+" --token=secret\n"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	s := &Server{Logs: logs.NewWatcher(t.TempDir()), BazelLogs: logs.NewBazelWatcher()}
	s.BazelLogs.Poll([]model.Process{{PID: 42, Type: "BAZEL_SERVER", BazelOutputBase: base}})
	list := httptest.NewRecorder()
	s.handleDaemonLogs(list, httptest.NewRequest("GET", "/v1/daemon-logs", nil))
	var payload struct {
		Logs []logs.DaemonLog `json:"logs"`
	}
	if err := json.Unmarshal(list.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Logs) != 2 {
		t.Fatalf("list: %s", list.Body)
	}
	for _, log := range payload.Logs {
		if log.Tool != "bazel" || log.PID != 42 || log.Source == "" {
			t.Fatalf("metadata: %+v", log)
		}
	}
	// A historical Gradle daemon may have reused this live Bazel PID.
	gradleHome := t.TempDir()
	versionDir := filepath.Join(gradleHome, "daemon", "9.0")
	if err := os.MkdirAll(versionDir, 0700); err != nil {
		t.Fatal(err)
	}
	for _, pid := range []string{"17", "42"} {
		if err := os.WriteFile(filepath.Join(versionDir, "daemon-"+pid+".out.log"), []byte("Gradle --token=secret\n"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	s.Logs = logs.NewWatcher(gradleHome)
	if _, err := s.Logs.Poll(nil); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		query, source string
		status        int
	}{
		{"", "command.log", 200},
		{"?source=command.log", "command.log", 200},
		{"?source=server%2Fjvm.out", "server/jvm.out", 200},
		{"?source=..%2Flock", "", 404},
	} {
		rec := httptest.NewRecorder()
		s.handleDaemonLogTail(rec, httptest.NewRequest("GET", "/v1/daemon-logs/42/tail"+tc.query, nil))
		if rec.Code != tc.status {
			t.Fatalf("%s: %d %s", tc.query, rec.Code, rec.Body)
		}
		if tc.status != 200 {
			continue
		}
		var tail logs.Tail
		if err := json.Unmarshal(rec.Body.Bytes(), &tail); err != nil {
			t.Fatal(err)
		}
		if tail.Source != tc.source || len(tail.Lines) != 1 || tail.Lines[0] != tc.source+" --token=***" || strings.Contains(rec.Body.String(), "secret") {
			t.Fatalf("tail: %s", rec.Body)
		}
	}
	gradle := httptest.NewRecorder()
	s.handleDaemonLogTail(gradle, httptest.NewRequest("GET", "/v1/daemon-logs/17/tail", nil))
	var tail logs.Tail
	if err := json.Unmarshal(gradle.Body.Bytes(), &tail); err != nil {
		t.Fatal(err)
	}
	if gradle.Code != 200 || tail.GradleVersion != "9.0" || len(tail.Lines) != 1 || tail.Lines[0] != "Gradle --token=***" {
		t.Fatalf("Gradle tail: %s", gradle.Body)
	}
}
