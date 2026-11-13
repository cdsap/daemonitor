package tui

import (
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

type fakeCore struct {
	mu       sync.Mutex
	requests []*http.Request
	builds   string
}

func (f *fakeCore) RoundTrip(r *http.Request) (*http.Response, error) {
	f.mu.Lock()
	f.requests = append(f.requests, r)
	f.mu.Unlock()
	body := `{}`
	status := http.StatusOK
	switch r.URL.Path {
	case "/v1/builds":
		body = f.builds
	case "/v1/history", "/v1/host", "/v1/capabilities":
		body = `{}`
	default:
		status = http.StatusNotFound
		body = `not found`
	}
	return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header), Request: r}, nil
}

func (f *fakeCore) paths() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]string, 0, len(f.requests))
	for _, r := range f.requests {
		out = append(out, r.URL.Path)
	}
	return out
}

func bazelDetailModel(core *fakeCore, p model.Process) Model {
	c := client.New("unused")
	c.HTTP = &http.Client{Transport: core}
	m := NewModel(Config{Now: fixedNow, NoColor: true, Client: c})
	m.width, m.height = 140, 40
	m.selectedPID = int64(p.PID)
	m.detailsOpen, m.detailProcess, m.detailLoading = true, &p, true
	return m
}

func TestFetchDetailsBazelServerLoadsScopedBEPBuilds(t *testing.T) {
	core := &fakeCore{builds: `{"count":2,"builds":[
{"build_id":"b1","daemon_pid":4242,"start_time_ms":1700000000000,"final_status":"SUCCESS","inferred_source":"BEP","tool":"BAZEL","project_path":"/work/app","duration_seconds":12.3},
{"build_id":"b2","daemon_pid":4242,"start_time_ms":1700000100000,"final_status":"FAILED","inferred_source":"BEP","tool":"BAZEL","project_path":"/work/app","duration_seconds":4}]}`}
	p := model.Process{PID: 4242, Type: "BAZEL_SERVER", Name: "bazel", StartTimeMs: 1_699_999_000_000, ProjectPath: strPtr("/work/app")}
	m := bazelDetailModel(core, p)

	cmd := m.fetchDetails(p)
	if cmd == nil {
		t.Fatal("fetchDetails returned nil cmd")
	}
	msg := cmd()
	loaded, ok := msg.(detailsLoadedMsg)
	if !ok {
		t.Fatalf("msg=%T", msg)
	}
	if loaded.buildsErr != nil || loaded.err != nil || loaded.tail != nil || loaded.log != nil {
		t.Fatalf("unexpected load result: %+v", loaded)
	}
	if len(loaded.builds.Builds) != 2 {
		t.Fatalf("builds=%+v", loaded.builds)
	}
	for _, path := range core.paths() {
		if strings.Contains(path, "log") {
			t.Fatalf("bazel server must not fetch gradle logs: %v", core.paths())
		}
	}
	for _, r := range core.requests {
		if r.URL.Path != "/v1/builds" {
			continue
		}
		q := r.URL.Query()
		if q.Get("pid") != "4242" || q.Get("start_time_ms") != "1699999000000" || q.Get("tool") != "BAZEL" {
			t.Fatalf("builds query not scoped to server: %v", q)
		}
	}

	updated, _ := m.Update(loaded)
	body := updated.(Model).View().Content
	for _, want := range []string{"RECENT BUILDS", "SUCCESS", "FAILED", "project=/work/app", "source=bazel/BEP"} {
		if !strings.Contains(body, want) {
			t.Fatalf("missing %q in: %s", want, body)
		}
	}
	if strings.Contains(body, "BEP not in reach") || strings.Contains(body, "GRADLE LOG") {
		t.Fatalf("unexpected copy: %s", body)
	}
}

func TestFetchDetailsBazelServerEmptyKeepsHonestCopy(t *testing.T) {
	core := &fakeCore{builds: `{"count":0,"builds":[]}`}
	p := model.Process{PID: 4242, Type: "BAZEL_SERVER", StartTimeMs: 100, ProjectPath: strPtr("/work/app")}
	m := bazelDetailModel(core, p)
	loaded := m.fetchDetails(p)().(detailsLoadedMsg)
	updated, _ := m.Update(loaded)
	body := updated.(Model).View().Content
	if !strings.Contains(body, "No Bazel build events found (BEP not in reach)") {
		t.Fatalf("missing honest empty copy: %s", body)
	}
}

func TestFetchDetailsGradleDaemonStillLoadsDaemonLog(t *testing.T) {
	core := &fakeCore{builds: `{"count":0,"builds":[]}`}
	p := model.Process{PID: 77, Type: "GRADLE_DAEMON", StartTimeMs: 100}
	m := bazelDetailModel(core, p)
	m.fetchDetails(p)()
	var sawLog bool
	for _, path := range core.paths() {
		if strings.Contains(path, "log") {
			sawLog = true
		}
	}
	if !sawLog {
		t.Fatalf("gradle daemon should fetch logs: %v", core.paths())
	}
}

func TestFetchDetailsNonBuildProcessSkipsBuilds(t *testing.T) {
	core := &fakeCore{builds: `{"count":1,"builds":[{"build_id":"x"}]}`}
	p := model.Process{PID: 5, Type: "KOTLIN_DAEMON", StartTimeMs: 100}
	m := bazelDetailModel(core, p)
	loaded := m.fetchDetails(p)().(detailsLoadedMsg)
	if len(loaded.builds.Builds) != 0 {
		t.Fatalf("builds=%+v", loaded.builds)
	}
}
