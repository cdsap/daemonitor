//go:build darwin

package platform

import "context"

type darwinCollector struct{}

// New returns the macOS platform collector.
func New() PlatformCollector { return darwinCollector{} }

func (darwinCollector) CollectProcess(ctx context.Context, process ProcessIdentity) ProcessPlatformMetrics {
	return collectProcessGopsutil(ctx, process)
}

func (darwinCollector) CollectHost(ctx context.Context) HostMetrics {
	return collectHostGopsutil(ctx)
}

func (darwinCollector) Capabilities() []MetricCapability {
	caps := baseCapabilities()
	setCapability(caps, "process.memory.physical_footprint_bytes", NotCollected, "proc_pid_rusage",
		"macOS ri_phys_footprint (native collection pending)")
	setCapability(caps, "process.io.disk_read_bytes_total", NotCollected, "proc_pid_rusage",
		"macOS disk I/O via rusage (native collection pending)")
	setCapability(caps, "process.io.disk_write_bytes_total", NotCollected, "proc_pid_rusage",
		"macOS disk I/O via rusage (native collection pending)")
	setCapability(caps, "process.handles.count", Unsupported, "", "")
	return caps
}
