package store_test

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestBazelBuildDetailsUpsertBatchAndRetentionPurge(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "bazel-details.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	now := time.Now().UnixMilli()
	oldStart := now - int64((48 * time.Hour).Milliseconds())
	recentStart := now - 5_000
	wall := int64(4062)
	executed := int64(6)

	for _, b := range []builds.Build{
		{
			BuildID: "old-bazel", DaemonPID: 10, StartTimeMs: oldStart,
			Tool: builds.ToolBazel, InferredSource: builds.SourceBEP, FinalStatus: builds.StatusSuccess,
			BazelDetails: &builds.BazelBuildDetails{
				SchemaVersion: 1, BazelVersion: "8.4.2", SourceFormat: "json",
				CollectionState: builds.CollectionStateSealed, UpdatedAtMs: oldStart,
				Metrics: builds.BazelBuildMetrics{Timing: &builds.BazelTimingMetrics{WallTimeMs: &wall}},
			},
		},
		{
			BuildID: "recent-bazel", DaemonPID: 10, StartTimeMs: recentStart,
			Tool: builds.ToolBazel, InferredSource: builds.SourceBEP, FinalStatus: builds.StatusSuccess,
			BazelDetails: &builds.BazelBuildDetails{
				SchemaVersion: 1, BazelVersion: "8.4.2", SourceFormat: "binary",
				CollectionState: builds.CollectionStatePartial, UpdatedAtMs: recentStart,
				Metrics: builds.BazelBuildMetrics{},
			},
		},
	} {
		if err := s.InsertBuild(b); err != nil {
			t.Fatal(err)
		}
	}

	// Sealed update replaces partial; a subsequent partial update must NOT downgrade sealed.
	if err := s.UpsertBazelBuildDetails("recent-bazel", &builds.BazelBuildDetails{
		SchemaVersion: 1, BazelVersion: "8.4.2", SourceFormat: "binary",
		CollectionState: builds.CollectionStateSealed, UpdatedAtMs: recentStart + 1000,
		Metrics: builds.BazelBuildMetrics{
			Actions: &builds.BazelActionSummary{Executed: &executed},
			Timing:  &builds.BazelTimingMetrics{WallTimeMs: &wall},
		},
	}); err != nil {
		t.Fatal(err)
	}
	if err := s.UpsertBazelBuildDetails("recent-bazel", &builds.BazelBuildDetails{
		SchemaVersion: 1, SourceFormat: "binary",
		CollectionState: builds.CollectionStatePartial, UpdatedAtMs: recentStart + 2000,
		Metrics: builds.BazelBuildMetrics{},
	}); err != nil {
		t.Fatal(err)
	}

	batch, err := s.GetBazelBuildDetailsBatch([]string{"old-bazel", "recent-bazel", "missing"})
	if err != nil {
		t.Fatal(err)
	}
	if len(batch) != 2 {
		t.Fatalf("batch=%+v want 2", batch)
	}
	rec := batch["recent-bazel"]
	if rec.CollectionState != builds.CollectionStateSealed || rec.Metrics.Actions == nil || *rec.Metrics.Actions.Executed != 6 {
		t.Fatalf("recent details downgraded or missing: %+v", rec)
	}

	// Retention purge must explicitly remove expired bazel_build_details rows too.
	if _, err := s.PurgeOlderThan(24 * time.Hour); err != nil {
		t.Fatal(err)
	}
	after, err := s.GetBazelBuildDetailsBatch([]string{"old-bazel", "recent-bazel"})
	if err != nil {
		t.Fatal(err)
	}
	if _, exists := after["old-bazel"]; exists {
		t.Fatalf("expired bazel_build_details row survived purge: %+v", after["old-bazel"])
	}
	if _, exists := after["recent-bazel"]; !exists {
		t.Fatal("recent bazel_build_details row was unexpectedly purged")
	}
}
