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
	logs     string
	tail     string
	history  string
}

func (f *fakeCore) RoundTrip(r *http.Request) (*http.Response, error) {
	f.mu.Lock()
	f.requests = append(f.requests, r)
	f.mu.Unlock()
	body := `{}`
	status := http.StatusOK
	switch {
	case r.URL.Path == "/v1/builds":
		body = f.builds
	case r.URL.Path == "/v1/daemon-logs":
		if f.logs != "" {
			body = f.logs
		} else {
			body = `{"logs":[]}`
		}
	case strings.HasPrefix(r.URL.Path, "/v1/daemon-logs/") && strings.HasSuffix(r.URL.Path, "/tail"):
		if f.tail != "" {
			body = f.tail
		} else {
			status = http.StatusNotFound
			body = `not found`
		}
	case r.URL.Path == "/v1/processes/history":
		if f.history != "" {
			body = f.history
		} else {
			body = `{}`
		}
	case r.URL.Path == "/v1/host" || r.URL.Path == "/v1/capabilities":
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
	m.width, m.height = 160, 60
	m.selectedPID = int64(p.PID)
	m.detailsOpen, m.detailProcess, m.detailLoading = true, &p, true
	return m
}

func TestFetchDetailsBazelServerLoadsScopedMetricsAndLogs(t *testing.T) {
	subtreeRSS := int64(2400)
	subtreeCPU := 45.5
	core := &fakeCore{
		logs: `{"logs":[
			{"pid":4242,"gradle_version":"","path":"/tmp/outbase/server/jvm.out","tool":"bazel","source":"server/jvm.out"},
			{"pid":4242,"gradle_version":"","path":"/tmp/outbase/command.log","tool":"bazel","source":"command.log"}
		]}`,
		tail: `{"pid":4242,"gradle_version":"","path":"/tmp/outbase/command.log","tool":"bazel","source":"command.log","lines":["INFO: Analyzed target //app:all","INFO: Build completed successfully, 12 total actions"]}`,
		history: `{"processes":[
			{"pid":4242,"type":"BAZEL_SERVER","start_time_ms":1699999000000,"sampled_at_ms":1699999990000,"rss_memory_mb":500,"heap_used_mb":200},
			{"pid":4242,"type":"BAZEL_SERVER","start_time_ms":1699999000000,"sampled_at_ms":1700000005000,"rss_memory_mb":900,"heap_used_mb":420},
			{"pid":4242,"type":"BAZEL_SERVER","start_time_ms":1699999000000,"sampled_at_ms":1700000020000,"rss_memory_mb":660,"heap_used_mb":320}
		]}`,
		builds: `{"count":2,"builds":[
			{
				"build_id":"b1","daemon_pid":4242,"start_time_ms":1700000000000,"end_time_ms":1700000012300,
				"final_status":"SUCCESS","inferred_source":"BEP","tool":"BAZEL","project_path":"/work/app",
				"duration_seconds":12.3,"peak_memory_mb":900,"agent":"Claude Code",
				"bazel_details":{
					"schema_version":1,"bazel_version":"8.1.1","source_format":"json","collection_state":"sealed","updated_at_ms":1700000012300,
					"metrics":{
						"timing":{"analysis_phase_ms":1400,"execution_phase_ms":9200},
						"actions":{"executed":42,"remote_cache_hits":30,"local_cache_hits":10,"local_cache_misses":2},
						"workers":[{"process_id":9001,"mnemonic":"Javac"},{"process_id":9002,"mnemonic":"Kotlin"}],
						"test_totals":{"total":5,"passed":4,"failed":1,"cached":2},
						"memory":{"used_heap_post_build_bytes":335544320}
					}
				},
				"window_summaries":[{"pid":4242,"process_type":"BAZEL_SERVER","delta_rss_mb":160,"delta_heap_used_mb":120,"delta_total_gc_count":4}]
			},
			{"build_id":"b2","daemon_pid":4242,"start_time_ms":1700000100000,"final_status":"FAILED","inferred_source":"PROFILE","tool":"BAZEL","project_path":"/work/app","duration_seconds":4}
		]}`,
	}
	p := model.Process{
		PID:                        4242,
		Type:                       "BAZEL_SERVER",
		Name:                       "bazel",
		StartTimeMs:                1_699_999_000_000,
		ProjectPath:                strPtr("/work/app"),
		BazelOutputBase:            "/tmp/outbase",
		BazelInstallBase:           "/tmp/installbase",
		ActiveWorkerCount:          3,
		ActiveSandboxedActionCount: 2,
		SubtreeRSSMemoryMB:         &subtreeRSS,
		SubtreeCPUPercent:          &subtreeCPU,
	}
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
	if loaded.buildsErr != nil || loaded.err != nil {
		t.Fatalf("unexpected load error: %+v", loaded)
	}
	if loaded.log == nil || loaded.log.Source != "command.log" {
		t.Fatalf("expected command.log metadata, got %+v", loaded.log)
	}
	if loaded.tail == nil || len(loaded.tail.Lines) != 2 {
		t.Fatalf("expected bazel log tail, got %+v", loaded.tail)
	}
	if len(loaded.builds.Builds) != 2 {
		t.Fatalf("builds=%+v", loaded.builds)
	}
	var sawBuilds, sawLogs, sawTail bool
	for _, r := range core.requests {
		switch r.URL.Path {
		case "/v1/builds":
			sawBuilds = true
			q := r.URL.Query()
			if q.Get("pid") != "4242" || q.Get("start_time_ms") != "1699999000000" || q.Get("tool") != "BAZEL" || q.Get("include_metrics") != "true" {
				t.Fatalf("builds query not scoped with metrics: %v", q)
			}
		case "/v1/daemon-logs":
			sawLogs = true
		case "/v1/daemon-logs/4242/tail":
			sawTail = true
		}
	}
	if !sawBuilds || !sawLogs || !sawTail {
		t.Fatalf("missing expected requests: builds=%v logs=%v tail=%v paths=%v", sawBuilds, sawLogs, sawTail, core.paths())
	}

	updated, _ := m.Update(loaded)
	modelAfter := updated.(Model)
	if modelAfter.detailPaneCount() != 4 {
		t.Fatalf("detailPaneCount=%d want 4", modelAfter.detailPaneCount())
	}
	body := modelAfter.View().Content
	for _, want := range []string{
		"BAZEL SERVER",
		"8.1.1",
		"/tmp/outbase",
		"/tmp/installbase",
		"Workers        3",
		"Sandboxed      2",
		"2.3 GB",
		"45.5%",
		"command.log",
		"RECENT BUILDS",
		"BAZEL LOG",
		"INFO: Analyzed target //app:all",
		"SUCCESS",
		"project=/work/app",
		"source=bazel/BEP",
		"peak_rss=900 MB",
		"phases=analysis 1.4s, exec 9.2s",
		"actions=exec 42, remote_hit 30",
		"workers=2",
		"tests=4 pass/1 fail/2 cached",
		"Heap 200 MB → 320 MB (+120 MB)",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("missing %q in:\n%s", want, body)
		}
	}
	if strings.Contains(body, "GRADLE LOG") || strings.Contains(body, "No Bazel builds captured yet.") {
		t.Fatalf("unexpected copy:\n%s", body)
	}
}

func TestFetchDetailsBazelServerEmptyKeepsHonestCopy(t *testing.T) {
	core := &fakeCore{builds: `{"count":0,"builds":[]}`}
	p := model.Process{PID: 4242, Type: "BAZEL_SERVER", StartTimeMs: 100, ProjectPath: strPtr("/work/app")}
	m := bazelDetailModel(core, p)
	loaded := m.fetchDetails(p)().(detailsLoadedMsg)
	if loaded.err != nil {
		t.Fatalf("404 on bazel log tail must not surface as fatal error: %v", loaded.err)
	}
	updated, _ := m.Update(loaded)
	body := updated.(Model).View().Content
	if !strings.Contains(body, "No Bazel builds captured yet.") || !strings.Contains(body, "No command.profile.gz or BEP.") {
		t.Fatalf("missing honest empty build copy:\n%s", body)
	}
	if !strings.Contains(body, "No Bazel log available yet (command.log / server/jvm.out).") {
		t.Fatalf("missing honest empty bazel log copy:\n%s", body)
	}
	if strings.Contains(body, "GRADLE LOG") {
		t.Fatalf("bazel server must not show GRADLE LOG:\n%s", body)
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
