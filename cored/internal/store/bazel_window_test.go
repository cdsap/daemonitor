package store_test

import (
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func i64(v int64) *int64       { return &v }
func f64(v float64) *float64   { return &v }

func TestBazelBuildWindowMultiWorkerAndIncarnationScoping(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "bazel-window.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	const (
		host          = "mac-build"
		serverPID     = int32(4200)
		oldStartMs    = int64(500)
		activeStartMs = int64(1_000)
	)

	// Old incarnation that reused PID 4200 (and its worker) must never pollute the active build window.
	if err := s.InsertSnapshotForHost(host, model.Snapshot{
		SampledAtMs: 2_500,
		Processes: []model.Process{
			{
				PID: serverPID, StartTimeMs: oldStartMs, Type: "BAZEL_SERVER",
				CommandLine: "java -jar A-server.jar --output_base=/old",
				RSSMemoryMB: 9_999, SubtreeRSSMemoryMB: i64(9_999),
				CPUPercent: f64(99.0), SubtreeCPUPercent: f64(99.0),
				HeapUsedMB: i64(8_000), Status: "RUNNING",
			},
			{
				PID: 4299, ParentPID: 4298, OwnerPID: serverPID, OwnerStartTimeMs: oldStartMs,
				StartTimeMs: 600, Type: "BAZEL_WORKER",
				CommandLine: "java JavaBuilder_deploy.jar --persistent_worker",
				RSSMemoryMB: 4_000, CPUPercent: f64(50.0), HeapUsedMB: i64(3_000), Status: "RUNNING",
			},
		},
	}); err != nil {
		t.Fatal(err)
	}

	// Active incarnation samples inside build window [2_000, 4_000]:
	// t=2_000: server with pre-rolled subtree totals (600 MB, 30%) + JVM worker + native test.
	// t=3_500: server with pre-rolled subtree totals (900 MB, 65%) + JVM worker (heap/GC growth) + native test.
	if err := s.InsertSnapshotForHost(host, model.Snapshot{
		SampledAtMs: 2_000,
		Processes: []model.Process{
			{
				PID: serverPID, StartTimeMs: activeStartMs, Type: "BAZEL_SERVER",
				CommandLine: "java -jar A-server.jar --output_base=/active",
				RSSMemoryMB: 400, SubtreeRSSMemoryMB: i64(600),
				CPUPercent: f64(20.0), SubtreeCPUPercent: f64(30.0),
				HeapUsedMB: i64(200), MetaspaceUsedMB: i64(80),
				YoungGCCount: i64(10), TotalGCCount: i64(12),
				YoungGCTimeMs: i64(100), OldGCTimeMs: i64(50),
				Status: "RUNNING",
			},
			{
				PID: 4301, ParentPID: 4300, OwnerPID: serverPID, OwnerStartTimeMs: activeStartMs,
				StartTimeMs: 1_500, Type: "BAZEL_WORKER",
				CommandLine: "java JavaBuilder_deploy.jar --persistent_worker",
				RSSMemoryMB: 150, CPUPercent: f64(8.0),
				HeapUsedMB: i64(60), MetaspaceUsedMB: i64(30),
				YoungGCCount: i64(2), TotalGCCount: i64(2),
				YoungGCTimeMs: i64(15), OldGCTimeMs: i64(0),
				Status: "RUNNING",
			},
			{
				PID: 4302, ParentPID: 4300, OwnerPID: serverPID, OwnerStartTimeMs: activeStartMs,
				StartTimeMs: 1_800, Type: "BAZEL_TEST",
				CommandLine: "/sandbox/foo.runfiles/native_test",
				RSSMemoryMB: 50, CPUPercent: f64(2.0), Status: "RUNNING",
			},
		},
	}); err != nil {
		t.Fatal(err)
	}
	if err := s.InsertSnapshotForHost(host, model.Snapshot{
		SampledAtMs: 3_500,
		Processes: []model.Process{
			{
				PID: serverPID, StartTimeMs: activeStartMs, Type: "BAZEL_SERVER",
				CommandLine: "java -jar A-server.jar --output_base=/active",
				RSSMemoryMB: 550, SubtreeRSSMemoryMB: i64(900),
				CPUPercent: f64(35.0), SubtreeCPUPercent: f64(65.0),
				HeapUsedMB: i64(340), MetaspaceUsedMB: i64(95),
				YoungGCCount: i64(16), TotalGCCount: i64(19),
				YoungGCTimeMs: i64(220), OldGCTimeMs: i64(140),
				Status: "RUNNING",
			},
			{
				PID: 4301, ParentPID: 4300, OwnerPID: serverPID, OwnerStartTimeMs: activeStartMs,
				StartTimeMs: 1_500, Type: "BAZEL_WORKER",
				CommandLine: "java JavaBuilder_deploy.jar --persistent_worker",
				RSSMemoryMB: 250, CPUPercent: f64(25.0),
				HeapUsedMB: i64(140), MetaspaceUsedMB: i64(34),
				YoungGCCount: i64(5), TotalGCCount: i64(6),
				YoungGCTimeMs: i64(45), OldGCTimeMs: i64(10),
				Status: "RUNNING",
			},
			{
				PID: 4302, ParentPID: 4300, OwnerPID: serverPID, OwnerStartTimeMs: activeStartMs,
				StartTimeMs: 1_800, Type: "BAZEL_TEST",
				CommandLine: "/sandbox/foo.runfiles/native_test",
				RSSMemoryMB: 100, CPUPercent: f64(5.0), Status: "RUNNING",
			},
		},
	}); err != nil {
		t.Fatal(err)
	}

	resolvedHost, resolvedStart, ok := s.ResolveBazelServerIncarnation(host, int64(serverPID), 2_000, 4_000)
	if !ok || resolvedHost != host || resolvedStart != activeStartMs {
		t.Fatalf("resolved=(%q,%d,%v) want (%q,%d,true)", resolvedHost, resolvedStart, ok, host, activeStartMs)
	}

	samples, summaries, err := s.BazelBuildWindowSamples(host, int64(serverPID), activeStartMs, 2_000, 4_000)
	if err != nil {
		t.Fatal(err)
	}
	if len(samples) != 2 {
		t.Fatalf("samples=%d want 2", len(samples))
	}
	peakMem, avgMem, peakCPU := builds.SummarizeSamples(samples)
	if peakMem == nil || *peakMem != 900 || avgMem == nil || *avgMem != 750 || peakCPU == nil || *peakCPU != 65.0 {
		t.Fatalf("totals peak=%v avg=%v cpu=%v want 900/750/65", peakMem, avgMem, peakCPU)
	}

	if len(summaries) != 3 {
		t.Fatalf("summaries=%d want 3 (%+v)", len(summaries), summaries)
	}
	// Ordered: BAZEL_SERVER first, then BAZEL_TEST, then BAZEL_WORKER.
	srv := summaries[0]
	if srv.ProcessType != "BAZEL_SERVER" || srv.PID != int64(serverPID) || srv.StartTimeMs != activeStartMs {
		t.Fatalf("server summary=%+v", srv)
	}
	if srv.FirstRSSMB != 400 || srv.PeakRSSMB != 550 || srv.LastRSSMB != 550 || srv.DeltaRSSMB != 150 {
		t.Fatalf("server RSS=%+v", srv)
	}
	if srv.DeltaHeapUsedMB == nil || *srv.DeltaHeapUsedMB != 140 ||
		srv.DeltaMetaspaceUsedMB == nil || *srv.DeltaMetaspaceUsedMB != 15 ||
		srv.DeltaYoungGCCount == nil || *srv.DeltaYoungGCCount != 6 ||
		srv.DeltaTotalGCCount == nil || *srv.DeltaTotalGCCount != 7 ||
		srv.DeltaYoungGCTimeMs == nil || *srv.DeltaYoungGCTimeMs != 120 ||
		srv.DeltaOldGCTimeMs == nil || *srv.DeltaOldGCTimeMs != 90 {
		t.Fatalf("server JVM deltas=%+v", srv)
	}

	nativeTest := summaries[1]
	if nativeTest.ProcessType != "BAZEL_TEST" || nativeTest.DeltaRSSMB != 50 || nativeTest.FirstHeapUsedMB != nil || nativeTest.FirstYoungGCCount != nil {
		t.Fatalf("native test summary=%+v", nativeTest)
	}

	worker := summaries[2]
	if worker.ProcessType != "BAZEL_WORKER" || worker.DeltaRSSMB != 100 || worker.DeltaHeapUsedMB == nil || *worker.DeltaHeapUsedMB != 80 || worker.DeltaYoungGCCount == nil || *worker.DeltaYoungGCCount != 3 {
		t.Fatalf("worker summary=%+v", worker)
	}

	// Sub-poll window with no samples returns nil so build columns stay NULL.
	emptySamples, emptySummaries, err := s.BazelBuildWindowSamples(host, int64(serverPID), activeStartMs, 10_000, 10_500)
	if err != nil {
		t.Fatal(err)
	}
	if len(emptySamples) != 0 || len(emptySummaries) != 0 {
		t.Fatalf("expected empty sub-poll window, got samples=%v summaries=%v", emptySamples, emptySummaries)
	}
}

func TestBazelBuildWindowSumsWorkersWhenSubtreeTotalsAbsent(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "bazel-nosubtree.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	const host = "linux-build"
	if err := s.InsertSnapshotForHost(host, model.Snapshot{
		SampledAtMs: 5_000,
		Processes: []model.Process{
			{PID: 100, StartTimeMs: 1_000, Type: "BAZEL_SERVER", CommandLine: "A-server.jar", RSSMemoryMB: 300, CPUPercent: f64(15.0), Status: "RUNNING"},
			{PID: 101, OwnerPID: 100, OwnerStartTimeMs: 1_000, StartTimeMs: 2_000, Type: "BAZEL_WORKER", CommandLine: "worker", RSSMemoryMB: 200, CPUPercent: f64(25.0), Status: "RUNNING"},
		},
	}); err != nil {
		t.Fatal(err)
	}

	samples, summaries, err := s.BazelBuildWindowSamples(host, 100, 1_000, 4_500, 5_500)
	if err != nil {
		t.Fatal(err)
	}
	peakMem, avgMem, peakCPU := builds.SummarizeSamples(samples)
	if peakMem == nil || *peakMem != 500 || avgMem == nil || *avgMem != 500 || peakCPU == nil || *peakCPU != 40.0 {
		t.Fatalf("summed totals peak=%v avg=%v cpu=%v want 500/500/40", peakMem, avgMem, peakCPU)
	}
	if len(summaries) != 2 {
		t.Fatalf("summaries=%d want 2", len(summaries))
	}
}

func TestInsertBuildRoundTripsWindowSummariesAcrossProfileAndBEP(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "bazel-summaries.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	end := int64(4_000)
	summary := []sessions.RSSSummary{{
		HostID: "h1", PID: 42, StartTimeMs: 1_000, ProcessType: "BAZEL_SERVER",
		SampleCount: 2, FirstRSSMB: 400, PeakRSSMB: 600, LastRSSMB: 550, DeltaRSSMB: 150,
		FirstHeapUsedMB: i64(200), PeakHeapUsedMB: i64(350), LastHeapUsedMB: i64(300), DeltaHeapUsedMB: i64(100),
		Association: sessions.AssociationInferred,
	}}
	if err := s.InsertBuild(builds.Build{
		BuildID: "uuid-1", DaemonPID: 42, StartTimeMs: 2_000, EndTimeMs: &end,
		PeakMemoryMB: i64(600), AvgMemoryMB: i64(500), PeakCPUPercent: f64(45.0),
		Tool: builds.ToolBazel, InferredSource: builds.SourceProfile, FinalStatus: builds.StatusSuccess,
		WindowSummaries: summary,
	}); err != nil {
		t.Fatal(err)
	}
	// Later BEP arrival without window_summaries must retain the already-computed summaries.
	if err := s.InsertBuild(builds.Build{
		BuildID: "uuid-1", DaemonPID: 42, StartTimeMs: 2_000, EndTimeMs: &end,
		Tool: builds.ToolBazel, InferredSource: builds.SourceBEP, FinalStatus: builds.StatusSuccess,
	}); err != nil {
		t.Fatal(err)
	}
	rows, err := s.ListBuilds(10)
	if err != nil || len(rows) != 1 {
		t.Fatalf("rows=%+v err=%v", rows, err)
	}
	got := rows[0]
	if got.InferredSource != builds.SourceBEP || len(got.WindowSummaries) != 1 {
		t.Fatalf("got=%+v", got)
	}
	if got.WindowSummaries[0].DeltaHeapUsedMB == nil || *got.WindowSummaries[0].DeltaHeapUsedMB != 100 {
		t.Fatalf("window summary=%+v", got.WindowSummaries[0])
	}
}
