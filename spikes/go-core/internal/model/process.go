package model

// Process is a minimal IPC snapshot of a Gradle-related process.
// Field names mirror the Kotlin domain enough for a future client to map cleanly.
type Process struct {
	PID         int32   `json:"pid"`
	Type        string  `json:"type"`
	Name        string  `json:"name"`
	CommandLine string  `json:"command_line"`
	RSSMemoryMB float64 `json:"rss_memory_mb"`
	CPUPercent  float64 `json:"cpu_percent"`
	SampledAtMs int64   `json:"sampled_at_ms"`
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

// Health is the `/v1/health` response body.
type Health struct {
	Status      string `json:"status"`
	Version     string `json:"version"`
	Socket      string `json:"socket"`
	SampleCount int64  `json:"sample_count"`
	DBPath      string `json:"db_path,omitempty"`
}
