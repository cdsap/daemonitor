//go:build darwin

package platform

import "context"

type darwinCollector struct{}

// New returns the macOS platform collector.
func New() PlatformCollector { return darwinCollector{} }

func (darwinCollector) CollectProcess(ctx context.Context, process ProcessIdentity) ProcessPlatformMetrics {
	out := collectProcessGopsutil(ctx, process)
	// macOS gopsutil exposes zero-valued generic I/O fields even though the
	// native process API has disk counters. Do not publish those zeros as
	// logical I/O or let them obscure the native disk measurements.
	out.LogicalReadBytesTotal = IntUnavailable(Unsupported, "macOS does not expose logical process I/O")
	out.LogicalWriteBytesTotal = IntUnavailable(Unsupported, "macOS does not expose logical process I/O")
	rusage := collectDarwinRusage(process.PID, out.SampledAtMs)
	out.DiskReadBytesTotal = rusage.diskRead
	out.DiskWriteBytesTotal = rusage.diskWrite
	out.PhysicalFootprintBytes = rusage.physFootprint
	out.PeakResidentBytes = rusage.peakResident
	return out
}

func (darwinCollector) CollectHost(ctx context.Context) HostMetrics {
	out := collectHostGopsutil(ctx)
	out.MemoryPressureLevel, out.ThermalState = collectDarwinHostContext(out.SampledAtMs)
	return out
}

func (darwinCollector) Capabilities() []MetricCapability {
	caps := baseCapabilities()
	setCapability(caps, "process.memory.physical_footprint_bytes", Available, "proc_pid_rusage",
		"macOS ri_phys_footprint via proc_pid_rusage")
	setCapability(caps, "process.memory.peak_resident_bytes", Available, "proc_pid_rusage",
		"macOS ri_lifetime_max_phys_footprint via proc_pid_rusage")
	setCapability(caps, "process.io.disk_read_bytes_total", Available, "proc_pid_rusage",
		"macOS storage bytes read via proc_pid_rusage")
	setCapability(caps, "process.io.disk_write_bytes_total", Available, "proc_pid_rusage",
		"macOS storage bytes written via proc_pid_rusage")
	setCapability(caps, "process.io.logical_read_bytes_total", Unsupported, "proc_pid_rusage",
		"macOS proc_pid_rusage exposes disk I/O, not logical read bytes")
	setCapability(caps, "process.io.logical_write_bytes_total", Unsupported, "proc_pid_rusage",
		"macOS proc_pid_rusage exposes disk I/O, not logical write bytes")
	setCapability(caps, "process.handles.count", Unsupported, "", "")
	setCapability(caps, "host.memory.pressure_level", Available, "sysctl:kern.memorystatus_vm_pressure_level",
		"macOS VM memory pressure level (normal/warn/critical)")
	setCapability(caps, "host.thermal.state", Available, "notify:thermalpressurelevel",
		"macOS thermal pressure level (nominal/moderate/heavy/trapping/sleeping)")
	return caps
}
