package tui

import (
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

var (
	titleStyle    = lipgloss.NewStyle().Bold(true)
	headerStyle   = lipgloss.NewStyle().Bold(true).Faint(true)
	errorStyle    = lipgloss.NewStyle().Foreground(lipgloss.Color("1")).Bold(true)
	footerStyle   = lipgloss.NewStyle().Faint(true)
	selectedStyle = lipgloss.NewStyle().Reverse(true)
)

const (
	minWidth  = 40
	minHeight = 8

	// Spec §8 width tiers.
	widthFull   = 110
	widthMedium = 80
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
		return truncateRunes("Terminal too small — enlarge window.", width)
	}

	if m.details.open {
		return clampLines(m.renderDetails(width, height), width, height)
	}

	var b strings.Builder
	b.WriteString(m.renderHeader(width))
	b.WriteByte('\n')
	b.WriteString(m.renderTable(width))
	b.WriteByte('\n')
	b.WriteString(m.renderFooter(width))
	return clampLines(b.String(), width, height)
}

func (m Model) renderHeader(width int) string {
	updated := "—"
	if !m.lastUpdated.IsZero() {
		updated = m.lastUpdated.Format("15:04:05")
	}
	title := m.styled(titleStyle, "DAEMONITOR")
	right := fmt.Sprintf("Updated %s", updated)
	gap := width - lipgloss.Width(title) - lipgloss.Width(right)
	if gap < 1 {
		gap = 1
	}
	line1 := truncateRunes(title+strings.Repeat(" ", gap)+right, width)

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
	parts := []string{
		fmt.Sprintf("%d processes", len(m.processes)),
		fmt.Sprintf("%s RSS", formatMemMB(totalRSS)),
		coreStatus,
		fmt.Sprintf("Refresh %s", formatDuration(m.pollInterval)),
	}
	if m.paused {
		parts = append(parts, "PAUSED")
	}
	line2 := truncateRunes(strings.Join(parts, "   "), width)

	var errLine string
	if m.lastError != nil {
		msg := fmt.Sprintf("Last refresh failed: %v", m.lastError)
		if !m.connected {
			msg = fmt.Sprintf("Unable to reach daemonitor-cored: %v", m.lastError)
		}
		if m.socketPath != "" {
			msg = fmt.Sprintf("%s  (socket %s)", msg, m.socketPath)
		}
		if !m.connected {
			msg = fmt.Sprintf("%s — start daemonitor-cored or check --socket", msg)
		}
		errLine = "\n" + m.styled(errorStyle, truncateRunes(msg, width))
	}

	return line1 + "\n" + line2 + errLine
}

func (m Model) renderTable(width int) string {
	visible := m.visibleRows()
	if visible < 1 {
		visible = 1
	}

	if len(m.processes) == 0 {
		if m.connected {
			return clampBlock("No Gradle-related processes are currently running.\nWaiting for activity…", width)
		}
		if m.loading || m.lastError == nil {
			return truncateRunes("Connecting to daemonitor-cored…", width)
		}
		return clampBlock(fmt.Sprintf("Unable to reach daemonitor-cored.\nSocket: %s\nStart the core or check --socket.", m.socketPath), width)
	}

	cols := selectColumns(width)
	var b strings.Builder
	b.WriteString(m.styled(headerStyle, truncateRunes(formatHeader(cols, m.sortField, m.sortOrder, width), width)))
	b.WriteByte('\n')

	end := m.offset + visible
	if end > len(m.processes) {
		end = len(m.processes)
	}
	for i := m.offset; i < end; i++ {
		row := formatRow(m.processes[i], cols, m.lastUpdated, width)
		row = truncateRunes(row, width)
		if m.processes[i].PID == m.selectedPID {
			row = selectedStyle.Render(row) // reverse remains useful without color
		}
		b.WriteString(row)
		if i+1 < end {
			b.WriteByte('\n')
		}
	}
	return b.String()
}

func (m Model) renderFooter(width int) string {
	compact := "↑/↓ j/k select  s sort  space pause  r refresh  enter details  ? help  q quit"
	if m.showHelp {
		compact = "Navigation: ↑/↓ j/k · Home/g · End/G · s cycle sort · S reverse · space pause · r refresh\n" +
			"? close help · enter details · esc back · q quit"
	}
	return m.styled(footerStyle, clampBlock(compact, width))
}

func (m Model) styled(style lipgloss.Style, text string) string {
	if !m.colorEnabled {
		style = style.UnsetForeground().UnsetBackground()
	}
	return style.Render(text)
}

type columnID int

const (
	colType columnID = iota
	colPID
	colRSS
	colXmx
	colCPU
	colUptime
	colProject
)

type columnSet struct {
	ids          []columnID
	projectWidth int
	tier         string
}

func selectColumns(width int) columnSet {
	switch {
	case width >= widthFull:
		// Spec §8 ≥110: full set. Heap used/committed omitted until the API exposes them (§19).
		ids := []columnID{colType, colPID, colRSS, colXmx, colCPU, colUptime, colProject}
		return columnSet{
			ids:          ids,
			projectWidth: projectWidthFor(width, ids),
			tier:         "full",
		}
	case width >= widthMedium:
		ids := []columnID{colType, colPID, colRSS, colXmx, colCPU, colUptime, colProject}
		return columnSet{
			ids:          ids,
			projectWidth: minInt(18, projectWidthFor(width, ids)),
			tier:         "medium",
		}
	default:
		ids := []columnID{colType, colPID, colRSS, colCPU, colProject}
		return columnSet{
			ids:          ids,
			projectWidth: minInt(14, projectWidthFor(width, ids)),
			tier:         "narrow",
		}
	}
}

func projectWidthFor(termWidth int, ids []columnID) int {
	fixed := 0
	gaps := 0
	hasProject := false
	for _, id := range ids {
		if id == colProject {
			hasProject = true
			continue
		}
		fixed += colWidth(id)
		gaps++
	}
	if !hasProject {
		return 0
	}
	// gaps spaces between non-project cols, plus one before project if any fixed cols.
	sep := 0
	if gaps > 0 {
		sep = (gaps - 1) * 2 // "  " between fixed cols
		sep += 2             // before project
	}
	remain := termWidth - fixed - sep
	if remain < 4 {
		return 4
	}
	return remain
}

func colWidth(id columnID) int {
	switch id {
	case colType:
		return 16
	case colPID:
		return 7
	case colRSS:
		return 8
	case colXmx:
		return 8
	case colCPU:
		return 7
	case colUptime:
		return 7
	default:
		return 0
	}
}

func formatHeader(cols columnSet, field SortField, order SortOrder, width int) string {
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		parts = append(parts, colHeader(id, cols.projectWidth, field, order))
	}
	return truncateRunes(strings.Join(parts, "  "), width)
}

func colHeader(id columnID, projectWidth int, field SortField, order SortOrder) string {
	mark := ""
	if columnMatchesSort(id, field) {
		mark = order.Arrow()
	}
	switch id {
	case colType:
		return padRight("TYPE"+mark, 16)
	case colPID:
		return padLeft("PID"+mark, 7)
	case colRSS:
		return padLeft("RSS"+mark, 8)
	case colXmx:
		return padLeft("XMX", 8)
	case colCPU:
		return padLeft("CPU"+mark, 7)
	case colUptime:
		return padLeft("UPTIME"+mark, 7)
	case colProject:
		return padRight("PROJECT"+mark, projectWidth)
	default:
		return ""
	}
}

func columnMatchesSort(id columnID, field SortField) bool {
	switch field {
	case SortRSS:
		return id == colRSS
	case SortCPU:
		return id == colCPU
	case SortPID:
		return id == colPID
	case SortType:
		return id == colType
	case SortUptime:
		return id == colUptime
	case SortProject:
		return id == colProject
	default:
		return false
	}
}

func formatRow(p model.Process, cols columnSet, now time.Time, width int) string {
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		parts = append(parts, colCell(p, id, now, cols.projectWidth))
	}
	return truncateRunes(strings.Join(parts, "  "), width)
}

func colCell(p model.Process, id columnID, now time.Time, projectWidth int) string {
	switch id {
	case colType:
		return padRight(truncateRunes(typeDisplay(p.Type), 16), 16)
	case colPID:
		return padLeft(fmt.Sprintf("%d", p.PID), 7)
	case colRSS:
		return padLeft(formatMemMB(p.RSSMemoryMB), 8)
	case colXmx:
		if p.MaxHeapMB == nil {
			return padLeft("n/a", 8)
		}
		return padLeft(formatMemMB(*p.MaxHeapMB), 8)
	case colCPU:
		if p.CPUPercent == nil {
			return padLeft("n/a", 7)
		}
		return padLeft(fmt.Sprintf("%.1f%%", *p.CPUPercent), 7)
	case colUptime:
		ref := now
		if ref.IsZero() && p.SampledAtMs > 0 {
			ref = time.UnixMilli(p.SampledAtMs)
		}
		return padLeft(formatUptime(p.StartTimeMs, ref), 7)
	case colProject:
		name := projectName(p)
		return padRight(truncateRunes(name, projectWidth), projectWidth)
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
	if p.Name != "" {
		return p.Name
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

// truncateRunes shortens s to at most max display columns with a Unicode ellipsis.
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

func clampBlock(s string, width int) string {
	lines := strings.Split(s, "\n")
	for i, line := range lines {
		lines[i] = truncateRunes(line, width)
	}
	return strings.Join(lines, "\n")
}

func clampLines(s string, width, height int) string {
	lines := strings.Split(s, "\n")
	if height > 0 && len(lines) > height {
		lines = lines[:height]
	}
	for i, line := range lines {
		lines[i] = truncateRunes(line, width)
	}
	return strings.Join(lines, "\n")
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// stripANSI removes CSI sequences so golden tests compare readable text.
func stripANSI(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(s); {
		if s[i] == '\x1b' && i+1 < len(s) && s[i+1] == '[' {
			j := i + 2
			for j < len(s) {
				c := s[j]
				j++
				if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') {
					break
				}
			}
			i = j
			continue
		}
		r, size := utf8.DecodeRuneInString(s[i:])
		b.WriteRune(r)
		i += size
	}
	return b.String()
}
