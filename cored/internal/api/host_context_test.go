package api

import (
	"context"
	"net/http/httptest"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/platform"
	"github.com/cdsap/daemonitor/cored/internal/poll"
)

type requestHostCollector struct {
	platform.PlatformCollector
	called  bool
	context context.Context
}

func (c *requestHostCollector) CollectHost(ctx context.Context) platform.HostMetrics {
	c.called = true
	c.context = ctx
	return platform.HostMetrics{}
}

func TestHandleHostUsesRequestContextAndCachedSample(t *testing.T) {
	c := &requestHostCollector{}
	s := &Server{Collector: &poll.Collector{Platform: c}}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	req := httptest.NewRequest("GET", "/v1/host", nil).WithContext(ctx)
	s.handleHost(httptest.NewRecorder(), req)
	if !c.called || c.context != ctx || c.context.Err() != context.Canceled {
		t.Fatal("host collection lost request context")
	}
	s.refreshHost(t.Context())
	c.called = false
	s.handleHost(httptest.NewRecorder(), req)
	if c.called {
		t.Fatal("cached host sample triggered another collection")
	}
}
