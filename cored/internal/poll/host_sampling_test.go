package poll

import (
	"context"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/platform"
)

type hostSamplingCollector struct {
	platform.PlatformCollector
	hostCalls int
}

func (p *hostSamplingCollector) CollectHost(ctx context.Context) platform.HostMetrics {
	p.hostCalls++
	return platform.HostMetrics{}
}

func TestProcessSnapshotDoesNotCollectHost(t *testing.T) {
	p := &hostSamplingCollector{PlatformCollector: platform.New()}
	c := NewCollector()
	defer c.Close()
	c.Heap = nil
	c.Platform = p
	if _, err := c.Snapshot(t.Context()); err != nil {
		t.Fatal(err)
	}
	if p.hostCalls != 0 {
		t.Fatalf("process snapshot collected host %d times", p.hostCalls)
	}
	c.HostMetrics(t.Context())
	if p.hostCalls != 1 {
		t.Fatal("explicit host collection did not run")
	}
}
