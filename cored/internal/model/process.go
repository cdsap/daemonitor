package model

// Process is an IPC snapshot of a Gradle-related process.
// Field names mirror the Kotlin GradleProcess domain for dual-run clients.
type Process struct {
	PID              int32    `json:"pid"`
	ParentPID        int32    `json:"parent_pid"`
	Type             string   `json:"type"`
	Name             string   `json:"name"`
	CommandLine      string   `json:"command_line"`
	WorkingDirectory *string  `json:"working_directory"`
	ProjectPath      *string  `json:"project_path"`
	RSSMemoryMB      int64    `json:"rss_memory_mb"`
	CPUPercent       *float64 `json:"cpu_percent"`
	MaxHeapMB        *int64   `json:"max_heap_mb"`
	MinHeapMB        *int64   `json:"min_heap_mb"`
	GC               *string  `json:"gc"`
	StartTimeMs      int64    `json:"start_time_ms"`
	Status           string   `json:"status"`
	Automated        bool     `json:"automated"`
	SampledAtMs      int64    `json:"sampled_at_ms"`
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
