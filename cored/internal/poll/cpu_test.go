package poll

import (
	"testing"
	"time"
)

func TestCPUUsageKeepsHostPercentDistinctFromCoresUsed(t *testing.T) {
	now := time.Unix(2, 0)
	percent, cores := cpuUsage(now, 8, priorCPU{totalSeconds: 3}, priorCPU{totalSeconds: 1, wallClock: time.Unix(1, 0)}, true)
	if percent == nil || *percent != 25 {
		t.Fatalf("percent=%v want 25", percent)
	}
	if cores == nil || *cores != 2 {
		t.Fatalf("cores=%v want 2", cores)
	}
}

func TestCPUUsageOmitsFirstAndInvalidSamples(t *testing.T) {
	if percent, cores := cpuUsage(time.Unix(2, 0), 8, priorCPU{totalSeconds: 1}, priorCPU{}, false); percent != nil || cores != nil {
		t.Fatalf("first sample percent=%v cores=%v", percent, cores)
	}
	if percent, cores := cpuUsage(time.Unix(2, 0), 8, priorCPU{totalSeconds: 1}, priorCPU{totalSeconds: 2, wallClock: time.Unix(1, 0)}, true); percent != nil || cores != nil {
		t.Fatalf("decreasing counter percent=%v cores=%v", percent, cores)
	}
}

func TestCPUPriorsAreKeyedByProcessIncarnation(t *testing.T) {
	priors := map[incarnationKey]priorCPU{
		{pid: 42, createTimeMs: 1_000}: {totalSeconds: 5},
		{pid: 42, createTimeMs: 2_000}: {totalSeconds: 1},
	}
	if len(priors) != 2 {
		t.Fatalf("priors=%d want 2 distinct incarnations", len(priors))
	}
}
