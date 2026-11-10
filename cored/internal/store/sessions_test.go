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

	ref := sessions.ProcessRef{HostID: "host-a", PID: 42, StartTimeMs: 100}
	other := model.Process{PID: 42, StartTimeMs: 200, RSSMemoryMB: 999, Status: "R"}
	for at, rss := range map[int64]int64{110: 10, 120: 30, 130: 20} {
		if err := s.InsertSnapshotForHost(ref.HostID, model.Snapshot{SampledAtMs: at, Processes: []model.Process{{PID: 42, StartTimeMs: 100, RSSMemoryMB: rss, Status: "R"}, other}}); err != nil {
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
	if got.FirstRSSMB != 10 || got.PeakRSSMB != 30 || got.LastRSSMB != 20 || got.DeltaRSSMB != 10 || got.SampleCount != 3 {
		t.Fatalf("rss=%+v", got)
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
