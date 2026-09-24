package render

import (
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

// WriteProcessesPlain prints one stable process snapshot without ANSI.
func WriteProcessesPlain(w io.Writer, snap model.Snapshot) {
	fmt.Fprintf(w, "sampled_at_ms=%d processes=%d\n", snap.SampledAtMs, len(snap.Processes))
	if len(snap.Processes) == 0 {
		fmt.Fprintln(w, "No Gradle-related processes are currently running.")
		return
	}
	fmt.Fprintf(w, "%-16s %7s %8s %7s %8s %8s  %s\n",
		"TYPE", "PID", "RSS", "CPU", "XMX", "UPTIME", "PROJECT")
	now := snap.SampledAtMs
	if now == 0 {
		now = time.Now().UnixMilli()
	}
	for _, p := range snap.Processes {
		fmt.Fprintf(w, "%-16s %7d %8s %7s %8s %8s  %s\n",
			TypeDisplay(p.Type),
			p.PID,
			fmt.Sprintf("%dMB", p.RSSMemoryMB),
			CPUText(p.CPUPercent),
			HeapLimitText(p.MaxHeapMB),
			Uptime(p.StartTimeMs, now),
			ProjectName(p),
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
		fmt.Fprintf(w, "  id=%-36s pid=%-7d status=%-20s source=%-8s dur=%s  %s\n",
			truncate(b.BuildID, 36), b.DaemonPID, b.FinalStatus, b.InferredSource, dur, truncate(b.ProjectPath, 40))
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
