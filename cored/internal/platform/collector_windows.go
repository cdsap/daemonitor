//go:build windows

package platform

import "context"

type windowsCollector struct{}

// New returns the Windows platform collector.
func New() PlatformCollector { return windowsCollector{} }

func (windowsCollector) CollectProcess(ctx context.Context, process ProcessIdentity) ProcessPlatformMetrics {
	return normalizeWindowsProcessMetrics(collectProcessGopsutil(ctx, process))
}

func (windowsCollector) CollectHost(ctx context.Context) HostMetrics {
	out := collectHostGopsutil(ctx)
	used, limit := collectWindowsCommit(out.SampledAtMs)
	out.CommitUsedBytes, out.CommitLimitBytes = used, limit
	out.Resource.CommitUsedBytes, out.Resource.CommitLimitBytes = used, limit
	return out
}

func (windowsCollector) Capabilities() []MetricCapability {
	caps := baseCapabilities()
	setCapability(caps, "job.cpu.limit", NotCollected, "Windows Job Object", "Read-only query for an existing target Job Object; no job is created")
	setCapability(caps, "job.cpu.time_seconds_total", NotCollected, "Windows Job Object", "Read-only query for an existing target Job Object; no job is created")
	setCapability(caps, "job.memory.limit_bytes", NotCollected, "Windows Job Object", "Read-only query for an existing target Job Object; no job is created")
	setCapability(caps, "job.memory.usage_bytes", NotCollected, "Windows Job Object", "Read-only query for an existing target Job Object; no job is created")
	setCapability(caps, "job.process_count", NotCollected, "Windows Job Object", "Read-only query for an existing target Job Object; no job is created")
	setCapability(caps, "host.memory.commit_used_bytes", Available, "GlobalMemoryStatusEx", "Windows system commit usage")
	setCapability(caps, "host.memory.commit_limit_bytes", Available, "GlobalMemoryStatusEx", "Windows system commit limit")
	setCapability(caps, "process.memory.private_committed_bytes", NotCollected, "GetProcessMemoryInfo", "")
	setCapability(caps, "process.memory.peak_resident_bytes", NotCollected, "GetProcessMemoryInfo", "")
	setCapability(caps, "process.handles.count", Available, sourceGopsutil,
		"Windows handle count via GetProcessHandleCount; it is not a POSIX FD count")
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
