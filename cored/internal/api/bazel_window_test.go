package api

import (
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func ptrI64(v int64) *int64     { return &v }
func ptrF64(v float64) *float64 { return &v }

func TestPersistBazelBuildCorrelatesWindowMetricsAndRejectsReusedPID(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "bazel-api-window.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	const host = "dev-host"
	// Reused PID incarnation that must be excluded.
	if err := st.InsertSnapshotForHost(host, model.Snapshot{
		SampledAtMs: 2_500,
		Processes: []model.Process{
			{PID: 42, StartTimeMs: 100, Type: "BAZEL_SERVER", CommandLine: "A-server.jar", RSSMemoryMB: 9_000, SubtreeRSSMemoryMB: ptrI64(9_000), CPUPercent: ptrF64(99), SubtreeCPUPercent: ptrF64(99), Status: "RUNNING"},
		},
	}); err != nil {
		t.Fatal(err)
	}
	// Active BAZEL_SERVER (start=1000) + owned BAZEL_WORKER inside [2000, 4000].
	for _, snap := range []model.Snapshot{
		{
			SampledAtMs: 2_200,
			Processes: []model.Process{
				{PID: 42, StartTimeMs: 1_000, Type: "BAZEL_SERVER", CommandLine: "A-server.jar", RSSMemoryMB: 300, SubtreeRSSMemoryMB: ptrI64(450), CPUPercent: ptrF64(15), SubtreeCPUPercent: ptrF64(25), HeapUsedMB: ptrI64(180), YoungGCCount: ptrI64(4), Status: "RUNNING"},
				{PID: 43, OwnerPID: 42, OwnerStartTimeMs: 1_000, StartTimeMs: 1_500, Type: "BAZEL_WORKER", CommandLine: "JavaBuilder_deploy.jar --persistent_worker", RSSMemoryMB: 150, CPUPercent: ptrF64(10), HeapUsedMB: ptrI64(70), Status: "RUNNING"},
			},
		},
		{
			SampledAtMs: 3_600,
			Processes: []model.Process{
				{PID: 42, StartTimeMs: 1_000, Type: "BAZEL_SERVER", CommandLine: "A-server.jar", RSSMemoryMB: 500, SubtreeRSSMemoryMB: ptrI64(750), CPUPercent: ptrF64(30), SubtreeCPUPercent: ptrF64(55), HeapUsedMB: ptrI64(290), YoungGCCount: ptrI64(9), Status: "RUNNING"},
				{PID: 43, OwnerPID: 42, OwnerStartTimeMs: 1_000, StartTimeMs: 1_500, Type: "BAZEL_WORKER", CommandLine: "JavaBuilder_deploy.jar --persistent_worker", RSSMemoryMB: 250, CPUPercent: ptrF64(25), HeapUsedMB: ptrI64(120), Status: "RUNNING"},
			},
		},
	} {
		if err := st.InsertSnapshotForHost(host, snap); err != nil {
			t.Fatal(err)
		}
	}

	srv := &Server{Store: st}
	end := int64(4_000)
	dur := 2.0
	srv.persistBazelBuildWithContext(builds.Build{
		BuildID: "bep-window-1", DaemonPID: 42, StartTimeMs: 2_000, EndTimeMs: &end,
		DurationSeconds: &dur, Tool: builds.ToolBazel, InferredSource: builds.SourceBEP,
		FinalStatus: builds.StatusSuccess, ProjectPath: "/work/app",
	}, []model.Process{{PID: 42, StartTimeMs: 1_000, Type: "BAZEL_SERVER"}}, host)

	// Sub-poll build with no samples in [5000, 5100] must preserve NULL peak/avg/CPU.
	subEnd := int64(5_100)
	subDur := 0.1
	srv.persistBazelBuildWithContext(builds.Build{
		BuildID: "bep-subpoll-2", DaemonPID: 42, StartTimeMs: 5_000, EndTimeMs: &subEnd,
		DurationSeconds: &subDur, Tool: builds.ToolBazel, InferredSource: builds.SourceBEP,
		FinalStatus: builds.StatusSuccess, ProjectPath: "/work/app",
	}, []model.Process{{PID: 42, StartTimeMs: 1_000, Type: "BAZEL_SERVER"}}, host)

	rec := httptest.NewRecorder()
	srv.handleBuilds(rec, httptest.NewRequest("GET", "/v1/builds?pid=42&start_time_ms=1000", nil))
	if rec.Code != 200 {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	var payload struct {
		Builds []builds.Build `json:"builds"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Builds) != 2 {
		t.Fatalf("builds=%d want 2", len(payload.Builds))
	}

	// Newest first: subpoll first, then window-1.
	sub := payload.Builds[0]
	if sub.BuildID != "bep-subpoll-2" || sub.PeakMemoryMB != nil || sub.AvgMemoryMB != nil || sub.PeakCPUPercent != nil || len(sub.WindowSummaries) != 0 {
		t.Fatalf("sub-poll build should preserve NULLs: %+v", sub)
	}

	got := payload.Builds[1]
	if got.BuildID != "bep-window-1" || got.EndTimeMs == nil || *got.EndTimeMs != 4_000 {
		t.Fatalf("got=%+v", got)
	}
	if got.PeakMemoryMB == nil || *got.PeakMemoryMB != 750 || got.AvgMemoryMB == nil || *got.AvgMemoryMB != 600 || got.PeakCPUPercent == nil || *got.PeakCPUPercent != 55.0 {
		t.Fatalf("totals peak=%v avg=%v cpu=%v want 750/600/55", got.PeakMemoryMB, got.AvgMemoryMB, got.PeakCPUPercent)
	}
	if len(got.WindowSummaries) != 2 {
		t.Fatalf("window summaries=%+v want 2", got.WindowSummaries)
	}
	if got.WindowSummaries[0].ProcessType != "BAZEL_SERVER" || got.WindowSummaries[0].DeltaHeapUsedMB == nil || *got.WindowSummaries[0].DeltaHeapUsedMB != 110 || got.WindowSummaries[0].DeltaYoungGCCount == nil || *got.WindowSummaries[0].DeltaYoungGCCount != 5 {
		t.Fatalf("server window summary=%+v", got.WindowSummaries[0])
	}
	if got.WindowSummaries[1].ProcessType != "BAZEL_WORKER" || got.WindowSummaries[1].DeltaHeapUsedMB == nil || *got.WindowSummaries[1].DeltaHeapUsedMB != 50 {
		t.Fatalf("worker window summary=%+v", got.WindowSummaries[1])
	}
}
