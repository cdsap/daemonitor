package poll

import (
	"testing"
	"time"
)

func TestCPUPriorsKeyedByIncarnationNotPIDAlone(t *testing.T) {
	c := &Collector{
		priors: make(map[incarnationKey]priorCPU),
		cpus:   1,
	}
	first := incarnationKey{pid: 42, createTimeMs: 1000}
	second := incarnationKey{pid: 42, createTimeMs: 2000}
	now := time.UnixMilli(100)
	c.priors[first] = priorCPU{userSeconds: 0.4, systemSeconds: 0.1, totalSeconds: 0.5, wallClock: now}
	c.priors[second] = priorCPU{userSeconds: 0.01, systemSeconds: 0, totalSeconds: 0.01, wallClock: now}

	if len(c.priors) != 2 {
		t.Fatalf("priors=%d want 2 distinct incarnations", len(c.priors))
	}
	if c.priors[first].totalSeconds == c.priors[second].totalSeconds {
		t.Fatal("PID reuse must not share CPU prior state across create times")
	}

	seen := map[incarnationKey]struct{}{second: {}}
	for key := range c.priors {
		if _, ok := seen[key]; !ok {
			delete(c.priors, key)
		}
	}
	if _, ok := c.priors[first]; ok {
		t.Fatal("evicted incarnation should drop prior")
	}
	if _, ok := c.priors[second]; !ok {
		t.Fatal("live incarnation prior must remain")
	}
}
