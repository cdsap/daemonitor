package builds

import "github.com/cdsap/daemonitor/cored/internal/sessions"

// Source is the inferred origin of a build (U6 / KTD-8).
type Source string

const (
	SourceTerminal Source = "TERMINAL"
	SourceIDE      Source = "IDE"
	SourceBEP      Source = "BEP"
	SourceProfile  Source = "PROFILE"
	SourceUnknown  Source = "UNKNOWN"
)

// Tool is the build system that produced a retained build row.
type Tool string

const (
	ToolGradle Tool = "GRADLE"
	ToolBazel  Tool = "BAZEL"
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
	// Tool distinguishes Gradle daemon-log builds from Bazel history rows.
	// Empty / omitted means GRADLE for backward compatibility with existing rows.
	Tool            Tool                  `json:"tool,omitempty"`
	BazelProfile    *BazelProfile         `json:"bazel_profile,omitempty"`
	BazelDetails    *BazelBuildDetails    `json:"bazel_details,omitempty"`
	WindowSummaries []sessions.RSSSummary `json:"window_summaries,omitempty"`
}

// Sample is one RSS/CPU observation for peak calculation.
type Sample struct {
	RSSMemoryMB int64
	CPUPercent  *float64
}

// BazelProfile retains trace summaries separately from sampled OS RSS/CPU.
// Counter keys combine the trace series name and argument; units remain those
// supplied by Bazel, rather than being guessed or coerced to process metrics.
type BazelProfile struct {
	BazelVersion           string             `json:"bazel_version,omitempty"`
	Date                   string             `json:"date"`
	PhaseSeconds           map[string]float64 `json:"phase_seconds"`
	CriticalPathSeconds    float64            `json:"critical_path_seconds"`
	CriticalPathComponents int                `json:"critical_path_components"`
	PeakCounters           map[string]float64 `json:"peak_counters"`
}
