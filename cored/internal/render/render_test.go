package render

import (
	"bytes"
	"encoding/csv"
	"encoding/json"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/platform"
)

func TestWriteProcessesPlainKeepsColumnsAlignedForLongTypes(t *testing.T) {
	var buf bytes.Buffer
	WriteProcessesPlain(&buf, model.Snapshot{
		SampledAtMs: 100,
		Processes: []model.Process{
			{PID: 4242, Type: "GRADLE_DAEMON", RSSMemoryMB: 613},
			{PID: 4243, Type: "UNKNOWN_GRADLE_WORKER", RSSMemoryMB: 1045},
		},
	})
	lines := strings.Split(strings.TrimSpace(buf.String()), "\n")
	header, short, long := lines[1], lines[2], lines[3]
	pidEnd := strings.Index(header, "PID") + len("PID")
	for _, row := range []string{short, long} {
		if got := strings.Index(row, "424") + len("4242"); got != pidEnd {
			t.Fatalf("PID ends at %d, header at %d:\n%s\n%s", got, pidEnd, header, row)
		}
	}
	if !strings.Contains(short, "613 MB") || !strings.Contains(long, "1.0 GB") {
		t.Fatalf("RSS should use the same units as XMX:\n%s\n%s", short, long)
	}
}

func TestWriteProcessesPlainNoANSI(t *testing.T) {
	cpu := 12.5
	xmx := int64(2048)
	used := int64(4500)
	gc := "G1"
	var buf bytes.Buffer
	WriteProcessesPlain(&buf, model.Snapshot{
		SampledAtMs: 100,
		Processes: []model.Process{
			{PID: 1, Type: "GRADLE_DAEMON", RSSMemoryMB: 4500, CPUPercent: &cpu, MaxHeapMB: &xmx, HeapUsedMB: &used, GC: &gc, Automated: true, ProjectPath: strPtr("/a/b")},
		},
	})
	out := buf.String()
	if strings.Contains(out, "\x1b") {
		t.Fatalf("ANSI found in plain output: %q", out)
	}
	if !strings.Contains(out, "Gradle daemon") {
		t.Fatalf("missing type: %s", out)
	}
	if !strings.Contains(out, "G1") {
		t.Fatalf("missing GC type: %s", out)
	}
	if !strings.Contains(out, "GC") {
		t.Fatalf("missing GC column: %s", out)
	}
	if !strings.Contains(out, "HIGH MEM") || !strings.Contains(out, "AUTOMATED") {
		t.Fatalf("missing process signals: %s", out)
	}
}

func TestTypeDisplayForGradleWorkerKinds(t *testing.T) {
	for _, tc := range []struct {
		kind string
		want string
	}{
		{kind: "BAZEL_SERVER", want: "Bazel server"},
		{kind: "BAZEL_TEST", want: "Bazel test"},
		{kind: "BAZEL_WORKER", want: "Bazel worker"},
		{kind: "TEST_WORKER", want: "Gradle test worker"},
		{kind: "GRADLE_WORKER", want: "Gradle worker"},
		{kind: "UNKNOWN_GRADLE_WORKER", want: "Unknown Gradle worker"},
		{kind: "KOTLIN_TOOLCHAIN", want: "Kotlin Toolchain"},
		{kind: "XCODE_BUILD", want: "Xcode build"},
		{kind: "XCODE_BUILD_SERVICE", want: "Xcode build service"},
		{kind: "XCODE_COMPILER", want: "Xcode compiler"},
		{kind: "XCODE_TOOL", want: "Xcode tool"},
	} {
		t.Run(tc.kind, func(t *testing.T) {
			if got := TypeDisplay(tc.kind); got != tc.want {
				t.Fatalf("TypeDisplay(%q)=%q want %q", tc.kind, got, tc.want)
			}
		})
	}
}

func TestWriteProcessesDetailedPlainIncludesExtendedMetrics(t *testing.T) {
	threads, readBytes, writeBytes := int64(12), int64(1024), int64(2048)
	metaspace, youngTime, oldTime := int64(64), int64(7), int64(9)
	javaVersion, javaVendor := "21", "Temurin"
	var buf bytes.Buffer
	WriteProcessesDetailedPlain(&buf, model.Snapshot{SampledAtMs: 100, Processes: []model.Process{{
		PID: 7, Type: "GRADLE_DAEMON", RSSMemoryMB: 256, ThreadCount: &threads,
		ReadBytes: &readBytes, WriteBytes: &writeBytes, MetaspaceUsedMB: &metaspace,
		YoungGCTimeMs: &youngTime, OldGCTimeMs: &oldTime, JavaVersion: &javaVersion, JavaVendor: &javaVendor,
	}}})
	out := buf.String()
	for _, want := range []string{"pid=7", "threads=12", "read_bytes=1024", "write_bytes=2048", "metaspace_used_mb=64", "young_gc_time_ms=7", "old_gc_time_ms=9", "java_version=21", "java_runtime_version=n/a", "java_vendor=Temurin", "java_vm_name=n/a", "java_vm_version=n/a", "os_name=n/a", "os_arch=n/a"} {
		if !strings.Contains(out, want) {
			t.Fatalf("detailed output missing %q: %s", want, out)
		}
	}
	if !strings.Contains(out, "open_file_descriptors=n/a") {
		t.Fatalf("unavailable metric was not rendered cleanly: %s", out)
	}
}

func TestWriteJSONStableSchema(t *testing.T) {
	var buf bytes.Buffer
	snap := model.Snapshot{SampledAtMs: 42, Processes: []model.Process{}}
	if err := WriteJSON(&buf, snap); err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(buf.Bytes(), &decoded); err != nil {
		t.Fatal(err)
	}
	if _, ok := decoded["sampled_at_ms"]; !ok {
		t.Fatalf("missing sampled_at_ms: %v", decoded)
	}
	if _, ok := decoded["processes"]; !ok {
		t.Fatalf("missing processes: %v", decoded)
	}
}

func TestStructuredProcessExportsIncludeExtendedMetrics(t *testing.T) {
	threads := int64(12)
	young, total := 1.234, 7.035
	measured := int64(1000)
	snap := model.Snapshot{SampledAtMs: 5000, Processes: []model.Process{{PID: 7, SampledAtMs: 5000, ThreadCount: &threads, YoungGCTimeSeconds: &young, TotalGCTimeSeconds: &total, HeapSampledAtMs: &measured, JVMMeasurementTimes: map[string]int64{"heap": 1000, "gc": 1100}}}}
	for name, write := range map[string]func(*bytes.Buffer) error{
		"json":  func(buf *bytes.Buffer) error { return WriteJSON(buf, snap) },
		"jsonl": func(buf *bytes.Buffer) error { return WriteJSONLine(buf, snap) },
	} {
		var buf bytes.Buffer
		if err := write(&buf); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		var decoded struct {
			Processes []map[string]any `json:"processes"`
		}
		if err := json.Unmarshal(buf.Bytes(), &decoded); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if got := decoded.Processes[0]["thread_count"]; got != float64(12) {
			t.Fatalf("%s thread_count=%v", name, got)
		}
		if got := decoded.Processes[0]["young_gc_time_seconds"]; got != young {
			t.Fatalf("%s young_gc_time_seconds=%v", name, got)
		}
		if got := decoded.Processes[0]["total_gc_time_seconds"]; got != total {
			t.Fatalf("%s total_gc_time_seconds=%v", name, got)
		}
		if got := decoded.Processes[0]["heap_sampled_at_ms"]; got != float64(1000) {
			t.Fatalf("%s heap_sampled_at_ms=%v", name, got)
		}
		if got := decoded.Processes[0]["jvm_sampled_at_ms"].(map[string]any)["gc"]; got != float64(1100) {
			t.Fatalf("%s GC measurement time=%v", name, got)
		}
	}
	for name, write := range map[string]func(*bytes.Buffer) error{
		"process CSV": func(buf *bytes.Buffer) error { return WriteProcessesCSV(buf, snap) },
		"history CSV": func(buf *bytes.Buffer) error { return WriteHistoryCSV(buf, model.History{Processes: snap.Processes}) },
	} {
		var buf bytes.Buffer
		if err := write(&buf); err != nil {
			t.Fatal(err)
		}
		rows, err := csv.NewReader(&buf).ReadAll()
		if err != nil {
			t.Fatal(err)
		}
		columns := map[string]bool{}
		for i, col := range rows[0] {
			columns[col] = true
			switch col {
			case "sampled_at_ms":
				if rows[1][i] != "5000" {
					t.Fatalf("%s observation=%s", name, rows[1][i])
				}
			case "heap_sampled_at_ms":
				if rows[1][i] != "1000" {
					t.Fatalf("%s heap measurement=%s", name, rows[1][i])
				}
			case "jvm_sampled_at_ms":
				if rows[1][i] != `{"gc":1100,"heap":1000}` {
					t.Fatalf("%s family measurements=%s", name, rows[1][i])
				}
			}
		}
		for _, col := range []string{"sampled_at_ms", "heap_sampled_at_ms", "jvm_sampled_at_ms"} {
			if !columns[col] {
				t.Fatalf("%s missing %s", name, col)
			}
		}
	}
}

func TestJSONUnknownJVMMeasurementTimesStayNull(t *testing.T) {
	legacyTime, used := int64(3000), int64(42)
	for _, p := range []model.Process{{}, {HeapAvailable: true, HeapUsedMB: &used, HeapSampledAtMs: &legacyTime}} {
		var buf bytes.Buffer
		if err := WriteJSON(&buf, p); err != nil {
			t.Fatal(err)
		}
		var row map[string]any
		if err := json.Unmarshal(buf.Bytes(), &row); err != nil {
			t.Fatal(err)
		}
		if times, ok := row["jvm_sampled_at_ms"]; !ok || times != nil {
			t.Fatalf("unknown measurement times must be explicit null: %s", buf.String())
		}
	}
}

func TestEmptyProcessesMessage(t *testing.T) {
	var buf bytes.Buffer
	WriteProcessesPlain(&buf, model.Snapshot{SampledAtMs: 1, Processes: nil})
	if !strings.Contains(buf.String(), "No Gradle-related processes") {
		t.Fatalf("unexpected: %s", buf.String())
	}
}

func TestHeapTextFormatsLiveAndUnavailableValues(t *testing.T) {
	used := int64(1536)
	if got := HeapText(&used); got != "1.5 GB" {
		t.Fatalf("live heap=%q want 1.5 GB", got)
	}
	if got := HeapText(nil); got != "n/a" {
		t.Fatalf("unavailable heap=%q want n/a", got)
	}
}

func TestGCTimeTextFormatsSecondsAndUnavailableValues(t *testing.T) {
	value := 1.23456
	if got := GCTimeText(&value); got != "1.235 s" {
		t.Fatalf("GC time=%q want 1.235 s", got)
	}
	if got := GCTimeText(nil); got != "—" {
		t.Fatalf("unavailable GC time=%q want —", got)
	}
}

func TestProcessSignalsUseDesktopThresholds(t *testing.T) {
	if got := ProcessSignals(model.Process{RSSMemoryMB: MemoryWarnMB - 1}); len(got) != 0 {
		t.Fatalf("below warning threshold=%v", got)
	}
	if got := ProcessSignals(model.Process{RSSMemoryMB: MemoryWarnMB}); strings.Join(got, " ") != "HIGH MEM" {
		t.Fatalf("warning signals=%v", got)
	}
	if got := ProcessSignals(model.Process{RSSMemoryMB: MemoryCritMB, Automated: true}); strings.Join(got, " ") != "CRIT MEM AUTOMATED" {
		t.Fatalf("critical signals=%v", got)
	}
}

func TestWriteBuildsPlainIncludesOriginAndAgent(t *testing.T) {
	var buf bytes.Buffer
	WriteBuildsPlain(&buf, client.BuildsPayload{
		Count:  1,
		Builds: []client.BuildRecord{{BuildID: "build-1", DaemonPID: 9, InferredSource: "IDE", Agent: "Claude Code", AgentProvider: "Anthropic", FinalStatus: "SUCCESS"}},
	})
	out := buf.String()
	if !strings.Contains(out, "source=IDE") || !strings.Contains(out, "agent=Claude Code") || !strings.Contains(out, "provider=Anthropic") {
		t.Fatalf("missing build origin or agent: %s", out)
	}
}

func TestWriteBuildsJSONKeepsBuildMetadataForScopedResult(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteJSON(&buf, client.BuildsPayload{
		Count:  1,
		Builds: []client.BuildRecord{{BuildID: "build-42", DaemonPID: 42, FinalStatus: "SUCCESS", InferredSource: "IDE", ProjectPath: "/work/demo"}},
	}); err != nil {
		t.Fatal(err)
	}
	var payload client.BuildsPayload
	if err := json.Unmarshal(buf.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Count != 1 || len(payload.Builds) != 1 || payload.Builds[0].DaemonPID != 42 || payload.Builds[0].ProjectPath != "/work/demo" {
		t.Fatalf("scoped build payload=%+v", payload)
	}
}

func TestRowOrientedExports(t *testing.T) {
	threads, readBytes, writeBytes := int64(4), int64(10), int64(20)
	var buf bytes.Buffer
	if err := WriteProcessesCSV(&buf, model.Snapshot{SampledAtMs: 7, Processes: []model.Process{{PID: 9, Type: "GRADLE_DAEMON", RSSMemoryMB: 512, ThreadCount: &threads, ReadBytes: &readBytes, WriteBytes: &writeBytes, ProjectPath: strPtr("/tmp/demo")}}}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "sampled_at_ms,pid,parent_pid,type") || !strings.Contains(buf.String(), "7,9,0,GRADLE_DAEMON") {
		t.Fatalf("unexpected process CSV: %s", buf.String())
	}
	if !strings.Contains(buf.String(), "thread_count") || !strings.Contains(buf.String(), "read_bytes") || !strings.Contains(buf.String(), "write_bytes") || !strings.Contains(buf.String(), ",4,10,20,") {
		t.Fatalf("extended process metrics missing from CSV: %s", buf.String())
	}
	for _, column := range []string{"java_runtime_version", "java_vm_name", "java_vm_version", "os_name", "os_arch"} {
		if !strings.Contains(buf.String(), column) {
			t.Fatalf("JVM metadata column missing from CSV: %s", buf.String())
		}
	}

	buf.Reset()
	if err := WriteJSONLine(&buf, map[string]string{"kind": "snapshot"}); err != nil {
		t.Fatal(err)
	}
	if strings.Count(strings.TrimSpace(buf.String()), "\n") != 0 || !strings.HasSuffix(buf.String(), "\n") {
		t.Fatalf("expected one JSON line: %q", buf.String())
	}
}

func TestWriteHostPlainShowsCompactAvailableMetrics(t *testing.T) {
	cpus := int64(8)
	cpu := 12.5
	used := int64(2 * 1024 * 1024 * 1024)
	avail := int64(6 * 1024 * 1024 * 1024)
	total := int64(8 * 1024 * 1024 * 1024)
	host := platform.HostMetrics{
		SampledAtMs:          42,
		Scope:                platform.ScopeHost,
		LogicalProcessors:    platform.IntMetric{Availability: platform.Available, Value: &cpus},
		CPUUsagePercent:      platform.RealMetric{Availability: platform.Available, Value: &cpu},
		MemoryUsedBytes:      platform.IntMetric{Availability: platform.Available, Value: &used},
		MemoryAvailableBytes: platform.IntMetric{Availability: platform.Available, Value: &avail},
		MemoryTotalBytes:     platform.IntMetric{Availability: platform.Available, Value: &total},
		CommitUsedBytes:      platform.IntMetric{Availability: platform.Unsupported},
		Load1:                platform.RealMetric{Availability: platform.Unsupported},
	}
	var buf strings.Builder
	WriteHostPlain(&buf, host)
	out := buf.String()
	for _, want := range []string{
		"sampled_at_ms=42",
		"scope=host",
		"logical_processors=8",
		"cpu_usage_percent=12.50%",
		"memory_used=2 GB",
		"memory_available=6 GB",
		"memory_total=8 GB",
		"commit_used=n/a",
		"load1=n/a",
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("host plain missing %q in %q", want, out)
		}
	}
}

func TestExportSanitizationDefaultsToBasenames(t *testing.T) {
	project := "/Users/alice/work/demo"
	snap := model.Snapshot{Processes: []model.Process{{WorkingDirectory: strPtr(project), ProjectPath: strPtr(project)}}}
	SanitizeSnapshotForExport(&snap, false)
	if *snap.Processes[0].WorkingDirectory != "demo" || *snap.Processes[0].ProjectPath != "demo" {
		t.Fatalf("snapshot paths=%+v", snap.Processes[0])
	}
	optIn := model.Snapshot{Processes: []model.Process{{ProjectPath: strPtr(project)}}}
	SanitizeSnapshotForExport(&optIn, true)
	if *optIn.Processes[0].ProjectPath != project {
		t.Fatalf("opt-in path=%q", *optIn.Processes[0].ProjectPath)
	}
}

func strPtr(s string) *string { return &s }

func TestWriteLogsPlainDistinguishesBazelSourcesFromGradle(t *testing.T) {
	list := []logs.DaemonLog{
		{PID: 1, GradleVersion: "9.0", Path: "/logs/daemon-1.out.log"},
		{PID: 2, Tool: "bazel", Source: "server/jvm.out", Path: "/output/server/jvm.out"},
		{PID: 2, Tool: "bazel", Source: "command.log", Path: "/output/command.log"},
	}
	// The CLI strips paths by default; the source must still distinguish Bazel logs.
	SanitizeLogsForExport(&list, false)
	var buf bytes.Buffer
	WriteLogsPlain(&buf, list)
	lines := strings.Split(strings.TrimSpace(buf.String()), "\n")
	if len(lines) != 4 || lines[0] != "daemon_logs=3" {
		t.Fatalf("unexpected log count: %s", buf.String())
	}
	want := [][]string{
		{"pid=1", "gradle=9.0", "daemon-1.out.log"},
		{"pid=2", "bazel=server/jvm.out", "jvm.out"},
		{"pid=2", "bazel=command.log", "command.log"},
	}
	for i, fields := range want {
		if got := strings.Fields(lines[i+1]); strings.Join(got, " ") != strings.Join(fields, " ") {
			t.Fatalf("log row=%q, want fields %v", lines[i+1], fields)
		}
	}
}

func TestWriteLogsPlainAndTailDistinguishesGradleAndBazel(t *testing.T) {
	var buf bytes.Buffer
	WriteLogsPlain(&buf, []logs.DaemonLog{
		{PID: 50527, GradleVersion: "9.6.1", Path: "daemon-50527.out.log"},
		{PID: 50577, Tool: "bazel", Source: "server/jvm.out", Path: "jvm.out"},
		{PID: 50578, Tool: "bazel", Path: "command.log"},
	})
	out := buf.String()
	if !strings.Contains(out, "pid=50527   gradle=9.6.1    daemon-50527.out.log") {
		t.Fatalf("missing Gradle log row: %q", out)
	}
	if !strings.Contains(out, "pid=50577   bazel=server/jvm.out jvm.out") {
		t.Fatalf("missing Bazel log row with source: %q", out)
	}
	if !strings.Contains(out, "pid=50578   bazel=-") {
		t.Fatalf("missing Bazel fallback source: %q", out)
	}
	if strings.Contains(out, "pid=50577   gradle=") {
		t.Fatalf("Bazel log rendered blank gradle= label: %q", out)
	}

	buf.Reset()
	WriteLogTailPlain(&buf, logs.Tail{
		PID:    50577,
		Tool:   "bazel",
		Source: "server/jvm.out",
		Lines:  []string{"OpenJDK warning"},
	})
	tailOut := buf.String()
	if !strings.Contains(tailOut, "pid=50577 bazel=server/jvm.out lines=1 events=0") {
		t.Fatalf("unexpected Bazel tail header: %q", tailOut)
	}
}

func TestWriteHostPlainUnavailableLogicalProcessors(t *testing.T) {
	cpus := int64(8)
	for _, metric := range []platform.IntMetric{
		{},
		{Availability: platform.Available},
		{Value: &cpus},
		{Availability: platform.Unsupported, Value: &cpus},
	} {
		var buf strings.Builder
		WriteHostPlain(&buf, platform.HostMetrics{LogicalProcessors: metric})
		if !strings.Contains(buf.String(), "logical_processors=n/a\n") {
			t.Fatalf("metric %+v rendered as available: %s", metric, buf.String())
		}
	}
}

func TestWriteProcessesPlainNativeXcodeOmitsJVMColumns(t *testing.T) {
	fp := int64(256 * 1024 * 1024)
	dr := int64(12 * 1024 * 1024)
	dw := int64(4 * 1024 * 1024)
	var buf bytes.Buffer
	WriteProcessesPlain(&buf, model.Snapshot{
		SampledAtMs: 1000,
		Processes: []model.Process{
			{PID: 500, Type: "XCODE_COMPILER", RSSMemoryMB: 300, PhysicalFootprintBytes: &fp, DiskReadBytes: &dr, DiskWriteBytes: &dw},
		},
	})
	out := buf.String()
	for _, want := range []string{"FOOTPR", "DISK_R", "DISK_W", "Xcode compiler", "256 MB", "12 MB", "4 MB"} {
		if !strings.Contains(out, want) {
			t.Fatalf("native plain output missing %q:\n%s", want, out)
		}
	}
	for _, forbidden := range []string{" GC ", " XMX "} {
		if strings.Contains(out, forbidden) {
			t.Fatalf("native plain output must omit JVM column %q:\n%s", forbidden, out)
		}
	}
}

func TestWriteProcessesDetailedPlainOmitsJVMFieldsForXcode(t *testing.T) {
	dr, dw := int64(4096), int64(8192)
	var buf bytes.Buffer
	WriteProcessesDetailedPlain(&buf, model.Snapshot{
		SampledAtMs: 1000,
		Processes: []model.Process{
			{PID: 600, Type: "XCODE_BUILD", RSSMemoryMB: 50, DiskReadBytes: &dr, DiskWriteBytes: &dw},
		},
	})
	out := buf.String()
	for _, want := range []string{"type=Xcode build", "disk_read_bytes=4096", "disk_write_bytes=8192"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in:\n%s", want, out)
		}
	}
	for _, forbidden := range []string{"max_heap_mb=", "java_version=", "young_gc_count="} {
		if strings.Contains(out, forbidden) {
			t.Fatalf("native detailed output must omit %q:\n%s", forbidden, out)
		}
	}
}
