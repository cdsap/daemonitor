package analysis

import (
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestAnalyzeMemoryGrowth(t *testing.T) {
	const start = int64(10_000)
	cases := []struct {
		name       string
		rss        []int64
		heap       []int64
		want       Assessment
		wantHeap   bool
		wantGrowth int64
	}{
		{"stable", []int64{100, 105, 98, 103, 101, 104}, []int64{50, 51, 49, 52, 50, 51}, Stable, true, 4},
		{"growing", []int64{100, 180, 260, 340, 420, 500}, []int64{50, 80, 120, 160, 210, 260}, Growing, true, 320},
		{"high growth", []int64{100, 350, 700, 1100, 1500, 1900}, nil, HighGrowth, false, 1550},
		{"spike recovery", []int64{100, 110, 120, 900, 115, 108}, nil, Stable, false, -2},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			samples := make([]model.Process, len(tc.rss))
			for i, rss := range tc.rss {
				var heap *int64
				if i < len(tc.heap) {
					value := tc.heap[i]
					heap = &value
				}
				samples[i] = model.Process{PID: 7, StartTimeMs: start, SampledAtMs: int64(i+1) * 30_000, RSSMemoryMB: rss, HeapUsedMB: heap}
			}
			got := Analyze(samples, 7, start)
			if got.Assessment != tc.want {
				t.Fatalf("assessment=%s want %s", got.Assessment, tc.want)
			}
			if got.RSS.AbsoluteGrowthMB != tc.wantGrowth {
				t.Fatalf("rss growth=%d want %d", got.RSS.AbsoluteGrowthMB, tc.wantGrowth)
			}
			if got.Heap.Available != tc.wantHeap {
				t.Fatalf("heap available=%v want %v", got.Heap.Available, tc.wantHeap)
			}
		})
	}
}

func TestAnalyzeMemoryGrowthRejectsInvalidAndOtherIncarnations(t *testing.T) {
	samples := []model.Process{
		{PID: 7, StartTimeMs: 99, SampledAtMs: 0, RSSMemoryMB: 10},
		{PID: 7, StartTimeMs: 100, SampledAtMs: 1_000, RSSMemoryMB: 100},
		{PID: 8, StartTimeMs: 100, SampledAtMs: 2_000, RSSMemoryMB: 900},
		{PID: 7, StartTimeMs: 100, SampledAtMs: 3_000, RSSMemoryMB: -1},
	}
	got := Analyze(samples, 7, 100)
	if got.Assessment != InsufficientData || got.SampleCount != 1 {
		t.Fatalf("got=%+v, want insufficient data for short valid history", got)
	}
}

func TestAnalyzeMemoryGrowthRequiresElapsedWindow(t *testing.T) {
	var samples []model.Process
	for i := 0; i < 8; i++ {
		samples = append(samples, model.Process{PID: 7, StartTimeMs: 100, SampledAtMs: int64(i) * 1_000, RSSMemoryMB: int64(100 + i*100)})
	}
	if got := Analyze(samples, 7, 100); got.Assessment != InsufficientData {
		t.Fatalf("assessment=%s want %s", got.Assessment, InsufficientData)
	}
}
