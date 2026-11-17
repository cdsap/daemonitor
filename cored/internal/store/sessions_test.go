package store_test

import (
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestObservationSessionSummarizesOneIncarnationAndIsIdempotent(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "watcher.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	ref := sessions.ProcessRef{HostID: "host-a", PID: 42, StartTimeMs: 100, ProcessType: "GRADLE_DAEMON"}
	other := model.Process{PID: 42, StartTimeMs: 200, Type: "KOTLIN_DAEMON", RSSMemoryMB: 999, Status: "R"}
	heap := map[int64]int64{110: 50, 120: 80, 130: 70}
	young := map[int64]int64{110: 2, 120: 2, 130: 5}
	for at, rss := range map[int64]int64{110: 10, 120: 30, 130: 20} {
		h, y := heap[at], young[at]
		if err := s.InsertSnapshotForHost(ref.HostID, model.Snapshot{SampledAtMs: at, Processes: []model.Process{
			{PID: 42, StartTimeMs: 100, Type: "GRADLE_DAEMON", RSSMemoryMB: rss, Status: "R", HeapUsedMB: &h, YoungGCCount: &y, TotalGCCount: &y},
			other,
		}}); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.InsertBuild(builds.Build{BuildID: "ok", DaemonPID: 42, StartTimeMs: 115, FinalStatus: builds.StatusSuccess}); err != nil {
		t.Fatal(err)
	}
	if err := s.InsertBuild(builds.Build{BuildID: "bad", DaemonPID: 42, StartTimeMs: 125, FinalStatus: builds.StatusFailed}); err != nil {
		t.Fatal(err)
	}

	request := sessions.OpenRequest{Name: "build-1", HostID: ref.HostID, Association: sessions.AssociationConfirmed, Processes: []sessions.ProcessRef{ref}}
	opened, existing, err := s.OpenObservationSession(request, 100)
	if err != nil || existing || opened.Name != request.Name {
		t.Fatalf("open=%+v existing=%v err=%v", opened, existing, err)
	}
	if _, _, err := s.OpenObservationSession(request, 101); err != nil {
		t.Fatal(err)
	}
	closed, err := s.CloseObservationSession(request.Name, 140)
	if err != nil {
		t.Fatal(err)
	}
	if len(closed.Processes) != 1 {
		t.Fatalf("processes=%+v", closed.Processes)
	}
	got := closed.Processes[0]
	if got.FirstRSSMB != 10 || got.PeakRSSMB != 30 || got.LastRSSMB != 20 || got.DeltaRSSMB != 10 || got.SampleCount != 3 || got.ProcessType != "GRADLE_DAEMON" {
		t.Fatalf("rss=%+v", got)
	}
	if got.FirstHeapUsedMB == nil || *got.FirstHeapUsedMB != 50 || got.PeakHeapUsedMB == nil || *got.PeakHeapUsedMB != 80 ||
		got.LastHeapUsedMB == nil || *got.LastHeapUsedMB != 70 || got.DeltaHeapUsedMB == nil || *got.DeltaHeapUsedMB != 20 {
		t.Fatalf("heap=%+v", got)
	}
	if got.FirstYoungGCCount == nil || *got.FirstYoungGCCount != 2 || got.LastYoungGCCount == nil || *got.LastYoungGCCount != 5 ||
		got.DeltaYoungGCCount == nil || *got.DeltaYoungGCCount != 3 {
		t.Fatalf("gc=%+v", got)
	}
	if got.FirstSampleAtMs != 110 || got.LastSampleAtMs != 130 {
		t.Fatalf("sample_at=%+v", got)
	}
	if closed.Builds.Count != 2 || closed.Builds.SuccessCount != 1 || closed.Builds.FailureCount != 1 {
		t.Fatalf("builds=%+v", closed.Builds)
	}
	repeated, err := s.CloseObservationSession(request.Name, 999)
	if err != nil {
		t.Fatal(err)
	}
	if repeated.ClosedAtMs == nil || *repeated.ClosedAtMs != *closed.ClosedAtMs || len(repeated.Processes) != 1 {
		t.Fatalf("close was not idempotent: first=%+v repeated=%+v", closed, repeated)
	}
}
