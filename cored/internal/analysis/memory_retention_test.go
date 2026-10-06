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
