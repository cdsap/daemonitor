package builds

// Source is the inferred origin of a build (U6 / KTD-8).
type Source string

const (
	SourceTerminal Source = "TERMINAL"
	SourceIDE      Source = "IDE"
	SourceUnknown  Source = "UNKNOWN"
)

// FinalStatus is the final state of a build invocation (U5).
type FinalStatus string

const (
	StatusSuccess            FinalStatus = "SUCCESS"
	StatusFailed             FinalStatus = "FAILED"
	StatusCompletedNoOutcome FinalStatus = "COMPLETED_NO_OUTCOME"
	StatusInterrupted        FinalStatus = "INTERRUPTED"
)

// Build is one confirmed build invocation (Kotlin Build / U5).
type Build struct {
	BuildID          string      `json:"build_id"`
	DaemonPID        int64       `json:"daemon_pid"`
	DaemonIdentity   string      `json:"daemon_identity,omitempty"`
	CommandLine      *string     `json:"command_line"`
	WorkingDirectory string      `json:"working_directory,omitempty"`
	ProjectPath      string      `json:"project_path,omitempty"`
	StartTimeMs      int64       `json:"start_time_ms"`
	EndTimeMs        *int64      `json:"end_time_ms"`
	DurationSeconds  *float64    `json:"duration_seconds"`
	PeakMemoryMB     *int64      `json:"peak_memory_mb"`
	AvgMemoryMB      *int64      `json:"avg_memory_mb"`
	PeakCPUPercent   *float64    `json:"peak_cpu_percent"`
	InferredSource   Source      `json:"inferred_source"`
	FinalStatus      FinalStatus `json:"final_status"`
	LogSnippet       string      `json:"log_snippet,omitempty"`
	Agent            string      `json:"agent,omitempty"`
	AgentProvider    string      `json:"agent_provider,omitempty"`
}

// Sample is one RSS/CPU observation for peak calculation.
type Sample struct {
	RSSMemoryMB int64
	CPUPercent  *float64
}
