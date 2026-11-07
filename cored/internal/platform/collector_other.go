//go:build !darwin && !linux && !windows

package platform

import (
	"context"
	"runtime"
)

type unsupportedCollector struct{}

// New returns a stub collector for unsupported operating systems.
func New() PlatformCollector { return unsupportedCollector{} }

func (unsupportedCollector) CollectProcess(_ context.Context, id ProcessIdentity) ProcessPlatformMetrics {
	m := ProcessPlatformMetrics{Identity: id}
	m.ResidentBytes = IntUnavailable(Unsupported, runtime.GOOS)
	m.VirtualBytes = IntUnavailable(Unsupported, runtime.GOOS)
	m.SwapBytes = IntUnavailable(Unsupported, runtime.GOOS)
	m.PhysicalFootprintBytes = IntUnavailable(Unsupported, runtime.GOOS)
	m.ProportionalBytes = IntUnavailable(Unsupported, runtime.GOOS)
	m.PrivateCommittedBytes = IntUnavailable(Unsupported, runtime.GOOS)
	m.PeakResidentBytes = IntUnavailable(Unsupported, runtime.GOOS)
	m.ThreadCount = IntUnavailable(Unsupported, runtime.GOOS)
	m.LogicalReadBytesTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.LogicalWriteBytesTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.DiskReadBytesTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.DiskWriteBytesTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.ReadOperationsTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.WriteOperationsTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.MinorPageFaultsTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.MajorPageFaultsTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.PageFaultsTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.VoluntaryContextSwitchesTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.InvoluntaryContextSwitchesTotal = IntUnavailable(Unsupported, runtime.GOOS)
	m.OpenDescriptorsCount = IntUnavailable(Unsupported, runtime.GOOS)
	m.HandlesCount = IntUnavailable(Unsupported, runtime.GOOS)
	m.CPUUserSecondsTotal = RealUnavailable(Unsupported, runtime.GOOS)
	m.CPUSystemSecondsTotal = RealUnavailable(Unsupported, runtime.GOOS)
	m.CPUTotalSecondsTotal = RealUnavailable(Unsupported, runtime.GOOS)
	return m
}

func (unsupportedCollector) CollectHost(_ context.Context) HostMetrics {
	return HostMetrics{
		Scope:                ScopeHost,
		LogicalProcessors:    IntUnavailable(Unsupported, runtime.GOOS),
		CPUUsagePercent:      RealUnavailable(Unsupported, runtime.GOOS),
		MemoryTotalBytes:     IntUnavailable(Unsupported, runtime.GOOS),
		MemoryAvailableBytes: IntUnavailable(Unsupported, runtime.GOOS),
		MemoryUsedBytes:      IntUnavailable(Unsupported, runtime.GOOS),
		Resource:             unavailableResources(ScopeHost, Unsupported, runtime.GOOS),
	}
}

func (unsupportedCollector) Capabilities() []MetricCapability {
	return []MetricCapability{{
		MetricName:   "platform",
		Availability: Unsupported,
		Platform:     runtime.GOOS,
		Description:  "No first-class platform collector for this GOOS",
	}}
}
