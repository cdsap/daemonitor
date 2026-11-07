package model

// Process is an IPC snapshot of a Gradle-related process.
// Field names mirror the Kotlin GradleProcess domain for dual-run clients.
type Process struct {
	PID                        int32    `json:"pid"`
	ParentPID                  int32    `json:"parent_pid"`
	Type                       string   `json:"type"`
	Name                       string   `json:"name"`
	CommandLine                string   `json:"command_line"`
	WorkingDirectory           *string  `json:"working_directory"`
	ProjectPath                *string  `json:"project_path"`
	RSSMemoryMB                int64    `json:"rss_memory_mb"`
	VirtualMemoryMB            *int64   `json:"virtual_memory_mb"`
	SwapMemoryMB               *int64   `json:"swap_memory_mb"`
	ThreadCount                *int64   `json:"thread_count"`
	ReadBytes                  *int64   `json:"read_bytes"`
	WriteBytes                 *int64   `json:"write_bytes"`
	ReadOperations             *int64   `json:"read_operations"`
	WriteOperations            *int64   `json:"write_operations"`
	MinorPageFaults            *int64   `json:"minor_page_faults"`
	MajorPageFaults            *int64   `json:"major_page_faults"`
	VoluntaryContextSwitches   *int64   `json:"voluntary_context_switches"`
	InvoluntaryContextSwitches *int64   `json:"involuntary_context_switches"`
	OpenFileDescriptors        *int64   `json:"open_file_descriptors"`
	CPUPercent                 *float64 `json:"cpu_percent"`
	CPUUserSecondsTotal        *float64 `json:"cpu_user_seconds_total"`
	CPUSystemSecondsTotal      *float64 `json:"cpu_system_seconds_total"`
	CPUTotalSecondsTotal       *float64 `json:"cpu_total_seconds_total"`
	CPUCoresUsed               *float64 `json:"cpu_cores_used"`
	LogicalProcessorCount      *int64   `json:"logical_processor_count"`
	MaxHeapMB                  *int64   `json:"max_heap_mb"`
	MinHeapMB                  *int64   `json:"min_heap_mb"`
	GC                         *string  `json:"gc"`
	// Live heap from jcmd/jstat (Gradle/Kotlin daemons only). Null used/committed when
	// unavailable — never coerced to zero. Distinct from MaxHeapMB (-Xmx).
	HeapUsedMB           *int64 `json:"heap_used_mb"`
	HeapCommittedMB      *int64 `json:"heap_committed_mb"`
	HeapMaxMB            *int64 `json:"heap_max_mb"`
	HeapSampledAtMs      *int64 `json:"heap_sampled_at_ms"`
	HeapAvailable        bool   `json:"heap_available"`
	MetaspaceUsedMB      *int64 `json:"metaspace_used_mb"`
	MetaspaceCommittedMB *int64 `json:"metaspace_committed_mb"`
	YoungGCCount         *int64 `json:"young_gc_count"`
	YoungGCTimeMs        *int64 `json:"young_gc_time_ms"`
	// Cumulative jstat -gc times, in seconds; null means unavailable.
	YoungGCTimeSeconds      *float64          `json:"young_gc_time_seconds"`
	FullGCTimeSeconds       *float64          `json:"full_gc_time_seconds"`
	ConcurrentGCTimeSeconds *float64          `json:"concurrent_gc_time_seconds"`
	TotalGCTimeSeconds      *float64          `json:"total_gc_time_seconds"`
	OldGCCount              *int64            `json:"old_gc_count"`
	OldGCTimeMs             *int64            `json:"old_gc_time_ms"`
	JavaVersion             *string           `json:"java_version"`
	JavaRuntimeVersion      *string           `json:"java_runtime_version"`
	JavaVendor              *string           `json:"java_vendor"`
	JavaVMName              *string           `json:"java_vm_name"`
	JavaVMVersion           *string           `json:"java_vm_version"`
	OSName                  *string           `json:"os_name"`
	OSArch                  *string           `json:"os_arch"`
	ActiveProcessorCount    *int64            `json:"active_processor_count"`
	HeapProbeDiagnostics    []ProbeDiagnostic `json:"heap_probe_diagnostics,omitempty"`
	StartTimeMs             int64             `json:"start_time_ms"`
	Status                  string            `json:"status"`
	Automated               bool              `json:"automated"`
	SampledAtMs             int64             `json:"sampled_at_ms"`
}

// ProbeDiagnostic is a safe, stable summary of a failed JVM tool attempt.
// It intentionally excludes command output and JVM system properties.
type ProbeDiagnostic struct {
	Tool      string `json:"tool"`
	Operation string `json:"operation"`
	Category  string `json:"category"`
}

// Snapshot is the `/v1/processes` response body.
type Snapshot struct {
	SampledAtMs int64     `json:"sampled_at_ms"`
	Processes   []Process `json:"processes"`
}

// History is the `/v1/processes/history` response body.
type History struct {
	SinceMs   int64     `json:"since_ms"`
	Count     int       `json:"count"`
	Processes []Process `json:"processes"`
}

// DaemonLogTail is the `/v1/daemon-logs/{pid}/tail` response body.
// Lines are redacted by the core before they are served.
type DaemonLogTail struct {
	PID           int64    `json:"pid"`
	GradleVersion string   `json:"gradle_version"`
	Path          string   `json:"path"`
	Lines         []string `json:"lines"`
}

// Health is the `/v1/health` response body.
type Health struct {
	Status      string `json:"status"`
	Version     string `json:"version"`
	Socket      string `json:"socket"`
	SampleCount int64  `json:"sample_count"`
	DBPath      string `json:"db_path,omitempty"`
}

// Capabilities is the `/v1/capabilities` response body.
type Capabilities struct {
	SchemaVersion int                `json:"schema_version"`
	Platform      string             `json:"platform"`
	SampledAtMs   int64              `json:"sampled_at_ms"`
	Metrics       []CapabilityMetric `json:"metrics"`
}

// CapabilityMetric is one optional metric's availability on this host.
type CapabilityMetric struct {
	MetricName   string `json:"metric_name"`
	Availability string `json:"availability"`
	Unit         string `json:"unit,omitempty"`
	Source       string `json:"source,omitempty"`
	Platform     string `json:"platform,omitempty"`
	Description  string `json:"description,omitempty"`
}

// SelfMetrics is the `/v1/self` response for collector overhead.
type SelfMetrics struct {
	SchemaVersion    int               `json:"schema_version"`
	Platform         string            `json:"platform"`
	SampledAtMs      int64             `json:"sampled_at_ms"`
	Metrics          any               `json:"metrics"`
	Cadences         CollectorCadences `json:"cadences"`
	ProbeConcurrency int               `json:"probe_concurrency"`
}

// MetricRecord is one normalized metric sample for machine-readable exports.
type MetricRecord struct {
	SchemaVersion    int    `json:"schema_version"`
	MetricName       string `json:"metric_name"`
	Unit             string `json:"unit"`
	Value            any    `json:"value"`
	Source           string `json:"source"`
	CollectionTimeMs int64  `json:"collection_timestamp_ms"`
	ProcessIdentity  string `json:"process_identity,omitempty"`
	PID              int32  `json:"pid,omitempty"`
}

// CollectorCadences exposes provisional independent sampling intervals.
type CollectorCadences struct {
	ProcessOSMs       int64 `json:"process_os_ms"`
	HostMs            int64 `json:"host_ms"`
	JVMHeapGCMs       int64 `json:"jvm_heap_gc_ms"`
	JVMJITCodeCacheMs int64 `json:"jvm_jit_code_cache_ms"`
	LinuxPSSMs        int64 `json:"linux_pss_ms"`
	ContainerJobMs    int64 `json:"container_job_ms"`
}

// ProcessEvent is one lifecycle row from process_events (schema_version 1).
type ProcessEvent struct {
	SchemaVersion     int    `json:"schema_version"`
	EventType         string `json:"event_type"`
	TimestampMs       int64  `json:"timestamp_ms"`
	ProcessInstanceID string `json:"process_instance_id"`
	PID               int32  `json:"pid"`
	ProcessType       string `json:"process_type,omitempty"`
	Source            string `json:"source"`
}

// Events is the `/v1/events` response body.
type Events struct {
	SchemaVersion int            `json:"schema_version"`
	SinceMs       int64          `json:"since_ms"`
	Count         int            `json:"count"`
	Events        []ProcessEvent `json:"events"`
}
