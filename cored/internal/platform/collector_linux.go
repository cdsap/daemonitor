//go:build linux

package platform

import "context"

type linuxCollector struct{}

// New returns the Linux platform collector.
func New() PlatformCollector { return linuxCollector{} }

func (linuxCollector) CollectProcess(ctx context.Context, process ProcessIdentity) ProcessPlatformMetrics {
	return collectProcessGopsutil(ctx, process)
}

func (linuxCollector) CollectHost(ctx context.Context) HostMetrics {
	return collectHostGopsutil(ctx)
}

func (linuxCollector) Capabilities() []MetricCapability {
	caps := baseCapabilities()
	setCapability(caps, "process.memory.proportional_bytes", NotCollected, "smaps_rollup",
		"Linux PSS (slower-cadence collection pending)")
	setCapability(caps, "process.memory.private_committed_bytes", NotCollected, "smaps_rollup", "")
	setCapability(caps, "process.memory.physical_footprint_bytes", Unsupported, "", "")
	setCapability(caps, "process.handles.count", Unsupported, "", "")
	setCapability(caps, "process.io.disk_read_bytes_total", Available, "/proc/<pid>/io",
		"Storage bytes read (read_bytes), distinct from logical rchar")
	setCapability(caps, "process.io.disk_write_bytes_total", Available, "/proc/<pid>/io",
		"Storage bytes written (write_bytes), distinct from logical wchar")
	return caps
}
