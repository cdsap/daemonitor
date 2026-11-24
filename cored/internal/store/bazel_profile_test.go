package store_test

import (
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestBazelProfileMergeBothOrders(t *testing.T) {
	for _, profileFirst := range []bool{true, false} {
		t.Run(map[bool]string{true: "profile first", false: "BEP first"}[profileFirst], func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "builds.sqlite")
			s, err := store.Open(path)
			if err != nil {
				t.Fatal(err)
			}
			end := int64(4000)
			duration := 3.0
			cmd := "bazel build"
			profile := builds.Build{BuildID: "shared", DaemonPID: 42, StartTimeMs: 900, Tool: builds.ToolBazel, InferredSource: builds.SourceProfile, FinalStatus: builds.StatusCompletedNoOutcome, BazelProfile: &builds.BazelProfile{BazelVersion: "8.0", PhaseSeconds: map[string]float64{"Execution": 2}}}
			bep := builds.Build{BuildID: "shared", DaemonPID: 42, CommandLine: &cmd, ProjectPath: "/ws", StartTimeMs: 1000, EndTimeMs: &end, DurationSeconds: &duration, Tool: builds.ToolBazel, InferredSource: builds.SourceBEP, FinalStatus: builds.StatusFailed}
			input := []builds.Build{profile, bep, profile}
			if !profileFirst {
				input = []builds.Build{bep, profile, bep}
			}
			for _, b := range input {
				if err := s.InsertBuild(b); err != nil {
					t.Fatal(err)
				}
			}
			s.Close()
			s, err = store.Open(path)
			if err != nil {
				t.Fatal(err)
			}
			defer s.Close()
			rows, err := s.ListBuilds(10)
			if err != nil || len(rows) != 1 {
				t.Fatalf("rows=%v err=%v", rows, err)
			}
			b := rows[0]
			if b.DaemonPID != 42 || b.InferredSource != builds.SourceBEP || b.FinalStatus != builds.StatusFailed || b.CommandLine == nil || *b.CommandLine != cmd || b.StartTimeMs != 1000 || b.ProjectPath != "/ws" || b.DurationSeconds == nil || *b.DurationSeconds != 3 || b.BazelProfile == nil || b.BazelProfile.PhaseSeconds["Execution"] != 2 {
				t.Fatalf("merged=%+v", b)
			}
		})
	}
}

func TestBazelFirstBuildOverlapsServerStartup(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "builds.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, tc := range []struct {
		id         string
		start, end int64
	}{{"previous-server", 100, 200}, {"first-build", 900, 2000}} {
		if err := s.InsertBuild(builds.Build{BuildID: tc.id, DaemonPID: 42, StartTimeMs: tc.start, EndTimeMs: &tc.end, Tool: builds.ToolBazel, InferredSource: builds.SourceProfile, FinalStatus: builds.StatusCompletedNoOutcome}); err != nil {
			t.Fatal(err)
		}
	}
	rows, err := s.ListBuildsForDaemonSince(42, 1000, 10)
	if err != nil || len(rows) != 1 || rows[0].BuildID != "first-build" {
		t.Fatalf("rows=%+v err=%v", rows, err)
	}
}
