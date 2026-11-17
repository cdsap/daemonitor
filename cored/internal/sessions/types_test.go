package sessions_test

import (
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/sessions"
)

func TestSummarizeRSS(t *testing.T) {
	heap10, heap40, heap25 := int64(10), int64(40), int64(25)
	gc1, gc3 := int64(1), int64(3)
	got, ok := sessions.SummarizeRSS(
		sessions.ProcessRef{HostID: "h", PID: 7, StartTimeMs: 11, ProcessType: "KOTLIN_DAEMON"},
		sessions.AssociationConfirmed,
		[]sessions.Sample{
			{AtMs: 3, RSS: 20, HeapUsedMB: &heap25, YoungGCCount: &gc3, TotalGCCount: &gc3},
			{AtMs: 1, RSS: 10, HeapUsedMB: &heap10, YoungGCCount: &gc1, TotalGCCount: &gc1},
			{AtMs: 2, RSS: 30, HeapUsedMB: &heap40, YoungGCCount: &gc1, TotalGCCount: &gc1},
		},
	)
	if !ok || got.FirstRSSMB != 10 || got.PeakRSSMB != 30 || got.LastRSSMB != 20 || got.DeltaRSSMB != 10 || got.SampleCount != 3 || got.ProcessType != "KOTLIN_DAEMON" {
		t.Fatalf("rss=%+v ok=%v", got, ok)
	}
	if got.FirstSampleAtMs != 1 || got.LastSampleAtMs != 3 {
		t.Fatalf("sample window=%+v", got)
	}
	if got.FirstHeapUsedMB == nil || *got.FirstHeapUsedMB != 10 || got.PeakHeapUsedMB == nil || *got.PeakHeapUsedMB != 40 ||
		got.LastHeapUsedMB == nil || *got.LastHeapUsedMB != 25 || got.DeltaHeapUsedMB == nil || *got.DeltaHeapUsedMB != 15 {
		t.Fatalf("heap=%+v", got)
	}
	if got.FirstYoungGCCount == nil || *got.FirstYoungGCCount != 1 || got.LastYoungGCCount == nil || *got.LastYoungGCCount != 3 ||
		got.DeltaYoungGCCount == nil || *got.DeltaYoungGCCount != 2 {
		t.Fatalf("gc=%+v", got)
	}
}
