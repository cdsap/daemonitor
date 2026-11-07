package platform

import "context"

// ProcessIdentity uniquely identifies a process incarnation for counter series.
// PID alone is insufficient because PIDs are reused.
type ProcessIdentity struct {
	PID          int32  `json:"pid"`
	CreateTimeMs int64  `json:"create_time_ms"`
	HostID       string `json:"host_id,omitempty"`
}

// MetricCapability describes whether a named metric can be collected on this host.
type MetricCapability struct {
	MetricName   string       `json:"metric_name"`
	Availability Availability `json:"availability"`
	Unit         string       `json:"unit,omitempty"`
	Source       string       `json:"source,omitempty"`
	Platform     string       `json:"platform,omitempty"`
	Description  string       `json:"description,omitempty"`
}

// ResourceScope identifies the accounting domain for a metric.
type ResourceScope string

const (
	ScopeHost        ResourceScope = "host"
	ScopeProcess     ResourceScope = "process"
	ScopeLinuxCgroup ResourceScope = "linux_cgroup"
	ScopeWindowsJob  ResourceScope = "windows_job"
)

// IntMetric is an optional integer measurement with availability and sample time.
type IntMetric struct {
	Availability Availability `json:"availability"`
	Value        *int64       `json:"value"`
	SampledAtMs  int64        `json:"sampled_at_ms,omitempty"`
	Source       string       `json:"source,omitempty"`
}

// RealMetric is an optional floating-point measurement with availability and sample time.
type RealMetric struct {
	Availability Availability `json:"availability"`
	Value        *float64     `json:"value"`
	SampledAtMs  int64        `json:"sampled_at_ms,omitempty"`
	Source       string       `json:"source,omitempty"`
}

// ProcessPlatformMetrics are OS-level measurements for one process incarnation.
// Fields that a platform cannot supply must use Unsupported (or another non-available
// state) with a null value — never a fabricated zero.
type ProcessPlatformMetrics struct {
	Identity      ProcessIdentity `json:"identity"`
	SampledAtMs   int64           `json:"sampled_at_ms"`
	ResourceScope ResourceScope   `json:"resource_scope"`
	Resource      ResourceMetrics `json:"resource,omitempty"`

	ResidentBytes IntMetric `json:"resident_bytes"`
	VirtualBytes  IntMetric `json:"virtual_bytes"`
	SwapBytes     IntMetric `json:"swap_bytes"`

	// Platform-specific memory. Unsupported on OSes that lack an equivalent.
	PhysicalFootprintBytes IntMetric `json:"physical_footprint_bytes"`
	ProportionalBytes      IntMetric `json:"proportional_bytes"`
	PrivateCommittedBytes  IntMetric `json:"private_committed_bytes"`
	PeakResidentBytes      IntMetric `json:"peak_resident_bytes"`

	ThreadCount IntMetric `json:"thread_count"`

	// I/O: only populate families the platform actually exposes.
	LogicalReadBytesTotal  IntMetric `json:"logical_read_bytes_total"`
	LogicalWriteBytesTotal IntMetric `json:"logical_write_bytes_total"`
	DiskReadBytesTotal     IntMetric `json:"disk_read_bytes_total"`
	DiskWriteBytesTotal    IntMetric `json:"disk_write_bytes_total"`
	ReadOperationsTotal    IntMetric `json:"read_operations_total"`
	WriteOperationsTotal   IntMetric `json:"write_operations_total"`

	MinorPageFaultsTotal IntMetric `json:"minor_page_faults_total"`
	MajorPageFaultsTotal IntMetric `json:"major_page_faults_total"`
	PageFaultsTotal      IntMetric `json:"page_faults_total"`

	VoluntaryContextSwitchesTotal   IntMetric `json:"voluntary_context_switches_total"`
	InvoluntaryContextSwitchesTotal IntMetric `json:"involuntary_context_switches_total"`

	OpenDescriptorsCount IntMetric `json:"open_descriptors_count"`
	HandlesCount         IntMetric `json:"handles_count"`

	CPUUserSecondsTotal   RealMetric `json:"cpu_user_seconds_total"`
	CPUSystemSecondsTotal RealMetric `json:"cpu_system_seconds_total"`
	CPUTotalSecondsTotal  RealMetric `json:"cpu_total_seconds_total"`
}

// ResourceMetrics contains accounting available for the process's resource
// scope. Optional values are null when the scope is absent or unsupported.
type ResourceMetrics struct {
	Scope ResourceScope `json:"scope"`

	CPUQuotaCores            RealMetric `json:"cpu_quota_cores"`
	CPUUsageSecondsTotal     RealMetric `json:"cpu_usage_seconds_total"`
	CPUThrottledSecondsTotal RealMetric `json:"cpu_throttled_seconds_total"`
	MemoryCurrentBytes       IntMetric  `json:"memory_current_bytes"`
	MemoryLimitBytes         IntMetric  `json:"memory_limit_bytes"`
	MemoryOOMTotal           IntMetric  `json:"memory_oom_total"`
	MemoryOOMKillTotal       IntMetric  `json:"memory_oom_kill_total"`

	CommitUsedBytes        IntMetric  `json:"commit_used_bytes"`
	CommitLimitBytes       IntMetric  `json:"commit_limit_bytes"`
	JobCPUTimeSecondsTotal RealMetric `json:"job_cpu_time_seconds_total"`
	JobMemoryUsageBytes    IntMetric  `json:"job_memory_usage_bytes"`
	JobProcessCount        IntMetric  `json:"job_process_count"`
}

// HostMetrics are machine-level measurements independent of a target process.
type HostMetrics struct {
	SampledAtMs          int64           `json:"sampled_at_ms"`
	Scope                ResourceScope   `json:"scope"`
	LogicalProcessors    IntMetric       `json:"logical_processors"`
	CPUUsagePercent      RealMetric      `json:"cpu_usage_percent"`
	MemoryTotalBytes     IntMetric       `json:"memory_total_bytes"`
	MemoryAvailableBytes IntMetric       `json:"memory_available_bytes"`
	MemoryUsedBytes      IntMetric       `json:"memory_used_bytes"`
	CommitUsedBytes      IntMetric       `json:"commit_used_bytes"`
	CommitLimitBytes     IntMetric       `json:"commit_limit_bytes"`
	Load1                RealMetric      `json:"load1"`
	Load5                RealMetric      `json:"load5"`
	Load15               RealMetric      `json:"load15"`
	PSICPU               SomePSI         `json:"psi_cpu"`
	PSIMemory            SomePSI         `json:"psi_memory"`
	PSIIO                SomePSI         `json:"psi_io"`
	Resource             ResourceMetrics `json:"resource"`
}

// SomePSI contains Linux pressure-stall "some" and optional "full" values.
type SomePSI struct {
	SomeAvg10  RealMetric `json:"some_avg10"`
	SomeAvg60  RealMetric `json:"some_avg60"`
	SomeAvg300 RealMetric `json:"some_avg300"`
	FullAvg10  RealMetric `json:"full_avg10"`
	FullAvg60  RealMetric `json:"full_avg60"`
	FullAvg300 RealMetric `json:"full_avg300"`
}

// PlatformCollector is the OS-specific adapter for process and host telemetry.
type PlatformCollector interface {
	CollectProcess(ctx context.Context, process ProcessIdentity) ProcessPlatformMetrics
	CollectHost(ctx context.Context) HostMetrics
	Capabilities() []MetricCapability
}

// IntAvailable builds an available integer metric.
func IntAvailable(v int64, sampledAtMs int64, source string) IntMetric {
	return IntMetric{Availability: Available, Value: &v, SampledAtMs: sampledAtMs, Source: source}
}

// IntUnavailable builds a null integer metric with the given availability.
func IntUnavailable(a Availability, source string) IntMetric {
	if a == "" || a == Available {
		a = NotCollected
	}
	return IntMetric{Availability: a, Source: source}
}

// RealAvailable builds an available floating-point metric.
func RealAvailable(v float64, sampledAtMs int64, source string) RealMetric {
	return RealMetric{Availability: Available, Value: &v, SampledAtMs: sampledAtMs, Source: source}
}

// RealUnavailable builds a null floating-point metric with the given availability.
func RealUnavailable(a Availability, source string) RealMetric {
	if a == "" || a == Available {
		a = NotCollected
	}
	return RealMetric{Availability: a, Source: source}
}

func unavailableResources(scope ResourceScope, a Availability, source string) ResourceMetrics {
	return ResourceMetrics{Scope: scope,
		CPUQuotaCores: RealUnavailable(a, source), CPUUsageSecondsTotal: RealUnavailable(a, source), CPUThrottledSecondsTotal: RealUnavailable(a, source),
		MemoryCurrentBytes: IntUnavailable(a, source), MemoryLimitBytes: IntUnavailable(a, source), MemoryOOMTotal: IntUnavailable(a, source), MemoryOOMKillTotal: IntUnavailable(a, source),
		CommitUsedBytes: IntUnavailable(a, source), CommitLimitBytes: IntUnavailable(a, source), JobCPUTimeSecondsTotal: RealUnavailable(a, source), JobMemoryUsageBytes: IntUnavailable(a, source), JobProcessCount: IntUnavailable(a, source)}
}

func unavailablePSI(a Availability, source string) SomePSI {
	return SomePSI{SomeAvg10: RealUnavailable(a, source), SomeAvg60: RealUnavailable(a, source), SomeAvg300: RealUnavailable(a, source), FullAvg10: RealUnavailable(a, source), FullAvg60: RealUnavailable(a, source), FullAvg300: RealUnavailable(a, source)}
}
