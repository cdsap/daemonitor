package platform

import (
	"context"
	"runtime"
	"strings"
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/mem"
	"github.com/shirou/gopsutil/v4/process"
)

const sourceGopsutil = "gopsutil"

// collectProcessGopsutil gathers portable process metrics via gopsutil.
// Platform-specific fields that gopsutil does not expose remain Unsupported.
func collectProcessGopsutil(ctx context.Context, id ProcessIdentity) ProcessPlatformMetrics {
	now := time.Now().UnixMilli()
	out := ProcessPlatformMetrics{
		Identity:    id,
		SampledAtMs: now,
	}
	markUnsupportedPlatformExtras(&out)

	p, err := process.NewProcessWithContext(ctx, id.PID)
	if err != nil {
		failProcess(&out, ProbeFailed)
		return out
	}

	if create, err := p.CreateTimeWithContext(ctx); err == nil && create > 0 {
		if id.CreateTimeMs > 0 && abs64(create-id.CreateTimeMs) > 1000 {
			failProcess(&out, Stale)
			out.Identity.CreateTimeMs = create
			return out
		}
		out.Identity.CreateTimeMs = create
	}

	if memInfo, err := p.MemoryInfoWithContext(ctx); err == nil && memInfo != nil {
		out.ResidentBytes = IntAvailable(int64(memInfo.RSS), now, sourceGopsutil)
		out.VirtualBytes = IntAvailable(int64(memInfo.VMS), now, sourceGopsutil)
		out.SwapBytes = IntAvailable(int64(memInfo.Swap), now, sourceGopsutil)
	} else {
		out.ResidentBytes = IntUnavailable(ProbeFailed, sourceGopsutil)
		out.VirtualBytes = IntUnavailable(ProbeFailed, sourceGopsutil)
		out.SwapBytes = IntUnavailable(ProbeFailed, sourceGopsutil)
	}

	if v, err := p.NumThreadsWithContext(ctx); err == nil {
		out.ThreadCount = IntAvailable(int64(v), now, sourceGopsutil)
	} else {
		out.ThreadCount = IntUnavailable(ProbeFailed, sourceGopsutil)
	}

	if io, err := p.IOCountersWithContext(ctx); err == nil && io != nil {
		out.LogicalReadBytesTotal, out.LogicalWriteBytesTotal,
			out.DiskReadBytesTotal, out.DiskWriteBytesTotal,
			out.ReadOperationsTotal, out.WriteOperationsTotal = ioMetricsFromCounters(*io, now)
	} else {
		a := mapOptionalErr(err)
		out.LogicalReadBytesTotal = IntUnavailable(a, sourceGopsutil)
		out.LogicalWriteBytesTotal = IntUnavailable(a, sourceGopsutil)
		out.ReadOperationsTotal = IntUnavailable(a, sourceGopsutil)
		out.WriteOperationsTotal = IntUnavailable(a, sourceGopsutil)
	}
	if out.DiskReadBytesTotal.Availability == "" {
		out.DiskReadBytesTotal = IntUnavailable(Unsupported, sourceGopsutil)
		out.DiskWriteBytesTotal = IntUnavailable(Unsupported, sourceGopsutil)
	}

	if faults, err := p.PageFaultsWithContext(ctx); err == nil && faults != nil {
		out.MinorPageFaultsTotal = IntAvailable(int64(faults.MinorFaults), now, sourceGopsutil)
		out.MajorPageFaultsTotal = IntAvailable(int64(faults.MajorFaults), now, sourceGopsutil)
		total := int64(faults.MinorFaults + faults.MajorFaults)
		out.PageFaultsTotal = IntAvailable(total, now, sourceGopsutil)
	} else {
		a := mapOptionalErr(err)
		out.MinorPageFaultsTotal = IntUnavailable(a, sourceGopsutil)
		out.MajorPageFaultsTotal = IntUnavailable(a, sourceGopsutil)
		out.PageFaultsTotal = IntUnavailable(a, sourceGopsutil)
	}

	if switches, err := p.NumCtxSwitchesWithContext(ctx); err == nil && switches != nil {
		out.VoluntaryContextSwitchesTotal = IntAvailable(switches.Voluntary, now, sourceGopsutil)
		out.InvoluntaryContextSwitchesTotal = IntAvailable(switches.Involuntary, now, sourceGopsutil)
	} else {
		a := mapOptionalErr(err)
		out.VoluntaryContextSwitchesTotal = IntUnavailable(a, sourceGopsutil)
		out.InvoluntaryContextSwitchesTotal = IntUnavailable(a, sourceGopsutil)
	}

	if v, err := p.NumFDsWithContext(ctx); err == nil {
		out.OpenDescriptorsCount = IntAvailable(int64(v), now, sourceGopsutil)
	} else {
		out.OpenDescriptorsCount = IntUnavailable(mapOptionalErr(err), sourceGopsutil)
	}

	if times, err := p.TimesWithContext(ctx); err == nil && times != nil {
		out.CPUUserSecondsTotal = RealAvailable(times.User, now, sourceGopsutil)
		out.CPUSystemSecondsTotal = RealAvailable(times.System, now, sourceGopsutil)
		out.CPUTotalSecondsTotal = RealAvailable(times.User+times.System, now, sourceGopsutil)
	} else {
		a := mapOptionalErr(err)
		out.CPUUserSecondsTotal = RealUnavailable(a, sourceGopsutil)
		out.CPUSystemSecondsTotal = RealUnavailable(a, sourceGopsutil)
		out.CPUTotalSecondsTotal = RealUnavailable(a, sourceGopsutil)
	}

	return out
}

func ioMetricsFromCounters(io process.IOCountersStat, sampledAtMs int64) (IntMetric, IntMetric, IntMetric, IntMetric, IntMetric, IntMetric) {
	return IntAvailable(int64(io.ReadBytes), sampledAtMs, sourceGopsutil),
		IntAvailable(int64(io.WriteBytes), sampledAtMs, sourceGopsutil),
		IntAvailable(int64(io.DiskReadBytes), sampledAtMs, sourceGopsutil),
		IntAvailable(int64(io.DiskWriteBytes), sampledAtMs, sourceGopsutil),
		IntAvailable(int64(io.ReadCount), sampledAtMs, sourceGopsutil),
		IntAvailable(int64(io.WriteCount), sampledAtMs, sourceGopsutil)
}

func collectHostGopsutil(ctx context.Context) HostMetrics {
	now := time.Now().UnixMilli()
	out := HostMetrics{SampledAtMs: now}

	n, err := cpu.CountsWithContext(ctx, true)
	if err != nil || n <= 0 {
		n = runtime.NumCPU()
	}
	if n > 0 {
		out.LogicalProcessors = IntAvailable(int64(n), now, sourceGopsutil)
	} else {
		out.LogicalProcessors = IntUnavailable(ProbeFailed, sourceGopsutil)
	}

	out.CPUUsagePercent = RealUnavailable(NotCollected, sourceGopsutil)

	if vm, err := mem.VirtualMemoryWithContext(ctx); err == nil && vm != nil {
		out.MemoryTotalBytes = IntAvailable(int64(vm.Total), now, sourceGopsutil)
		out.MemoryAvailableBytes = IntAvailable(int64(vm.Available), now, sourceGopsutil)
		out.MemoryUsedBytes = IntAvailable(int64(vm.Used), now, sourceGopsutil)
	} else {
		a := mapOptionalErr(err)
		out.MemoryTotalBytes = IntUnavailable(a, sourceGopsutil)
		out.MemoryAvailableBytes = IntUnavailable(a, sourceGopsutil)
		out.MemoryUsedBytes = IntUnavailable(a, sourceGopsutil)
	}
	return out
}

func baseCapabilities() []MetricCapability {
	plat := runtime.GOOS
	cap := func(name, unit, desc string, a Availability) MetricCapability {
		return MetricCapability{
			MetricName: name, Availability: a, Unit: unit,
			Source: sourceGopsutil, Platform: plat, Description: desc,
		}
	}
	return []MetricCapability{
		cap("process.memory.resident_bytes", "bytes", "Resident / working-set memory", Available),
		cap("process.memory.virtual_bytes", "bytes", "Virtual address space", Available),
		cap("process.memory.swap_bytes", "bytes", "Process swap / pagefile association when exposed", Available),
		cap("process.memory.physical_footprint_bytes", "bytes", "macOS physical footprint", Unsupported),
		cap("process.memory.proportional_bytes", "bytes", "Linux PSS", Unsupported),
		cap("process.memory.private_committed_bytes", "bytes", "Private committed memory", Unsupported),
		cap("process.memory.peak_resident_bytes", "bytes", "Peak resident / working set", Unsupported),
		cap("process.threads.count", "count", "Thread count", Available),
		cap("process.io.logical_read_bytes_total", "bytes", "Logical read bytes", Available),
		cap("process.io.logical_write_bytes_total", "bytes", "Logical write bytes", Available),
		cap("process.io.disk_read_bytes_total", "bytes", "Storage read bytes", Unsupported),
		cap("process.io.disk_write_bytes_total", "bytes", "Storage write bytes", Unsupported),
		cap("process.io.read_operations_total", "count", "Read operations", Available),
		cap("process.io.write_operations_total", "count", "Write operations", Available),
		cap("process.page_faults.minor_total", "count", "Minor page faults", Available),
		cap("process.page_faults.major_total", "count", "Major page faults", Available),
		cap("process.context_switches.voluntary_total", "count", "Voluntary context switches", Available),
		cap("process.context_switches.involuntary_total", "count", "Involuntary context switches", Available),
		cap("process.open_descriptors.count", "count", "Open file descriptors", Available),
		cap("process.handles.count", "count", "Windows handle count", Unsupported),
		cap("process.cpu.user_seconds_total", "seconds", "Cumulative user CPU time", Available),
		cap("process.cpu.system_seconds_total", "seconds", "Cumulative system CPU time", Available),
		cap("process.cpu.total_seconds_total", "seconds", "Cumulative total CPU time", Available),
		cap("host.cpu.logical_processors", "count", "Logical host processors", Available),
		cap("host.cpu.usage_percent", "percent", "Host CPU usage percent", NotCollected),
		cap("host.memory.total_bytes", "bytes", "Host memory total", Available),
		cap("host.memory.available_bytes", "bytes", "Host memory available", Available),
		cap("host.memory.used_bytes", "bytes", "Host memory used", Available),
	}
}

func markUnsupportedPlatformExtras(out *ProcessPlatformMetrics) {
	out.PhysicalFootprintBytes = IntUnavailable(Unsupported, sourceGopsutil)
	out.ProportionalBytes = IntUnavailable(Unsupported, sourceGopsutil)
	out.PrivateCommittedBytes = IntUnavailable(Unsupported, sourceGopsutil)
	out.PeakResidentBytes = IntUnavailable(Unsupported, sourceGopsutil)
	out.HandlesCount = IntUnavailable(Unsupported, sourceGopsutil)
}

func failProcess(out *ProcessPlatformMetrics, a Availability) {
	out.ResidentBytes = IntUnavailable(a, sourceGopsutil)
	out.VirtualBytes = IntUnavailable(a, sourceGopsutil)
	out.SwapBytes = IntUnavailable(a, sourceGopsutil)
	out.ThreadCount = IntUnavailable(a, sourceGopsutil)
	out.LogicalReadBytesTotal = IntUnavailable(a, sourceGopsutil)
	out.LogicalWriteBytesTotal = IntUnavailable(a, sourceGopsutil)
	out.DiskReadBytesTotal = IntUnavailable(Unsupported, sourceGopsutil)
	out.DiskWriteBytesTotal = IntUnavailable(Unsupported, sourceGopsutil)
	out.ReadOperationsTotal = IntUnavailable(a, sourceGopsutil)
	out.WriteOperationsTotal = IntUnavailable(a, sourceGopsutil)
	out.MinorPageFaultsTotal = IntUnavailable(a, sourceGopsutil)
	out.MajorPageFaultsTotal = IntUnavailable(a, sourceGopsutil)
	out.PageFaultsTotal = IntUnavailable(a, sourceGopsutil)
	out.VoluntaryContextSwitchesTotal = IntUnavailable(a, sourceGopsutil)
	out.InvoluntaryContextSwitchesTotal = IntUnavailable(a, sourceGopsutil)
	out.OpenDescriptorsCount = IntUnavailable(a, sourceGopsutil)
	out.CPUUserSecondsTotal = RealUnavailable(a, sourceGopsutil)
	out.CPUSystemSecondsTotal = RealUnavailable(a, sourceGopsutil)
	out.CPUTotalSecondsTotal = RealUnavailable(a, sourceGopsutil)
}

func mapOptionalErr(err error) Availability {
	if err == nil {
		return ProbeFailed
	}
	msg := err.Error()
	if strings.Contains(strings.ToLower(msg), "not implement") || strings.Contains(strings.ToLower(msg), "unsupported") {
		return Unsupported
	}
	if strings.Contains(strings.ToLower(msg), "permission") || strings.Contains(strings.ToLower(msg), "access denied") {
		return PermissionDenied
	}
	return ProbeFailed
}

func abs64(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

func setCapability(caps []MetricCapability, name string, a Availability, source, desc string) {
	for i := range caps {
		if caps[i].MetricName == name {
			caps[i].Availability = a
			if source != "" {
				caps[i].Source = source
			}
			if desc != "" {
				caps[i].Description = desc
			}
			return
		}
	}
}
