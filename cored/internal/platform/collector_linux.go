//go:build linux

package platform

import "context"

type linuxCollector struct{}

// New returns the Linux platform collector.
func New() PlatformCollector { return linuxCollector{} }

func (linuxCollector) CollectProcess(ctx context.Context, process ProcessIdentity) ProcessPlatformMetrics {
	out := collectProcessGopsutil(ctx, process)
	// Process rows stay scoped as process; cgroup accounting lives on Resource.Scope.
	out.ResourceScope = ScopeProcess
	out.Resource = collectLinuxCgroup(process.PID)
	return out
}

func (linuxCollector) CollectHost(ctx context.Context) HostMetrics {
	out := collectHostGopsutil(ctx)
	now := out.SampledAtMs
	out.PSICPU = collectLinuxPSI("cpu", now)
	out.PSIMemory = collectLinuxPSI("memory", now)
	out.PSIIO = collectLinuxPSI("io", now)
	return out
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
	setCapability(caps, "host.load.1", Available, "procfs", "One-minute load average")
	setCapability(caps, "host.load.5", Available, "procfs", "Five-minute load average")
	setCapability(caps, "host.load.15", Available, "procfs", "Fifteen-minute load average")
	setCapability(caps, "host.psi.cpu.some", Available, "procfs", "CPU pressure some average")
	setCapability(caps, "host.psi.memory.some", Available, "procfs", "Memory pressure some average")
	setCapability(caps, "host.psi.memory.full", Available, "procfs", "Memory pressure full average")
	setCapability(caps, "host.psi.io.some", Available, "procfs", "I/O pressure some average")
	setCapability(caps, "container.cpu.quota_cores", NotCollected, "cgroup_v2", "Resolved from target process cgroup")
	setCapability(caps, "container.cpu.usage_seconds_total", NotCollected, "cgroup_v2", "Resolved from target process cgroup")
	setCapability(caps, "container.cpu.throttled_seconds_total", NotCollected, "cgroup_v2", "Resolved from target process cgroup")
	setCapability(caps, "container.memory.current_bytes", NotCollected, "cgroup_v2", "Resolved from target process cgroup")
	setCapability(caps, "container.memory.limit_bytes", NotCollected, "cgroup_v2", "Resolved from target process cgroup")
	setCapability(caps, "container.memory.events.oom_total", NotCollected, "cgroup_v2", "Resolved from target process cgroup")
	setCapability(caps, "container.memory.events.oom_kill_total", NotCollected, "cgroup_v2", "Resolved from target process cgroup")
	return caps
}
