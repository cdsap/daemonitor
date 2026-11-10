package sessions_test

import (
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/sessions"
)

func TestSummarizeRSS(t *testing.T) {
	got, ok := sessions.SummarizeRSS(sessions.ProcessRef{HostID: "h", PID: 7, StartTimeMs: 11}, sessions.AssociationConfirmed, []sessions.Sample{{AtMs: 3, RSS: 20}, {AtMs: 1, RSS: 10}, {AtMs: 2, RSS: 30}})
	if !ok || got.FirstRSSMB != 10 || got.PeakRSSMB != 30 || got.LastRSSMB != 20 || got.DeltaRSSMB != 10 || got.SampleCount != 3 {
		t.Fatalf("summary=%+v ok=%v", got, ok)
	}
}
