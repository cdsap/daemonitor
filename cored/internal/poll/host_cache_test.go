package poll

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/platform"
)

type countingHostCollector struct {
	calls atomic.Int64
}

func (c *countingHostCollector) CollectProcess(_ context.Context, id platform.ProcessIdentity) platform.ProcessPlatformMetrics {
	return platform.ProcessPlatformMetrics{Identity: id}
}

func (c *countingHostCollector) CollectHost(_ context.Context) platform.HostMetrics {
	n := c.calls.Add(1)
	return platform.HostMetrics{SampledAtMs: n * 1000}
}

func (c *countingHostCollector) Capabilities() []platform.MetricCapability { return nil }

func TestCollectorHostSnapshotCachesWithinCadence(t *testing.T) {
	stub := &countingHostCollector{}
	c := NewCollector()
	defer c.Close()
	c.Platform = stub
	c.Cadences.Host = 5 * time.Second

	base := time.Unix(1700000000, 0)
	h1 := c.hostSnapshot(context.Background(), base)
	h2 := c.hostSnapshot(context.Background(), base.Add(2*time.Second))
	if got := stub.calls.Load(); got != 1 {
		t.Fatalf("CollectHost calls=%d want 1 within cadence", got)
	}
	if h1.SampledAtMs != h2.SampledAtMs {
		t.Fatalf("cached host changed: %d vs %d", h1.SampledAtMs, h2.SampledAtMs)
	}

	h3 := c.hostSnapshot(context.Background(), base.Add(5*time.Second))
	if got := stub.calls.Load(); got != 2 {
		t.Fatalf("CollectHost calls=%d want 2 after cadence", got)
	}
	if h3.SampledAtMs != 2000 {
		t.Fatalf("refreshed host SampledAtMs=%d want 2000", h3.SampledAtMs)
	}

	h4 := c.HostMetrics(context.Background())
	if got := stub.calls.Load(); got != 3 || h4.SampledAtMs != 3000 {
		t.Fatalf("HostMetrics calls=%d sampled=%d", got, h4.SampledAtMs)
	}
}
