// Package render formats one-shot plain-text and JSON CLI output.
package render

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

// ProcessesPlain writes a deterministic plain-text process snapshot with no ANSI.
func ProcessesPlain(w io.Writer, snap model.Snapshot) error {
	var b strings.Builder
	fmt.Fprintf(&b, "sampled_at_ms=%d processes=%d\n", snap.SampledAtMs, len(snap.Processes))
	for _, p := range snap.Processes {
		cpu := "-"
		if p.CPUPercent != nil {
			cpu = fmt.Sprintf("%.1f%%", *p.CPUPercent)
		}
		xmx := "-"
		if p.MaxHeapMB != nil {
			xmx = fmt.Sprintf("%dMB", *p.MaxHeapMB)
		}
		project := "-"
		if p.ProjectPath != nil && *p.ProjectPath != "" {
			project = baseName(*p.ProjectPath)
		} else if p.WorkingDirectory != nil && *p.WorkingDirectory != "" {
			project = baseName(*p.WorkingDirectory)
		}
		fmt.Fprintf(&b, "  pid=%-7d type=%-20s rss=%dMB cpu=%s xmx=%s project=%s  %s\n",
			p.PID, p.Type, p.RSSMemoryMB, cpu, xmx, truncate(project, 32), truncate(p.Name, 40))
	}
	_, err := io.WriteString(w, b.String())
	return err
}

// JSON writes indented JSON to w. Diagnostics must go to stderr separately.
func JSON(w io.Writer, v any) error {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	return enc.Encode(v)
}

// HistoryPlain writes recent process history.
func HistoryPlain(w io.Writer, hist model.History) error {
	var b strings.Builder
	fmt.Fprintf(&b, "since_ms=%d count=%d\n", hist.SinceMs, hist.Count)
	for _, p := range hist.Processes {
		fmt.Fprintf(&b, "  ts=%d pid=%-7d type=%-20s rss=%dMB\n",
			p.SampledAtMs, p.PID, p.Type, p.RSSMemoryMB)
	}
	_, err := io.WriteString(w, b.String())
	return err
}

// HealthPlain writes core health information.
func HealthPlain(w io.Writer, h model.Health) error {
	_, err := fmt.Fprintf(w, "status=%s version=%s socket=%s sample_count=%d db_path=%s\n",
		h.Status, h.Version, h.Socket, h.SampleCount, h.DBPath)
	return err
}

// DaemonLog is a discovered Gradle daemon log entry.
type DaemonLog struct {
	PID           int64  `json:"pid"`
	GradleVersion string `json:"gradle_version"`
	Path          string `json:"path"`
}

// DaemonLogsPayload is the `/v1/daemon-logs` response.
type DaemonLogsPayload struct {
	Logs []DaemonLog `json:"logs"`
}

// DaemonLogsPlain lists discovered daemon logs.
func DaemonLogsPlain(w io.Writer, payload DaemonLogsPayload) error {
	var b strings.Builder
	fmt.Fprintf(&b, "daemon_logs=%d\n", len(payload.Logs))
	for _, log := range payload.Logs {
		fmt.Fprintf(&b, "  pid=%-7d gradle=%-8s %s\n", log.PID, log.GradleVersion, log.Path)
	}
	_, err := io.WriteString(w, b.String())
	return err
}

// LogTail is the `/v1/daemon-logs/{pid}/tail` response.
type LogTail struct {
	PID           int64    `json:"pid"`
	GradleVersion string   `json:"gradle_version"`
	Path          string   `json:"path"`
	Lines         []string `json:"lines"`
	Events        []any    `json:"events"`
}

// LogTailPlain prints a retained log tail.
func LogTailPlain(w io.Writer, tail LogTail) error {
	var b strings.Builder
	fmt.Fprintf(&b, "pid=%d gradle=%s lines=%d events=%d\n",
		tail.PID, tail.GradleVersion, len(tail.Lines), len(tail.Events))
	for _, line := range tail.Lines {
		b.WriteString(line)
		if !strings.HasSuffix(line, "\n") {
			b.WriteByte('\n')
		}
	}
	_, err := io.WriteString(w, b.String())
	return err
}

// Build is one aggregated build row.
type Build struct {
	BuildID         string   `json:"build_id"`
	DaemonPID       int64    `json:"daemon_pid"`
	FinalStatus     string   `json:"final_status"`
	InferredSource  string   `json:"inferred_source"`
	ProjectPath     string   `json:"project_path"`
	DurationSeconds *float64 `json:"duration_seconds"`
}

// BuildsPayload is the `/v1/builds` response.
type BuildsPayload struct {
	Count  int     `json:"count"`
	Builds []Build `json:"builds"`
}

// BuildsPlain writes recent builds.
func BuildsPlain(w io.Writer, payload BuildsPayload) error {
	var b strings.Builder
	fmt.Fprintf(&b, "builds=%d\n", payload.Count)
	for _, build := range payload.Builds {
		dur := "-"
		if build.DurationSeconds != nil {
			dur = fmt.Sprintf("%.1fs", *build.DurationSeconds)
		}
		fmt.Fprintf(&b, "  id=%-36s pid=%-7d status=%-20s source=%-8s dur=%s  %s\n",
			truncate(build.BuildID, 36), build.DaemonPID, build.FinalStatus, build.InferredSource, dur, truncate(build.ProjectPath, 40))
	}
	_, err := io.WriteString(w, b.String())
	return err
}

// ContainsANSI reports whether s includes an ESC CSI sequence.
func ContainsANSI(s string) bool {
	return strings.Contains(s, "\x1b[")
}

// FormatUpdatedAt formats a sampled_at_ms for display.
func FormatUpdatedAt(ms int64) string {
	if ms <= 0 {
		return "—"
	}
	return time.UnixMilli(ms).UTC().Format(time.RFC3339)
}

func baseName(path string) string {
	path = strings.TrimRight(path, "/\\")
	if i := strings.LastIndexAny(path, "/\\"); i >= 0 {
		return path[i+1:]
	}
	return path
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
