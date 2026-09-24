package tui

import (
	"fmt"
	"strings"
	"time"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

var (
	titleStyle  = lipgloss.NewStyle().Bold(true)
	headerStyle = lipgloss.NewStyle().Bold(true).Faint(true)
	errorStyle  = lipgloss.NewStyle().Foreground(lipgloss.Color("1")).Bold(true)
	footerStyle = lipgloss.NewStyle().Faint(true)
)

const (
	minWidth  = 40
	minHeight = 8
)

// View renders the alternate-screen monitor.
func (m Model) View() tea.View {
	var content string
	if m.quitting {
		content = ""
	} else {
		content = m.render()
	}
	v := tea.NewView(content)
	v.AltScreen = true
	return v
}

func (m Model) render() string {
	width := m.width
	if width <= 0 {
		width = 80
	}
	height := m.height
	if height <= 0 {
		height = 24
	}

	if width < minWidth || height < minHeight {
		return "Terminal too small — enlarge the window to view Daemonitor."
	}

	var b strings.Builder
	b.WriteString(m.renderHeader(width))
	b.WriteByte('\n')
	b.WriteString(m.renderTable(width, height))
	b.WriteByte('\n')
	b.WriteString(footerStyle.Render("q quit"))
	return b.String()
}

func (m Model) renderHeader(width int) string {
	updated := "—"
	if !m.lastUpdated.IsZero() {
		updated = m.lastUpdated.Format("15:04:05")
	}
	title := titleStyle.Render("DAEMONITOR")
	right := fmt.Sprintf("Updated %s", updated)
	gap := width - lipgloss.Width(title) - lipgloss.Width(right)
	if gap < 1 {
		gap = 1
	}
	line1 := title + strings.Repeat(" ", gap) + right

	totalRSS := int64(0)
	for _, p := range m.processes {
		totalRSS += p.RSSMemoryMB
	}
	coreStatus := "Connecting…"
	if m.connected {
		coreStatus = "Core connected"
	}
	if m.lastError != nil && !m.connected {
		coreStatus = "Core unreachable"
	}
	refresh := formatDuration(m.pollInterval)
	line2 := fmt.Sprintf("%d processes   %s RSS   %s   Refresh %s",
		len(m.processes), formatMemMB(totalRSS), coreStatus, refresh)

	var errLine string
	if m.lastError != nil {
		msg := fmt.Sprintf("Last refresh failed: %v", m.lastError)
		if m.socketPath != "" {
			msg = fmt.Sprintf("%s  (socket %s)", msg, m.socketPath)
		}
		errLine = "\n" + errorStyle.Render(truncateRunes(msg, width))
	}

	return line1 + "\n" + truncateRunes(line2, width) + errLine
}

func (m Model) renderTable(width, height int) string {
	// Reserve: 2 header lines (+ optional error), blank, table header, footer, blanks.
	headerLines := 2
	if m.lastError != nil {
		headerLines++
	}
	// line1, line2, [err], blank before table counted in View as \n after header,
	// table header + separator, footer line + blank before footer.
	available := height - headerLines - 4
	if available < 1 {
		available = 1
	}

	if len(m.processes) == 0 {
		if m.connected {
			return "No Gradle-related processes are currently running.\nWaiting for activity…"
		}
		if m.loading || m.lastError == nil {
			return "Connecting to daemonitor-cored…"
		}
		return fmt.Sprintf("Unable to reach daemonitor-cored.\nSocket: %s\nStart the core or check --socket.", m.socketPath)
	}

	cols := selectColumns(width)
	var b strings.Builder
	b.WriteString(headerStyle.Render(formatHeader(cols)))
	b.WriteByte('\n')

	n := len(m.processes)
	if n > available {
		n = available
	}
	for i := 0; i < n; i++ {
		b.WriteString(formatRow(m.processes[i], cols, m.lastUpdated))
		if i+1 < n {
			b.WriteByte('\n')
		}
	}
	if len(m.processes) > available {
		b.WriteString(fmt.Sprintf("\n… %d more", len(m.processes)-available))
	}
	return b.String()
}

type columnID int

const (
	colType columnID = iota
	colPID
	colRSS
	colCPU
	colXmx
	colUptime
	colProject
)

type columnSet struct {
	ids []columnID
}

func selectColumns(width int) columnSet {
	switch {
	case width >= 100:
		return columnSet{ids: []columnID{colType, colPID, colRSS, colCPU, colXmx, colUptime, colProject}}
	case width >= 80:
		return columnSet{ids: []columnID{colType, colPID, colRSS, colCPU, colXmx, colProject}}
	default:
		return columnSet{ids: []columnID{colType, colPID, colRSS, colCPU, colProject}}
	}
}

func formatHeader(cols columnSet) string {
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		parts = append(parts, colHeader(id))
	}
	return strings.Join(parts, "  ")
}

func colHeader(id columnID) string {
	switch id {
	case colType:
		return padRight("TYPE", 16)
	case colPID:
		return padLeft("PID", 7)
	case colRSS:
		return padLeft("RSS", 8)
	case colCPU:
		return padLeft("CPU", 7)
	case colXmx:
		return padLeft("XMX", 8)
	case colUptime:
		return padLeft("UPTIME", 7)
	case colProject:
		return "PROJECT"
	default:
		return ""
	}
}

func formatRow(p model.Process, cols columnSet, now time.Time) string {
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		parts = append(parts, colCell(p, id, now))
	}
	return strings.Join(parts, "  ")
}

func colCell(p model.Process, id columnID, now time.Time) string {
	switch id {
	case colType:
		return padRight(truncateRunes(typeDisplay(p.Type), 16), 16)
	case colPID:
		return padLeft(fmt.Sprintf("%d", p.PID), 7)
	case colRSS:
		return padLeft(formatMemMB(p.RSSMemoryMB), 8)
	case colCPU:
		if p.CPUPercent == nil {
			return padLeft("n/a", 7)
		}
		return padLeft(fmt.Sprintf("%.1f%%", *p.CPUPercent), 7)
	case colXmx:
		if p.MaxHeapMB == nil {
			return padLeft("n/a", 8)
		}
		return padLeft(formatMemMB(*p.MaxHeapMB), 8)
	case colUptime:
		ref := now
		if ref.IsZero() && p.SampledAtMs > 0 {
			ref = time.UnixMilli(p.SampledAtMs)
		}
		return padLeft(formatUptime(p.StartTimeMs, ref), 7)
	case colProject:
		return truncateRunes(projectName(p), 32)
	default:
		return ""
	}
}

func typeDisplay(t string) string {
	switch t {
	case "GRADLE_DAEMON":
		return "Gradle daemon"
	case "GRADLE_WRAPPER":
		return "Gradle wrapper"
	case "KOTLIN_DAEMON":
		return "Kotlin daemon"
	case "TEST_WORKER":
		return "Test worker"
	default:
		return t
	}
}

func projectName(p model.Process) string {
	if p.ProjectPath != nil && *p.ProjectPath != "" {
		return baseName(*p.ProjectPath)
	}
	if p.WorkingDirectory != nil && *p.WorkingDirectory != "" {
		return baseName(*p.WorkingDirectory)
	}
	return "—"
}

func baseName(path string) string {
	path = strings.TrimRight(path, "/\\")
	if i := strings.LastIndexAny(path, "/\\"); i >= 0 {
		return path[i+1:]
	}
	return path
}

func formatMemMB(mb int64) string {
	if mb < 0 {
		return "n/a"
	}
	if mb >= 1024 {
		return fmt.Sprintf("%.1f GB", float64(mb)/1024.0)
	}
	return fmt.Sprintf("%d MB", mb)
}

func formatUptime(startMs int64, now time.Time) string {
	if startMs <= 0 || now.IsZero() {
		return "n/a"
	}
	seconds := now.UnixMilli() - startMs
	if seconds < 0 {
		seconds = 0
	}
	seconds /= 1000
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

func formatDuration(d time.Duration) string {
	if d < time.Second {
		return d.String()
	}
	secs := int(d.Round(time.Second) / time.Second)
	if secs == 1 {
		return "1s"
	}
	return fmt.Sprintf("%ds", secs)
}

func padLeft(s string, n int) string {
	w := lipgloss.Width(s)
	if w >= n {
		return s
	}
	return strings.Repeat(" ", n-w) + s
}

func padRight(s string, n int) string {
	w := lipgloss.Width(s)
	if w >= n {
		return s
	}
	return s + strings.Repeat(" ", n-w)
}

func truncateRunes(s string, max int) string {
	if max <= 0 {
		return ""
	}
	if lipgloss.Width(s) <= max {
		return s
	}
	if max == 1 {
		return "…"
	}
	// Walk runes until display width would exceed max-1, then ellipsis.
	var b strings.Builder
	width := 0
	for _, r := range s {
		rw := lipgloss.Width(string(r))
		if width+rw > max-1 {
			break
		}
		b.WriteRune(r)
		width += rw
	}
	b.WriteRune('…')
	return b.String()
}
