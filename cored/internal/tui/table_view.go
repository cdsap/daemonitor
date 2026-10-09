package tui

import (
	"fmt"
	"strings"

	terminalansi "github.com/charmbracelet/x/ansi"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
)

const (
	// rowPrefix is the selection gutter ("> " or "  ") in front of every row.
	rowPrefix = "  "
	// treeMarkerWidth is the extra gutter used for ▼/▶/├/└ in grouped view.
	treeMarkerWidth = 2
)

// footerVariants are tried longest-first; the first that fits the width wins.
var footerVariants = []string{
	"↑/↓ or j/k select   v flat/grouped   h/l collapse/expand   E/C all   s sort   space pause   r refresh   enter details   x kill   X kill all   ? help   q quit",
	"j/k move  v group  h/l fold  s sort  space pause  r refresh  enter details  x kill  ? help  q quit",
	"j/k move  v group  s sort  enter details  x kill  ? help  q quit",
	"j/k move  enter details  ? help  q quit",
	"? help  q quit",
}

var helpLines = []string{
	"Move      ↑/↓ or j/k  ·  g/G first/last  ·  enter details  ·  esc back",
	"View      v flat/grouped  ·  h/l collapse/expand group  ·  E/C expand/collapse all",
	"Sort      s next column  ·  S reverse direction",
	"Refresh   space pause/resume  ·  r refresh now",
	"Process   x kill selected  ·  X kill all listed (y confirms)",
	"          ? close help  ·  q quit",
}

func (m Model) renderTable() string {
	var b strings.Builder
	now := m.sampledAt
	if now == 0 {
		now = m.now().UnixMilli()
	}

	updated := "—"
	if !m.lastUpdated.IsZero() {
		updated = m.lastUpdated.Format("15:04:05")
	}
	status := "Core connected"
	if !m.connected {
		if m.loading {
			status = "Connecting…"
		} else {
			status = "Core disconnected"
		}
	}
	if m.paused {
		status = "PAUSED"
	}

	totalRSS := int64(0)
	for _, row := range m.displayRows {
		if row.groupRoot {
			totalRSS += row.process.RSSMemoryMB
		} else if !row.child {
			totalRSS += row.process.RSSMemoryMB
		}
	}

	right := padLeft("Updated "+updated, min(20, m.width))
	leftWidth := max(0, m.width-terminalansi.StringWidth(right))
	left := padRight("DAEMONITOR", leftWidth)
	if !m.noColor {
		pad := max(0, leftWidth-terminalansi.StringWidth("DAEMONITOR"))
		left = titleStyle(m.noColor).Render("DAEMONITOR") + strings.Repeat(" ", pad)
	}
	fmt.Fprintf(&b, "%s%s\n", left, right)
	rssText, statusText := render.RSSText(totalRSS), status
	if !m.noColor {
		rssText = rssStyle(model.Process{RSSMemoryMB: totalRSS}, m.noColor).Render(rssText)
		statusText = statusStyle(status, m.noColor).Render(status)
	}
	// Ordered by importance: trailing segments are dropped first on narrow terminals.
	segments := []string{fmt.Sprintf("%d processes", len(m.processes)), rssText + " RSS", statusText}
	if m.groupedView {
		segments = append(segments, "Grouped by parent")
	}
	segments = append(segments, fmt.Sprintf("Sort %s %s", m.sortField, m.sortOrder), fmt.Sprintf("Refresh %s", m.pollInterval))
	b.WriteString(fitSegments(segments, "   ", m.width) + "\n")
	if m.lastError != "" {
		errLine := "Last refresh failed: " + oneLine(m.lastError)
		if !m.noColor {
			errLine = errorStyle(m.noColor).Render(errLine)
		}
		b.WriteString(truncateWidth(errLine, m.width) + "\n")
	}
	b.WriteString("\n")

	if len(m.processes) == 0 && m.connected {
		b.WriteString("No Gradle-related processes are currently running.\nWaiting for activity…\n")
	} else if len(m.processes) == 0 && !m.connected {
		b.WriteString("Waiting for daemonitor-cored…\n")
	} else {
		gutter := len(rowPrefix)
		if m.groupedView {
			gutter += treeMarkerWidth
		}
		cols := columnsFor(m.width, gutter)
		header := truncateWidth(strings.Repeat(" ", gutter)+headerLine(cols, m.sortField, m.sortOrder), m.width)
		if !m.noColor {
			header = headerStyle(m.noColor).Render(header)
		}
		b.WriteString(header + "\n")
		visible := m.tableRows()
		end := m.offset + visible
		if end > len(m.displayRows) {
			end = len(m.displayRows)
		}
		for i := m.offset; i < end; i++ {
			entry := m.displayRows[i]
			p := entry.process
			prefix := rowPrefix
			selected := int64(p.PID) == m.selectedPID
			if selected {
				prefix = "> "
			}
			if m.groupedView {
				prefix += m.treeMarker(i)
			}
			rowCols, suffix := cols, ""
			if entry.groupRoot {
				suffix = groupSummary(entry.children, cols.width(colProject))
				rowCols = cols.reserve(terminalansi.StringWidth(suffix))
			}
			row := truncateWidth(prefix+formatRowStyled(p, rowCols, now, m.noColor, selected)+suffix, m.width)
			if selected && !m.noColor {
				row = selectedStyle(m.noColor).Render(padRight(row, m.width))
			}
			b.WriteString(row + "\n")
		}
	}

	b.WriteString("\n")
	if status := m.killStatusLine(); status != "" {
		b.WriteString(status + "\n")
	}
	footer := footerVariants[len(footerVariants)-1]
	for _, variant := range footerVariants {
		if terminalansi.StringWidth(variant) <= m.width {
			footer = variant
			break
		}
	}
	footer = truncateWidth(footer, m.width)
	if m.helpOpen {
		lines := make([]string, len(helpLines))
		for i, line := range helpLines {
			lines[i] = truncateWidth(line, m.width)
		}
		footer = strings.Join(lines, "\n")
	}
	if m.pendingKill != nil {
		footer = truncateWidth(killPrompt(*m.pendingKill), m.width)
		if !m.noColor {
			footer = errorStyle(m.noColor).Bold(true).Render(footer)
		}
	}
	b.WriteString(footer)
	return b.String()
}

// groupSummary returns the longest group-root note that still leaves PROJECT
// readable; the ▼/▶ marker already signals the group when no note fits.
func groupSummary(children, projectWidth int) string {
	const projectRoom = 16
	for _, text := range []string{
		fmt.Sprintf("  (%d children; includes daemon)", children),
		fmt.Sprintf("  +%d children", children),
	} {
		if projectWidth-terminalansi.StringWidth(text) >= projectRoom {
			return text
		}
	}
	return ""
}

// treeMarker draws the grouped-view hierarchy gutter for display row i.
func (m Model) treeMarker(i int) string {
	entry := m.displayRows[i]
	switch {
	case entry.groupRoot:
		if m.hierarchy.expanded[identity(entry.target)] {
			return "▼ "
		}
		return "▶ "
	case entry.child:
		if i+1 < len(m.displayRows) && m.displayRows[i+1].child {
			return "├ "
		}
		return "└ "
	}
	return "  "
}

func (m Model) killStatusLine() string {
	if m.killing {
		return truncateWidth("Sending termination signal…", m.width)
	}
	if m.notice == "" {
		return ""
	}
	line := truncateWidth(oneLine(m.notice), m.width)
	if m.noticeError && !m.noColor {
		return errorStyle(m.noColor).Render(line)
	}
	return line
}

func killPrompt(req killRequest) string {
	const suffix = "?  y confirm · any other key cancels"
	if req.all {
		return fmt.Sprintf("Kill all %d recorded processes%s", len(req.targets), suffix)
	}
	p := req.targets[0]
	return fmt.Sprintf("Kill %s pid %d (%s)%s", render.TypeDisplay(p.Type), p.PID, render.ProjectName(p), suffix)
}

type columnID int

const (
	colType columnID = iota
	colGC
	colPID
	colRSS
	colThreads
	colHeapPercent
	colMetaspace
	colHeapUsed
	colHeapCmt
	colXmx
	colYoungGCTime
	colFullGCTime
	colConcurrentGCTime
	colTotalGCTime
	colCPU
	colUptime
	colProject
)

const (
	defaultProjectCols = 24
	minProjectCols     = 8
)

// columnLayouts are tried widest-first; the first whose fixed columns plus a
// minimal PROJECT column fit the terminal is used.
var columnLayouts = [][]columnID{
	{colType, colGC, colPID, colRSS, colThreads, colHeapPercent, colMetaspace, colHeapUsed, colHeapCmt, colXmx, colYoungGCTime, colFullGCTime, colConcurrentGCTime, colTotalGCTime, colCPU, colUptime, colProject},
	{colType, colGC, colPID, colRSS, colXmx, colYoungGCTime, colFullGCTime, colConcurrentGCTime, colTotalGCTime, colCPU, colUptime, colProject},
	{colType, colGC, colPID, colRSS, colXmx, colCPU, colUptime, colProject},
	{colType, colGC, colPID, colRSS, colCPU, colProject},
}

type columnSet struct {
	ids []columnID
	// projectWidth is the PROJECT cell width; zero means defaultProjectCols.
	projectWidth int
}

func columnsForWidth(width int) columnSet {
	return columnsFor(width, len(rowPrefix))
}

// columnsFor picks the widest layout that fits width after a row gutter and
// gives PROJECT whatever space remains.
func columnsFor(width, gutter int) columnSet {
	for i, ids := range columnLayouts {
		cols := columnSet{ids: ids}
		fixed := gutter + cols.fixedWidth()
		if fixed+minProjectCols <= width || i == len(columnLayouts)-1 {
			cols.projectWidth = max(minProjectCols, width-fixed)
			return cols
		}
	}
	return columnSet{}
}

// fixedWidth is the width of every column except PROJECT, including gaps.
func (c columnSet) fixedWidth() int {
	total := 0
	for i, id := range c.ids {
		if i > 0 {
			total += len(columnGap(id))
		}
		if id != colProject {
			total += columnWidth(id)
		}
	}
	return total
}

func (c columnSet) width(id columnID) int {
	if id == colProject {
		if c.projectWidth > 0 {
			return c.projectWidth
		}
		return defaultProjectCols
	}
	return columnWidth(id)
}

// reserve shrinks PROJECT so n trailing cells fit after the row.
func (c columnSet) reserve(n int) columnSet {
	c.projectWidth = max(minProjectCols, c.width(colProject)-n)
	return c
}

// columnGap separates a column from the one before it. The left-aligned
// PROJECT text gets extra room so it does not run into right-aligned UPTIME or CPU.
func columnGap(id columnID) string {
	if id == colProject {
		return "  "
	}
	return " "
}

func joinColumns(ids []columnID, cells []string) string {
	var b strings.Builder
	for i, cell := range cells {
		if i > 0 {
			b.WriteString(columnGap(ids[i]))
		}
		b.WriteString(cell)
	}
	return b.String()
}

func headerLine(cols columnSet, sort SortField, order SortOrder) string {
	arrow := "▼"
	if order == SortAsc {
		arrow = "▲"
	}
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		label := columnLabel(id)
		if sortMatches(id, sort) {
			if rightAligned(id) {
				label = arrow + label
			} else {
				label = label + arrow
			}
		}
		parts = append(parts, padColumn(label, id, cols.width(id)))
	}
	return joinColumns(cols.ids, parts)
}

func sortMatches(id columnID, sort SortField) bool {
	switch sort {
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
	case SortHeapUsed:
		return id == colHeapUsed
	case SortHeapCommitted:
		return id == colHeapCmt
	case SortHeapMax:
		return id == colXmx
	}
	return false
}

func columnLabel(id columnID) string {
	switch id {
	case colType:
		return "TYPE"
	case colGC:
		return "GC"
	case colPID:
		return "PID"
	case colRSS:
		return "RSS"
	case colThreads:
		return "THR"
	case colHeapPercent:
		return "HEAP%"
	case colMetaspace:
		return "META"
	case colHeapUsed:
		return "HEAP"
	case colHeapCmt:
		return "CMT"
	case colXmx:
		return "XMX"
	case colYoungGCTime:
		return "YGCT(s)"
	case colFullGCTime:
		return "FGCT(s)"
	case colConcurrentGCTime:
		return "CGCT(s)"
	case colTotalGCTime:
		return "GCT(s)"
	case colCPU:
		return "CPU"
	case colUptime:
		return "UPTIME"
	case colProject:
		return "PROJECT"
	default:
		return ""
	}
}

func columnWidth(id columnID) int {
	switch id {
	case colType:
		return 16
	case colGC:
		return 8
	case colPID:
		return 7
	case colRSS:
		return 8
	case colThreads:
		return 6
	case colHeapPercent:
		return 7
	case colMetaspace:
		return 8
	case colHeapUsed, colHeapCmt, colXmx, colYoungGCTime, colFullGCTime, colConcurrentGCTime, colTotalGCTime:
		return 8
	case colCPU:
		return 7
	case colUptime:
		return 8
	case colProject:
		return defaultProjectCols
	default:
		return 8
	}
}

func rightAligned(id columnID) bool {
	return id != colType && id != colGC && id != colProject
}

func padColumn(s string, id columnID, w int) string {
	if rightAligned(id) {
		return padLeft(truncateWidth(s, w), w)
	}
	return padRight(truncateWidth(s, w), w)
}

// tableTypeLabel shortens type names that do not fit the TYPE column; the
// details view still shows the full render.TypeDisplay label.
func tableTypeLabel(t string) string {
	switch t {
	case "TEST_WORKER":
		return "Test worker"
	case "UNKNOWN_GRADLE_WORKER":
		return "Unknown worker"
	default:
		return render.TypeDisplay(t)
	}
}

func formatRow(p model.Process, cols columnSet, nowMs int64) string {
	return formatRowStyled(p, cols, nowMs, true, false)
}

func formatRowStyled(p model.Process, cols columnSet, nowMs int64, noColor, selected bool) string {
	signals := render.ProcessSignals(p)
	badges := strings.Join(signals, " ")
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		var cell string
		switch id {
		case colType:
			cell = tableTypeLabel(p.Type)
		case colGC:
			cell = render.GCText(p.GC)
		case colPID:
			cell = fmt.Sprintf("%d", p.PID)
		case colRSS:
			cell = render.RSSText(p.RSSMemoryMB)
		case colThreads:
			cell = optionalInt64Text(p.ThreadCount, "")
		case colHeapPercent:
			cell = render.HeapPercentText(p.HeapUsedMB, p.HeapMaxMB)
		case colMetaspace:
			cell = render.HeapText(p.MetaspaceUsedMB)
		case colHeapUsed:
			cell = render.HeapText(p.HeapUsedMB)
		case colHeapCmt:
			cell = render.HeapText(p.HeapCommittedMB)
		case colXmx:
			cell = render.HeapLimitText(p.MaxHeapMB)
		case colYoungGCTime:
			cell = render.GCTimeText(p.YoungGCTimeSeconds)
		case colFullGCTime:
			cell = render.GCTimeText(p.FullGCTimeSeconds)
		case colConcurrentGCTime:
			cell = render.GCTimeText(p.ConcurrentGCTimeSeconds)
		case colTotalGCTime:
			cell = render.GCTimeText(p.TotalGCTimeSeconds)
		case colCPU:
			cell = render.CPUText(p.CPUPercent)
		case colUptime:
			cell = render.Uptime(p.StartTimeMs, nowMs)
		case colProject:
			cell = render.ProjectName(p)
		}
		width := cols.width(id)
		if id == colProject && badges != "" {
			// Badges share the PROJECT cell so the row stays within the terminal.
			width = max(minProjectCols, width-terminalansi.StringWidth(badges)-2)
		}
		padded := padColumn(cell, id, width)
		if !noColor && !selected {
			switch id {
			case colRSS:
				padded = rssStyle(p, noColor).Render(padded)
			case colCPU:
				padded = cpuStyle(p, noColor).Render(padded)
			case colHeapUsed, colHeapCmt, colXmx:
				padded = heapStyle(noColor).Render(padded)
			}
		}
		parts = append(parts, padded)
	}
	row := joinColumns(cols.ids, parts)
	if len(signals) > 0 {
		// Keep badges outside the fixed-width table cells. Prefixing a badge
		// shifts every cell to the right while the header remains unchanged.
		if noColor || selected {
			row += "  " + badges
		} else {
			styled := make([]string, 0, len(signals))
			for _, signal := range signals {
				styled = append(styled, signalStyle(signal, noColor).Render(signal))
			}
			row += "  " + strings.Join(styled, " ")
		}
	}
	return row
}

// fitSegments joins as many leading segments as fit within width.
func fitSegments(segments []string, sep string, width int) string {
	line := ""
	for i, segment := range segments {
		next := segment
		if i > 0 {
			next = line + sep + segment
		}
		if i > 0 && terminalansi.StringWidth(next) > width {
			break
		}
		line = next
	}
	return truncateWidth(line, width)
}

// oneLine collapses whitespace, including newlines from server error bodies.
func oneLine(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

// truncateWidth fits s into width terminal cells. Styled strings are measured
// without their ANSI escapes and are never cut inside an escape sequence.
func truncateWidth(s string, width int) string {
	if width <= 0 {
		return ""
	}
	if terminalansi.StringWidth(s) <= width {
		return s
	}
	return terminalansi.Truncate(s, width, "…")
}

func padRight(s string, width int) string {
	w := terminalansi.StringWidth(s)
	if w >= width {
		return truncateWidth(s, width)
	}
	return s + strings.Repeat(" ", width-w)
}

func padLeft(s string, width int) string {
	w := terminalansi.StringWidth(s)
	if w >= width {
		return truncateWidth(s, width)
	}
	return strings.Repeat(" ", width-w) + s
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
