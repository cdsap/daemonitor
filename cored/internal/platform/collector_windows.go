//go:build windows

package platform

import "context"

type windowsCollector struct{}

// New returns the Windows platform collector.
func New() PlatformCollector { return windowsCollector{} }

func (windowsCollector) CollectProcess(ctx context.Context, process ProcessIdentity) ProcessPlatformMetrics {
	return collectProcessGopsutil(ctx, process)
}

func (windowsCollector) CollectHost(ctx context.Context) HostMetrics {
	return collectHostGopsutil(ctx)
}

func (windowsCollector) Capabilities() []MetricCapability {
	caps := baseCapabilities()
	setCapability(caps, "process.memory.private_committed_bytes", NotCollected, "GetProcessMemoryInfo", "")
	setCapability(caps, "process.memory.peak_resident_bytes", NotCollected, "GetProcessMemoryInfo", "")
	setCapability(caps, "process.handles.count", NotCollected, "GetProcessHandleCount",
		"Windows handle count (not POSIX FDs)")
	setCapability(caps, "process.open_descriptors.count", Unsupported, "",
		"POSIX open descriptors are not equivalent to Windows handles")
	setCapability(caps, "process.memory.physical_footprint_bytes", Unsupported, "", "")
	setCapability(caps, "process.memory.proportional_bytes", Unsupported, "", "")
	setCapability(caps, "process.page_faults.minor_total", Unsupported, "",
		"Windows exposes total page faults without a reliable minor/major split")
	setCapability(caps, "process.page_faults.major_total", Unsupported, "",
		"Windows exposes total page faults without a reliable minor/major split")
	return caps
}
