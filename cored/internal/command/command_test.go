package command

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestParseArgsDefaults(t *testing.T) {
	opts, err := ParseArgs(nil)
	if err != nil {
		t.Fatal(err)
	}
	if opts.PollInterval != 2*time.Second {
		t.Fatalf("interval=%v", opts.PollInterval)
	}
	if opts.Socket == "" {
		t.Fatal("expected default socket")
	}
}

func TestParseArgsFlags(t *testing.T) {
	opts, err := ParseArgs([]string{"--json", "--no-autostart", "--no-color", "--details", "ps"})
	if err != nil {
		t.Fatal(err)
	}
	if !opts.JSON || !opts.NoAutostart || !opts.NoColor || !opts.Details {
		t.Fatalf("flags not set: %+v", opts)
	}
	if len(opts.Args) != 1 || opts.Args[0] != "ps" {
		t.Fatalf("args=%v", opts.Args)
	}
}

func TestParseArgsFlagsAfterCommand(t *testing.T) {
	opts, err := ParseArgs([]string{"ps", "--json", "--socket", "/tmp/x.sock", "--no-autostart"})
	if err != nil {
		t.Fatal(err)
	}
	if !opts.JSON || !opts.NoAutostart || opts.Socket != "/tmp/x.sock" {
		t.Fatalf("flags after command: %+v", opts)
	}
	if len(opts.Args) != 1 || opts.Args[0] != "ps" {
		t.Fatalf("args=%v", opts.Args)
	}
}

func TestUsageMentionsCommands(t *testing.T) {
	u := Usage()
	for _, needle := range []string{"top", "ps", "health", "--json", "--no-autostart"} {
		if !strings.Contains(u, needle) {
			t.Fatalf("usage missing %q", needle)
		}
	}
	if strings.Contains(u, "\t--include-logs") || strings.Contains(u, "\t--include-project-path") {
		t.Fatalf("usage options should use spaces, not tabs: %q", u)
	}
}

func TestParseArgsQueryAndWatchOptions(t *testing.T) {
	opts, err := ParseArgs([]string{"builds", "--limit", "25", "--pid", "42", "--project", "demo", "--status", "FAILED", "--format", "jsonl"})
	if err != nil {
		t.Fatal(err)
	}
	if opts.Limit != 25 || opts.PID != 42 || opts.Project != "demo" || opts.Status != "FAILED" || opts.Output != "jsonl" {
		t.Fatalf("unexpected query options: %+v", opts)
	}

	opts, err = ParseArgs([]string{"ps", "--watch", "--until", "1m", "--poll-interval", "5s"})
	if err != nil {
		t.Fatal(err)
	}
	if !opts.Watch || opts.Until != time.Minute || opts.PollInterval != 5*time.Second {
		t.Fatalf("unexpected watch options: %+v", opts)
	}
	opts, err = ParseArgs([]string{"ps", "--watch", "--fail-on-rss", "8192"})
	if err != nil || opts.FailOnRSS != 8192 {
		t.Fatalf("unexpected RSS threshold: %+v, err=%v", opts, err)
	}
	opts, err = ParseArgs([]string{"builds", "--include-logs"})
	if err != nil || !opts.IncludeLogs {
		t.Fatalf("unexpected log option: %+v, err=%v", opts, err)
	}
	opts, err = ParseArgs([]string{"builds", "--include-project-path"})
	if err != nil || !opts.IncludeProjectPath {
		t.Fatalf("unexpected project path option: %+v, err=%v", opts, err)
	}
}

func TestParseArgsTelemetryCommandsAndBounds(t *testing.T) {
	opts, err := ParseArgs([]string{"metrics", "list", "--pid", "8421", "--format", "jsonl"})
	if err != nil || opts.Output != "jsonl" || opts.PID != 8421 || len(opts.Args) != 2 || opts.Args[1] != "list" {
		t.Fatalf("telemetry args=%+v err=%v", opts, err)
	}
	opts, err = ParseArgs([]string{"export", "--since", "1h", "--output", "telemetry.jsonl"})
	if err != nil || opts.OutputPath != "telemetry.jsonl" || opts.Since != time.Hour {
		t.Fatalf("export args=%+v err=%v", opts, err)
	}
	if boundedLimit(0) != 500 || boundedLimit(5001) != 500 || boundedLimit(42) != 42 {
		t.Fatal("unexpected bounded export limits")
	}
}

func TestMetricJSONLHasStableSchemaFields(t *testing.T) {
	project := "/private/project"
	rows := metricRecords([]model.Process{{PID: 8421, StartTimeMs: 123, SampledAtMs: 456, ProjectPath: &project, RSSMemoryMB: 7}})
	var out bytes.Buffer
	if err := writeMetrics(&out, rows, "jsonl"); err != nil {
		t.Fatal(err)
	}
	var record map[string]any
	firstLine := strings.SplitN(strings.TrimSpace(out.String()), "\n", 2)[0]
	if err := json.Unmarshal([]byte(firstLine), &record); err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{"schema_version", "metric_name", "unit", "value", "source", "collection_timestamp_ms", "process_identity"} {
		if _, ok := record[field]; !ok {
			t.Fatalf("missing %q in %s", field, out.String())
		}
	}
}

func TestJVMMetricExportSeparatesObservationAndMeasurement(t *testing.T) {
	for _, times := range []map[string]int64{nil, {"heap": 1000, "gc": 1200}} {
		used, gc, legacyHeapTime := int64(42), int64(7), int64(3000)
		rows := metricRecords([]model.Process{{PID: 7, SampledAtMs: 5000,
			HeapUsedMB: &used, YoungGCCount: &gc, HeapSampledAtMs: &legacyHeapTime, JVMMeasurementTimes: times}})
		var out bytes.Buffer
		if err := writeMetrics(&out, rows, "jsonl"); err != nil {
			t.Fatal(err)
		}
		for _, line := range strings.Split(strings.TrimSpace(out.String()), "\n") {
			var row model.MetricRecord
			if err := json.Unmarshal([]byte(line), &row); err != nil {
				t.Fatal(err)
			}
			if row.CollectionTimeMs != 5000 {
				t.Fatalf("lost observation time: %+v", row)
			}
			if !strings.HasPrefix(row.MetricName, "process.jvm.") {
				continue
			}
			family := "heap"
			switch {
			case strings.Contains(row.MetricName, "_gc_"):
				family = "gc"
			case strings.HasPrefix(row.MetricName, "process.jvm.jit."):
				family = "jit"
			case strings.HasPrefix(row.MetricName, "process.jvm.classes."):
				family = "classes"
			}
			want := times[family]
			if want == 0 {
				if row.MeasurementTimeMs != nil {
					t.Fatalf("invented legacy measurement: %+v", row)
				}
			} else if row.MeasurementTimeMs == nil || *row.MeasurementTimeMs != want {
				t.Fatalf("lost %s measurement time: %+v", family, row)
			}
		}
	}
}

func TestParseArgsRejectsInvalidOutputAndNegativeValues(t *testing.T) {
	for _, argv := range [][]string{
		{"ps", "--format", "xml"},
		{"history", "--limit", "-1"},
		{"ps", "--until", "-1s"},
	} {
		if _, err := ParseArgs(argv); err == nil {
			t.Fatalf("expected ParseArgs(%v) to fail", argv)
		}
	}
}

func TestFiltersApplyToProcesses(t *testing.T) {
	project := "/work/daemonitor"
	processes := filterProcesses([]model.Process{
		{PID: 1, ProjectPath: &project},
		{PID: 2, ProjectPath: strPtr("/work/other")},
	}, Options{Project: "DAEMONITOR"})
	if len(processes) != 1 || processes[0].PID != 1 {
		t.Fatalf("process filter=%+v", processes)
	}

}

func strPtr(s string) *string { return &s }

func TestFilterLogsAppliesLimitAfterPID(t *testing.T) {
	items := []logs.DaemonLog{
		{PID: 1, Path: "daemon-1.out.log"},
		{PID: 2, Tool: "bazel", Source: "command.log"},
		{PID: 2, Tool: "bazel", Source: "server/jvm.out"},
		{PID: 3, Path: "daemon-3.out.log"},
	}
	for _, tc := range []struct {
		name string
		opts Options
		want []logs.DaemonLog
	}{
		{name: "unlimited", want: items},
		{name: "limited", opts: Options{Limit: 2}, want: items[:2]},
		{name: "exact limit", opts: Options{Limit: 4}, want: items},
		{name: "larger limit", opts: Options{Limit: 5}, want: items},
		{name: "PID only", opts: Options{PID: 2}, want: items[1:3]},
		{name: "PID then limit", opts: Options{PID: 2, Limit: 1}, want: items[1:2]},
		{name: "no matching PID", opts: Options{PID: 9, Limit: 1}, want: items[:0]},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := filterLogs(append([]logs.DaemonLog(nil), items...), tc.opts)
			if !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("filterLogs(%+v)=%+v, want %+v", tc.opts, got, tc.want)
			}
		})
	}
	if got := filterLogs(nil, Options{Limit: 5}); len(got) != 0 {
		t.Fatalf("empty logs=%+v", got)
	}
}

func TestJVMCounterMetricExports(t *testing.T) {
	count, zero, seconds := int64(27), int64(0), 1.23456789
	p := model.Process{PID: 42, StartTimeMs: 100, SampledAtMs: 200,
		JIT:                &model.JITMetrics{CompiledCount: &count, FailedCount: &zero, CompilationTimeSeconds: &seconds},
		Classes:            &model.ClassMetrics{LoadedClasses: &count, UnloadedClasses: &zero, ClassTimeSeconds: &seconds},
		YoungGCTimeSeconds: &seconds, ConcurrentGCTimeSeconds: &seconds}
	path := filepath.Join(t.TempDir(), "export.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: 200, Processes: []model.Process{p}}); err != nil {
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
	history, err := s.History(0, 10)
	if err != nil {
		t.Fatal(err)
	}
	records := metricRecords(history)
	var jvm []model.MetricRecord
	for _, r := range records {
		if strings.HasPrefix(r.MetricName, "process.jvm.") {
			jvm = append(jvm, r)
		}
	}
	if len(jvm) != 15 {
		t.Fatalf("got %d JVM export fields, want 15", len(jvm))
	}
	expected := map[string]any{
		"process.jvm.young_gc_time_seconds":        &seconds,
		"process.jvm.full_gc_time_seconds":         (*float64)(nil),
		"process.jvm.concurrent_gc_time_seconds":   &seconds,
		"process.jvm.total_gc_time_seconds":        (*float64)(nil),
		"process.jvm.jit.compiled_count":           &count,
		"process.jvm.jit.failed_count":             &zero,
		"process.jvm.jit.invalid_count":            (*int64)(nil),
		"process.jvm.jit.compilation_time_seconds": &seconds,
		"process.jvm.classes.loaded":               &count,
		"process.jvm.classes.unloaded_total":       &zero,
		"process.jvm.classes.loaded_total":         (*int64)(nil),
		"process.jvm.classes.time_seconds":         &seconds,
	}
	for _, r := range jvm {
		if value, ok := expected[r.MetricName]; ok {
			if !reflect.DeepEqual(value, r.Value) {
				t.Fatalf("%s=%v want %v", r.MetricName, r.Value, value)
			}
			delete(expected, r.MetricName)
		}
	}
	if len(expected) != 0 {
		t.Fatalf("missing metrics: %v", expected)
	}
	for _, output := range []string{"json", "jsonl", "csv"} {
		var out bytes.Buffer
		if err := writeMetrics(&out, jvm, output); err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out.String(), "process.jvm.jit.compiled_count") || !strings.Contains(out.String(), "1.23456789") {
			t.Fatalf("%s lost JVM values: %s", output, out.String())
		}
	}
	for _, r := range jvm {
		if r.ProcessIdentity != "42:100" || r.CollectionTimeMs != 200 {
			t.Fatalf("identity/time=%+v", r)
		}
		if strings.HasSuffix(r.MetricName, "seconds") && r.Unit != "seconds" {
			t.Fatalf("unit=%+v", r)
		}
	}
}

func TestFilterLogsAppliesPIDAndLimit(t *testing.T) {
	items := []logs.DaemonLog{
		{PID: 101, GradleVersion: "9.6.1", Path: "daemon-101.out.log"},
		{PID: 102, GradleVersion: "9.6.1", Path: "daemon-102.out.log"},
		{PID: 103, Tool: "bazel", Source: "server/jvm.out", Path: "jvm.out"},
		{PID: 103, Tool: "bazel", Source: "command.log", Path: "command.log"},
	}

	limited := filterLogs(items, Options{Limit: 2})
	if len(limited) != 2 || limited[0].PID != 101 || limited[1].PID != 102 {
		t.Fatalf("limit=2 got %+v", limited)
	}
	byPID := filterLogs(items, Options{PID: 103})
	if len(byPID) != 2 || byPID[0].Source != "server/jvm.out" || byPID[1].Source != "command.log" {
		t.Fatalf("pid=103 got %+v", byPID)
	}
	byPIDAndLimit := filterLogs(items, Options{PID: 103, Limit: 1})
	if len(byPIDAndLimit) != 1 || byPIDAndLimit[0].Source != "server/jvm.out" {
		t.Fatalf("pid=103 limit=1 got %+v", byPIDAndLimit)
	}
	// Caller slice must remain unmodified.
	if len(items) != 4 || items[0].PID != 101 {
		t.Fatalf("input slice was mutated: %+v", items)
	}
}
