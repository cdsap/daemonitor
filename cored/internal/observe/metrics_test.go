package observe_test

import (
	"errors"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/observe"
)

func TestObservePollOverrun(t *testing.T) {
	m := &observe.Metrics{}
	m.ObservePoll(3*time.Second, 2*time.Second)
	snap := m.Snapshot()
	if snap.PollDurationMs < 3000 {
		t.Fatalf("duration=%d", snap.PollDurationMs)
	}
	if snap.PollOverrunTotal != 1 {
		t.Fatalf("overrun=%d", snap.PollOverrunTotal)
	}
}

func TestObserveProbeCategories(t *testing.T) {
	m := &observe.Metrics{}
	m.ObserveProbe(10*time.Millisecond, nil, false)
	m.ObserveProbe(20*time.Millisecond, errors.New("boom"), false)
	m.ObserveProbe(30*time.Millisecond, errors.New("timeout"), true)
	snap := m.Snapshot()
	if snap.ProbeSuccessTotal != 1 || snap.ProbeFailureTotal != 2 || snap.ProbeTimeoutTotal != 1 {
		t.Fatalf("%+v", snap)
	}
}
