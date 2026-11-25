package analysis

import (
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestAnalyzeBuildRetentionRequiresCompleteSurroundingSamples(t *testing.T) {
	end := int64(210_000)
	samples := []model.Process{
		{PID: 7, StartTimeMs: 100, SampledAtMs: 30_000, RSSMemoryMB: 100},
		{PID: 7, StartTimeMs: 100, SampledAtMs: 90_000, RSSMemoryMB: 200},
		{PID: 7, StartTimeMs: 100, SampledAtMs: 120_000, RSSMemoryMB: 450},
		{PID: 7, StartTimeMs: 100, SampledAtMs: 180_000, RSSMemoryMB: 400},
		{PID: 7, StartTimeMs: 100, SampledAtMs: 240_000, RSSMemoryMB: 300},
	}
	retained := AnalyzeBuildRetention(samples, []Build{{ID: "complete", PID: 7, StartTimeMs: 100_000, EndTimeMs: &end}}, 7, 100)
	if len(retained) != 1 || retained[0].BeforeMB != 200 || retained[0].PeakMB != 450 || retained[0].AfterMB != 300 || retained[0].RetainedMB != 100 {
		t.Fatalf("retention=%+v", retained)
	}

	retained = AnalyzeBuildRetention(samples, []Build{{ID: "incomplete", PID: 7, StartTimeMs: 100_000}}, 7, 100)
	if len(retained) != 0 {
		t.Fatalf("incomplete build reported: %+v", retained)
	}
}

func TestAnalyzeBuildRetentionRejectsIncarnationAndSamplingGaps(t *testing.T) {
	end := int64(210_000)
	samples := []model.Process{
		{PID: 7, StartTimeMs: 200, SampledAtMs: 1, RSSMemoryMB: 100},
		{PID: 7, StartTimeMs: 100, SampledAtMs: 90_000, RSSMemoryMB: 200},
		{PID: 7, StartTimeMs: 100, SampledAtMs: 120_000, RSSMemoryMB: 450},
		{PID: 7, StartTimeMs: 100, SampledAtMs: 500_000, RSSMemoryMB: 300},
	}
	got := AnalyzeBuildRetention(samples, []Build{{ID: "gap", PID: 7, StartTimeMs: 100_000, EndTimeMs: &end}}, 7, 100)
	if len(got) != 0 {
		t.Fatalf("reported retention across a sampling gap or incarnation: %+v", got)
	}
}

func TestAnalyzeBuildRetentionTracksConsecutiveBazelServerHeapRetention(t *testing.T) {
	heap := func(v int64) *int64 { return &v }
	end1 := int64(210_000)
	end2 := int64(360_000)
	samples := []model.Process{
		// Different incarnation with same PID must be ignored.
		{PID: 42, StartTimeMs: 50, SampledAtMs: 95_000, RSSMemoryMB: 9_000, HeapUsedMB: heap(8_000)},
		// Build 1: [100_000, 210_000] — Skyframe retains +120 MB heap after build 1.
		{PID: 42, StartTimeMs: 1_000, SampledAtMs: 90_000, RSSMemoryMB: 500, HeapUsedMB: heap(200)},
		{PID: 42, StartTimeMs: 1_000, SampledAtMs: 150_000, RSSMemoryMB: 900, HeapUsedMB: heap(520)},
		{PID: 42, StartTimeMs: 1_000, SampledAtMs: 240_000, RSSMemoryMB: 660, HeapUsedMB: heap(320)},
		// Build 2: [260_000, 360_000] — retains another +180 MB heap after build 2.
		{PID: 42, StartTimeMs: 1_000, SampledAtMs: 310_000, RSSMemoryMB: 1_100, HeapUsedMB: heap(780)},
		{PID: 42, StartTimeMs: 1_000, SampledAtMs: 390_000, RSSMemoryMB: 840, HeapUsedMB: heap(500)},
	}
	got := AnalyzeBuildRetention(samples, []Build{
		{ID: "bazel-1", PID: 42, StartTimeMs: 100_000, EndTimeMs: &end1},
		{ID: "bazel-2", PID: 42, StartTimeMs: 260_000, EndTimeMs: &end2},
	}, 42, 1_000)
	if len(got) != 2 {
		t.Fatalf("got=%+v want 2", got)
	}
	if got[0].RetainedMB != 160 || got[0].HeapBeforeMB == nil || *got[0].HeapBeforeMB != 200 || *got[0].HeapPeakMB != 520 || *got[0].HeapAfterMB != 320 || *got[0].HeapRetainedMB != 120 {
		t.Fatalf("build 1 retention=%+v", got[0])
	}
	if got[1].RetainedMB != 180 || got[1].HeapBeforeMB == nil || *got[1].HeapBeforeMB != 320 || *got[1].HeapPeakMB != 780 || *got[1].HeapAfterMB != 500 || *got[1].HeapRetainedMB != 180 {
		t.Fatalf("build 2 retention=%+v", got[1])
	}

	// Missing heap samples keep RSS retention while leaving heap fields nil (never 0).
	rssOnly := []model.Process{
		{PID: 42, StartTimeMs: 1_000, SampledAtMs: 90_000, RSSMemoryMB: 500},
		{PID: 42, StartTimeMs: 1_000, SampledAtMs: 150_000, RSSMemoryMB: 800},
		{PID: 42, StartTimeMs: 1_000, SampledAtMs: 240_000, RSSMemoryMB: 620},
	}
	gotNoHeap := AnalyzeBuildRetention(rssOnly, []Build{{ID: "no-heap", PID: 42, StartTimeMs: 100_000, EndTimeMs: &end1}}, 42, 1_000)
	if len(gotNoHeap) != 1 || gotNoHeap[0].RetainedMB != 120 || gotNoHeap[0].HeapBeforeMB != nil || gotNoHeap[0].HeapRetainedMB != nil {
		t.Fatalf("no-heap retention=%+v", gotNoHeap)
	}
}
