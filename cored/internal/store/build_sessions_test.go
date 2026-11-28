package store_test

import (
	"errors"
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestBuildSessionDuplicateOpenAndIdempotentClose(t *testing.T) {
	reg := sessions.NewBuildSessionRegistry(nil, nil)
	opened, err := reg.Open(sessions.BuildSessionOpenRequest{SessionID: "fixed", RootProjectPath: "/p", StartedAtMs: 1})
	if err != nil {
		t.Fatal(err)
	}
	_, err = reg.Open(sessions.BuildSessionOpenRequest{SessionID: opened.SessionID, RootProjectPath: "/p", StartedAtMs: 1})
	if !errors.Is(err, sessions.ErrBuildSessionDuplicateOpen) {
		t.Fatalf("want duplicate, got %v", err)
	}
	first, err := reg.Close(opened.SessionID, sessions.BuildSessionCloseRequest{EndedAtMs: 2})
	if err != nil {
		t.Fatal(err)
	}
	second, err := reg.Close(opened.SessionID, sessions.BuildSessionCloseRequest{EndedAtMs: 3})
	if !errors.Is(err, sessions.ErrBuildSessionAlreadyClosed) {
		t.Fatalf("want already closed, got %v", err)
	}
	if second == nil || second.SessionID != first.SessionID {
		t.Fatalf("idempotent close should still return prior summary")
	}
	if _, err := reg.Close("missing", sessions.BuildSessionCloseRequest{}); !errors.Is(err, sessions.ErrBuildSessionNotFound) {
		t.Fatalf("want not found, got %v", err)
	}
}

func TestPersistentXcodeSessionAttributionAndRoleRollups(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "watcher.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	reg := sessions.NewBuildSessionRegistry(st, st)

	opened, err := reg.Open(sessions.BuildSessionOpenRequest{
		Ecosystem:         "xcode",
		Mode:              "explicit",
		RootProjectPath:   "/work/MyApp.xcworkspace",
		CommandLine:       "/usr/bin/xcodebuild -workspace MyApp.xcworkspace build",
		AnchorPID:         1000,
		AnchorStartTimeMs: 990,
		StartedAtMs:       1_000,
	})
	if err != nil {
		t.Fatal(err)
	}

	proj := "/work/MyApp.xcworkspace"
	fp1, fp2, fp3 := int64(50<<20), int64(80<<20), int64(120<<20)
	cpu1, cpu2, cpu3 := 0.5, 1.5, 2.2
	dr1, dr2, dr3 := int64(1000), int64(5000), int64(9000)
	dw1, dw2 := int64(200), int64(1200)

	// Tick 1: xcodebuild + SWBBuildService + 1st swift-frontend
	if err := st.InsertSnapshot(model.Snapshot{
		SampledAtMs: 1_500,
		Processes: []model.Process{
			{PID: 1000, StartTimeMs: 990, Type: "XCODE_BUILD", Name: "xcodebuild", ProjectPath: &proj, RSSMemoryMB: 100, PhysicalFootprintBytes: &fp1, CPUTotalSecondsTotal: &cpu1, DiskReadBytes: &dr1, DiskWriteBytes: &dw1, Status: "R"},
			{PID: 2000, StartTimeMs: 100, Type: "XCODE_BUILD_SERVICE", Name: "SWBBuildService", RSSMemoryMB: 200, PhysicalFootprintBytes: &fp2, CPUTotalSecondsTotal: &cpu1, DiskReadBytes: &dr1, DiskWriteBytes: &dw1, Status: "R"},
			{PID: 3001, ParentPID: 2000, StartTimeMs: 1_100, Type: "XCODE_COMPILER", Name: "swift-frontend", RSSMemoryMB: 300, PhysicalFootprintBytes: &fp3, CPUTotalSecondsTotal: &cpu2, DiskReadBytes: &dr2, DiskWriteBytes: &dw2, Status: "R"},
		},
	}); err != nil {
		t.Fatal(err)
	}
	// Tick 2: xcodebuild + SWBBuildService + 2 parallel swift-frontends (ephemeral 1-sample job 3002!)
	if err := st.InsertSnapshot(model.Snapshot{
		SampledAtMs: 2_500,
		Processes: []model.Process{
			{PID: 1000, StartTimeMs: 990, Type: "XCODE_BUILD", Name: "xcodebuild", ProjectPath: &proj, RSSMemoryMB: 120, PhysicalFootprintBytes: &fp1, CPUTotalSecondsTotal: &cpu2, DiskReadBytes: &dr2, DiskWriteBytes: &dw2, Status: "R"},
			{PID: 2000, StartTimeMs: 100, Type: "XCODE_BUILD_SERVICE", Name: "SWBBuildService", RSSMemoryMB: 220, PhysicalFootprintBytes: &fp2, CPUTotalSecondsTotal: &cpu2, DiskReadBytes: &dr3, DiskWriteBytes: &dw2, Status: "R"},
			{PID: 3001, ParentPID: 2000, StartTimeMs: 1_100, Type: "XCODE_COMPILER", Name: "swift-frontend", RSSMemoryMB: 320, PhysicalFootprintBytes: &fp3, CPUTotalSecondsTotal: &cpu3, DiskReadBytes: &dr3, DiskWriteBytes: &dw2, Status: "R"},
			{PID: 3002, ParentPID: 2000, StartTimeMs: 2_100, Type: "XCODE_COMPILER", Name: "swift-frontend", RSSMemoryMB: 180, PhysicalFootprintBytes: &fp2, CPUTotalSecondsTotal: &cpu1, DiskReadBytes: &dr1, DiskWriteBytes: &dw1, Status: "R"},
		},
	}); err != nil {
		t.Fatal(err)
	}

	summary, err := reg.Close(opened.SessionID, sessions.BuildSessionCloseRequest{
		EndedAtMs: 3_000,
		Outcome:   "success",
	})
	if err != nil {
		t.Fatal(err)
	}
	if summary.ObservedProcessCount != 4 || summary.MaxParallelism != 4 {
		t.Fatalf("observed=%d maxPar=%d want 4/4", summary.ObservedProcessCount, summary.MaxParallelism)
	}
	if summary.TotalPeakRSSMB != 120+220+320+180 {
		t.Fatalf("TotalPeakRSSMB=%d want 840", summary.TotalPeakRSSMB)
	}
	if summary.TotalCPUSeconds == nil || *summary.TotalCPUSeconds < 4.5 {
		t.Fatalf("TotalCPUSeconds=%v want >= 4.5 (including 1-sample ephemeral compiler)", summary.TotalCPUSeconds)
	}
	if summary.ConcurrentOverlap {
		t.Fatal("single build should not set concurrent_overlap")
	}
	if len(summary.Roles) != 3 {
		t.Fatalf("roles=%+v want 3 roles", summary.Roles)
	}

	// Verify durable reload from SQLite.
	listed, err := reg.List("xcode", 10)
	if err != nil || len(listed) != 1 || listed[0].SessionID != opened.SessionID {
		t.Fatalf("persisted list=%+v err=%v", listed, err)
	}
}

func TestConcurrentSameProjectBuildsSeparateByDerivedDataPath(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "watcher.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	reg := sessions.NewBuildSessionRegistry(st, st)

	sA, err := reg.Open(sessions.BuildSessionOpenRequest{
		Ecosystem:       "xcode",
		RootProjectPath: "/work/SharedApp",
		CommandLine:     "xcodebuild -scheme App -derivedDataPath /tmp/dd-a build",
		AnchorPID:       101,
		StartedAtMs:     1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = reg.Open(sessions.BuildSessionOpenRequest{
		Ecosystem:       "xcode",
		RootProjectPath: "/work/SharedApp",
		CommandLine:     "xcodebuild -scheme App -derivedDataPath /tmp/dd-b build",
		AnchorPID:       102,
		StartedAtMs:     1_100,
	})
	if err != nil {
		t.Fatal(err)
	}

	_ = st.InsertSnapshot(model.Snapshot{
		SampledAtMs: 1_500,
		Processes: []model.Process{
			{PID: 101, StartTimeMs: 990, Type: "XCODE_BUILD", CommandLine: "xcodebuild -derivedDataPath /tmp/dd-a", RSSMemoryMB: 100, Status: "R"},
			{PID: 102, StartTimeMs: 1_090, Type: "XCODE_BUILD", CommandLine: "xcodebuild -derivedDataPath /tmp/dd-b", RSSMemoryMB: 100, Status: "R"},
			{PID: 500, StartTimeMs: 100, Type: "XCODE_BUILD_SERVICE", CommandLine: "SWBBuildService", RSSMemoryMB: 200, Status: "R"},
			{PID: 601, ParentPID: 500, StartTimeMs: 1_200, Type: "XCODE_COMPILER", CommandLine: "swift-frontend -c /work/SharedApp/A.swift -o /tmp/dd-a/A.o", RSSMemoryMB: 300, Status: "R"},
			{PID: 602, ParentPID: 500, StartTimeMs: 1_200, Type: "XCODE_COMPILER", CommandLine: "swift-frontend -c /work/SharedApp/A.swift -o /tmp/dd-b/A.o", RSSMemoryMB: 300, Status: "R"},
		},
	})

	sumA, err := reg.Close(sA.SessionID, sessions.BuildSessionCloseRequest{EndedAtMs: 2_000, Outcome: "success"})
	if err != nil {
		t.Fatal(err)
	}
	if !sumA.ConcurrentOverlap {
		t.Fatal("expected concurrent_overlap=true")
	}
	byPID := map[int32]string{}
	for _, p := range sumA.Processes {
		byPID[p.PID] = p.Association
	}
	if byPID[101] != sessions.AssociationConfirmed || byPID[601] != sessions.AssociationConfirmed {
		t.Fatalf("build A anchor & compiler should be confirmed: %+v", byPID)
	}
	if byPID[500] != sessions.AssociationAmbiguous {
		t.Fatalf("shared SWBBuildService should be ambiguous: %+v", byPID)
	}
	if _, leakedAnchor := byPID[102]; leakedAnchor {
		t.Fatalf("build B anchor leaked into build A: %+v", byPID)
	}
	if _, leakedCompiler := byPID[602]; leakedCompiler {
		t.Fatalf("build B compiler leaked into build A: %+v", byPID)
	}
}
