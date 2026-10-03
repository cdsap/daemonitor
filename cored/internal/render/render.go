package render

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

// WriteJSON encodes v as indented JSON to w.
func WriteJSON(w io.Writer, v any) error {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	return enc.Encode(v)
}

// WriteJSONLine writes one compact JSON value followed by a newline.
func WriteJSONLine(w io.Writer, v any) error {
	return json.NewEncoder(w).Encode(v)
}

// WriteProcessesCSV writes a stable, row-oriented process export.
func WriteProcessesCSV(w io.Writer, snap model.Snapshot) error {
	c := csv.NewWriter(w)
	if err := c.Write([]string{"sampled_at_ms", "pid", "type", "name", "rss_mb", "cpu_percent", "max_heap_mb", "heap_used_mb", "heap_committed_mb", "project", "status", "automated"}); err != nil {
		return err
	}
	for _, p := range snap.Processes {
		row := []string{fmt.Sprint(snap.SampledAtMs), fmt.Sprint(p.PID), p.Type, p.Name, fmt.Sprint(p.RSSMemoryMB), CPUText(p.CPUPercent), optionalInt64(p.MaxHeapMB), optionalInt64(p.HeapUsedMB), optionalInt64(p.HeapCommittedMB), ProjectName(p), p.Status, fmt.Sprint(p.Automated)}
		if err := c.Write(row); err != nil {
			return err
		}
	}
	c.Flush()
	return c.Error()
}

// WriteHistoryCSV writes timestamped process samples.
func WriteHistoryCSV(w io.Writer, hist model.History) error {
	c := csv.NewWriter(w)
	if err := c.Write([]string{"sampled_at_ms", "pid", "type", "rss_mb", "heap_used_mb", "heap_committed_mb", "project"}); err != nil {
		return err
	}
	for _, p := range hist.Processes {
		if err := c.Write([]string{fmt.Sprint(p.SampledAtMs), fmt.Sprint(p.PID), p.Type, fmt.Sprint(p.RSSMemoryMB), optionalInt64(p.HeapUsedMB), optionalInt64(p.HeapCommittedMB), ProjectName(p)}); err != nil {
			return err
		}
	}
	c.Flush()
	return c.Error()
}

// WriteBuildsCSV writes build records.
func WriteBuildsCSV(w io.Writer, payload client.BuildsPayload) error {
	c := csv.NewWriter(w)
	if err := c.Write([]string{"build_id", "daemon_pid", "status", "source", "agent", "agent_provider", "project_path", "duration_seconds"}); err != nil {
		return err
	}
	for _, b := range payload.Builds {
		duration := ""
		if b.DurationSeconds != nil {
			duration = fmt.Sprintf("%.3f", *b.DurationSeconds)
		}
		if err := c.Write([]string{b.BuildID, fmt.Sprint(b.DaemonPID), b.FinalStatus, b.InferredSource, b.Agent, b.AgentProvider, b.ProjectPath, duration}); err != nil {
			return err
		}
	}
	c.Flush()
	return c.Error()
}

// WriteLogsCSV writes discovered daemon logs.
func WriteLogsCSV(w io.Writer, list []logs.DaemonLog) error {
	c := csv.NewWriter(w)
	if err := c.Write([]string{"pid", "gradle_version", "path"}); err != nil {
		return err
	}
	for _, log := range list {
		if err := c.Write([]string{fmt.Sprint(log.PID), log.GradleVersion, log.Path}); err != nil {
			return err
		}
	}
	c.Flush()
	return c.Error()
}

func optionalInt64(v *int64) string {
	if v == nil {
		return ""
	}
	return fmt.Sprint(*v)
}

// WriteProcessesPlain prints one stable process snapshot without ANSI.
func WriteProcessesPlain(w io.Writer, snap model.Snapshot) {
	fmt.Fprintf(w, "sampled_at_ms=%d processes=%d\n", snap.SampledAtMs, len(snap.Processes))
	if len(snap.Processes) == 0 {
		fmt.Fprintln(w, "No Gradle-related processes are currently running.")
		return
	}
	fmt.Fprintf(w, "%-16s %-8s %7s %8s %7s %8s %8s  %-24s %s\n",
		"TYPE", "GC", "PID", "RSS", "CPU", "XMX", "UPTIME", "PROJECT", "SIGNALS")
	now := snap.SampledAtMs
	if now == 0 {
		now = time.Now().UnixMilli()
	}
	for _, p := range snap.Processes {
		signals := strings.Join(ProcessSignals(p), " ")
		if signals == "" {
			signals = "-"
		}
		fmt.Fprintf(w, "%-16s %-8s %7d %8s %7s %8s %8s  %-24s %s\n",
			TypeDisplay(p.Type),
			GCText(p.GC),
			p.PID,
			fmt.Sprintf("%dMB", p.RSSMemoryMB),
			CPUText(p.CPUPercent),
			HeapLimitText(p.MaxHeapMB),
			Uptime(p.StartTimeMs, now),
			ProjectName(p), signals,
		)
	}
}

// WriteHistoryPlain prints history rows.
func WriteHistoryPlain(w io.Writer, hist model.History) {
	fmt.Fprintf(w, "since_ms=%d count=%d\n", hist.SinceMs, hist.Count)
	for _, p := range hist.Processes {
		fmt.Fprintf(w, "  ts=%d pid=%-7d type=%-20s rss=%dMB\n",
			p.SampledAtMs, p.PID, p.Type, p.RSSMemoryMB)
	}
}

// WriteLogsPlain lists daemon logs.
func WriteLogsPlain(w io.Writer, list []logs.DaemonLog) {
	fmt.Fprintf(w, "daemon_logs=%d\n", len(list))
	for _, log := range list {
		fmt.Fprintf(w, "  pid=%-7d gradle=%-8s %s\n", log.PID, log.GradleVersion, log.Path)
	}
}

// WriteLogTailPlain prints a log tail.
func WriteLogTailPlain(w io.Writer, tail logs.Tail) {
	fmt.Fprintf(w, "pid=%d gradle=%s lines=%d events=%d\n",
		tail.PID, tail.GradleVersion, len(tail.Lines), len(tail.Events))
	for _, line := range tail.Lines {
		fmt.Fprintln(w, line)
	}
}

// WriteBuildsPlain prints builds.
func WriteBuildsPlain(w io.Writer, payload client.BuildsPayload) {
	fmt.Fprintf(w, "builds=%d\n", payload.Count)
	for _, b := range payload.Builds {
		dur := "-"
		if b.DurationSeconds != nil {
			dur = fmt.Sprintf("%.1fs", *b.DurationSeconds)
		}
		agent := b.Agent
		if agent == "" {
			agent = "-"
		}
		provider := b.AgentProvider
		if provider == "" {
			provider = "-"
		}
		fmt.Fprintf(w, "  id=%-36s pid=%-7d status=%-20s source=%-8s agent=%-20s provider=%-10s dur=%s  %s\n",
			truncate(b.BuildID, 36), b.DaemonPID, b.FinalStatus, b.InferredSource, truncate(agent, 20), truncate(provider, 10), dur, truncate(b.ProjectPath, 40))
	}
}

// WriteHealthPlain prints health.
func WriteHealthPlain(w io.Writer, h model.Health) {
	fmt.Fprintf(w, "status=%s version=%s socket=%s samples=%d",
		h.Status, h.Version, h.Socket, h.SampleCount)
	if h.DBPath != "" {
		fmt.Fprintf(w, " db=%s", h.DBPath)
	}
	fmt.Fprintln(w)
}

// TypeDisplay maps API type codes to short labels.
func TypeDisplay(t string) string {
	switch t {
	case "GRADLE_DAEMON":
		return "Gradle daemon"
	case "GRADLE_WRAPPER":
		return "Gradle wrapper"
	case "KOTLIN_DAEMON":
		return "Kotlin daemon"
	case "TEST_WORKER":
		return "Test worker"
	case "JAVA_GRADLE_RELATED":
		return "Java (Gradle)"
	default:
		if t == "" {
			return "n/a"
		}
		return t
	}
}

// CPUText formats CPU percent or n/a.
func CPUText(v *float64) string {
	if v == nil {
		return "n/a"
	}
	return fmt.Sprintf("%.1f%%", *v)
}

// HeapLimitText formats Xmx or n/a.
func HeapLimitText(v *int64) string {
	if v == nil {
		return "n/a"
	}
	return formatBytesMB(*v)
}

// HeapText formats a live heap measurement or n/a when the probe was unavailable.
func HeapText(v *int64) string {
	if v == nil {
		return "n/a"
	}
	return formatBytesMB(*v)
}

// GCText formats a garbage collector name or n/a when it was unavailable.
func GCText(v *string) string {
	if v == nil || *v == "" {
		return "n/a"
	}
	return *v
}

const (
	// MemoryWarnMB and MemoryCritMB match the desktop live-monitor thresholds.
	MemoryWarnMB = int64(4_096)
	MemoryCritMB = int64(8_192)
)

// ProcessSignals returns compact operator-facing signals for a live process.
func ProcessSignals(p model.Process) []string {
	signals := make([]string, 0, 2)
	if p.RSSMemoryMB >= MemoryCritMB {
		signals = append(signals, "CRIT MEM")
	} else if p.RSSMemoryMB >= MemoryWarnMB {
		signals = append(signals, "HIGH MEM")
	}
	if p.Automated {
		signals = append(signals, "AUTOMATED")
	}
	return signals
}

// formatBytesMB formats a megabyte count compactly.
func formatBytesMB(mb int64) string {
	if mb >= 1024 {
		gb := float64(mb) / 1024.0
		if gb == float64(int64(gb)) {
			return fmt.Sprintf("%d GB", int64(gb))
		}
		return fmt.Sprintf("%.1f GB", gb)
	}
	return fmt.Sprintf("%d MB", mb)
}

// RSSText formats RSS.
func RSSText(mb int64) string {
	return formatBytesMB(mb)
}

// ProjectName picks project or cwd basename.
func ProjectName(p model.Process) string {
	if p.ProjectPath != nil && strings.TrimSpace(*p.ProjectPath) != "" {
		return basename(*p.ProjectPath)
	}
	if p.WorkingDirectory != nil && strings.TrimSpace(*p.WorkingDirectory) != "" {
		return basename(*p.WorkingDirectory)
	}
	return "—"
}

// Uptime formats elapsed time since start.
func Uptime(startMs, nowMs int64) string {
	if startMs <= 0 {
		return "n/a"
	}
	seconds := (nowMs - startMs) / 1000
	if seconds < 0 {
		seconds = 0
	}
	switch {
	case seconds < 60:
		return fmt.Sprintf("%ds", seconds)
	case seconds < 3600:
		return fmt.Sprintf("%dm", seconds/60)
	case seconds < 86400:
		return fmt.Sprintf("%dh %dm", seconds/3600, (seconds/60)%60)
	default:
		return fmt.Sprintf("%dd %dh", seconds/86400, (seconds/3600)%24)
	}
}

func basename(path string) string {
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
	if n <= 1 {
		return "…"
	}
	return s[:n-1] + "…"
}
