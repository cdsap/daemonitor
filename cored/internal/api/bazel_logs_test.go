package api

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

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
