package poll

import "testing"

func TestCPUPriorsKeyedByIncarnationNotPIDAlone(t *testing.T) {
	c := &Collector{
		priors: make(map[incarnationKey]priorCPU),
		cpus:   1,
	}
	first := incarnationKey{pid: 42, createTimeMs: 1000}
	second := incarnationKey{pid: 42, createTimeMs: 2000}
	c.priors[first] = priorCPU{cpuTimeMs: 500, wallClockMs: 100}
	c.priors[second] = priorCPU{cpuTimeMs: 10, wallClockMs: 100}

	if len(c.priors) != 2 {
		t.Fatalf("priors=%d want 2 distinct incarnations", len(c.priors))
	}
	if c.priors[first].cpuTimeMs == c.priors[second].cpuTimeMs {
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
