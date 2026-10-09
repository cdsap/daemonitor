package tui

import (
	"fmt"
	"strings"

	terminalansi "github.com/charmbracelet/x/ansi"
	"github.com/clipperhouse/displaywidth"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
)

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
	leftWidth := max(0, m.width-displaywidth.String(right))
	left := padRight("DAEMONITOR", leftWidth)
	if !m.noColor {
		pad := max(0, leftWidth-displaywidth.String("DAEMONITOR"))
		left = titleStyle(m.noColor).Render("DAEMONITOR") + strings.Repeat(" ", pad)
	}
	fmt.Fprintf(&b, "%s%s\n", left, right)
	line2 := fmt.Sprintf("%d processes   %s RSS   %s   Refresh %s",
		len(m.processes), render.RSSText(totalRSS), status, m.pollInterval)
	if m.groupedView {
		line2 += "   Grouped by parent"
	}
	if !m.noColor {
		mode := ""
		if m.groupedView {
			mode = "   Grouped by parent"
		}
		line2 = fmt.Sprintf("%d processes   %s   RSS   %s   Refresh %s%s", len(m.processes), rssStyle(model.Process{RSSMemoryMB: totalRSS}, m.noColor).Render(render.RSSText(totalRSS)), statusStyle(status, m.noColor).Render(status), m.pollInterval, mode)
	}
	if m.sortField != SortRSS || m.sortOrder != SortDesc {
		line2 += fmt.Sprintf("   Sort %s %s", m.sortField, m.sortOrder)
	} else {
		line2 += fmt.Sprintf("   Sort %s %s", m.sortField, m.sortOrder)
	}
	b.WriteString(truncateWidth(line2, m.width) + "\n")
	if m.lastError != "" {
		errLine := "Last refresh failed: " + m.lastError
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
		cols := columnsForWidth(m.width)
		header := headerLine(cols, m.sortField)
		if !m.noColor {
			header = headerStyle(m.noColor).Render(header)
		}
		b.WriteString(truncateWidth(header, m.width) + "\n")
		visible := m.tableRows()
		end := m.offset + visible
		if end > len(m.displayRows) {
			end = len(m.displayRows)
		}
		for i := m.offset; i < end; i++ {
			entry := m.displayRows[i]
			p := entry.process
			prefix := "  "
			selected := int64(p.PID) == m.selectedPID
			if selected {
				prefix = "> "
			}
			if entry.groupRoot {
				if m.hierarchy.expanded[identity(p)] {
					prefix = "▼ "
				} else {
					prefix = "▶ "
				}
			}
			if entry.child {
				prefix = "  └─"
			}
			row := prefix + formatRowStyled(p, cols, now, m.noColor, selected)
			if entry.groupRoot {
				row += fmt.Sprintf("  (%d children; includes daemon)", entry.children)
			}
			row = truncateWidth(row, m.width)
			if selected && !m.noColor {
				row = selectedStyle(m.noColor).Render(row)
			}
			b.WriteString(row + "\n")
		}
	}

	b.WriteString("\n")
	if status := m.killStatusLine(); status != "" {
		b.WriteString(status + "\n")
	}
	footer := "↑/↓ or j/k select   v flat/grouped   h/l collapse/expand   E/C all   s sort   space pause   r refresh   enter details   x kill   X kill all   ? help   q quit"
	if m.helpOpen {
		footer = "Keys: q quit · arrows/jk move · g/G home/end · v flat/grouped · h/l collapse/expand · E/C all · s/S sort · space pause · r refresh · enter details · x kill selected · X kill all"
	}
	footer = truncateWidth(footer, m.width)
	if m.pendingKill != nil {
		footer = truncateWidth(killPrompt(*m.pendingKill), m.width)
		if !m.noColor {
			footer = errorStyle(m.noColor).Bold(true).Render(footer)
		}
	}
	b.WriteString(footer)
	return b.String()
}

func (m Model) killStatusLine() string {
	if m.killing {
		return truncateWidth("Sending termination signal…", m.width)
	}
	if m.notice == "" {
		return ""
	}
	line := truncateWidth(m.notice, m.width)
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

type columnSet struct {
	ids []columnID
}

func columnsForWidth(width int) columnSet {
	switch {
	case width >= 140:
		return columnSet{ids: []columnID{colType, colGC, colPID, colRSS, colThreads, colHeapPercent, colMetaspace, colHeapUsed, colHeapCmt, colXmx, colYoungGCTime, colFullGCTime, colConcurrentGCTime, colTotalGCTime, colCPU, colUptime, colProject}}
	case width >= 110:
		return columnSet{ids: []columnID{colType, colGC, colPID, colRSS, colXmx, colYoungGCTime, colFullGCTime, colConcurrentGCTime, colTotalGCTime, colCPU, colUptime, colProject}}
	case width >= 80:
		return columnSet{ids: []columnID{colType, colGC, colPID, colRSS, colXmx, colCPU, colUptime, colProject}}
	default:
		return columnSet{ids: []columnID{colType, colGC, colPID, colRSS, colCPU, colProject}}
	}
}

func headerLine(cols columnSet, sort SortField) string {
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		label := columnLabel(id)
		if sortMatches(id, sort) {
			label = label + "*"
		}
		parts = append(parts, padColumn(label, id))
	}
	return strings.Join(parts, " ")
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
		return 24
	default:
		return 8
	}
}

func padColumn(s string, id columnID) string {
	w := columnWidth(id)
	if id == colPID || id == colRSS || id == colThreads || id == colHeapPercent || id == colMetaspace || id == colCPU || id == colXmx || id == colHeapUsed || id == colHeapCmt || id == colYoungGCTime || id == colFullGCTime || id == colConcurrentGCTime || id == colTotalGCTime || id == colUptime {
		return padLeft(truncateWidth(s, w), w)
	}
	return padRight(truncateWidth(s, w), w)
}

func formatRow(p model.Process, cols columnSet, nowMs int64) string {
	return formatRowStyled(p, cols, nowMs, true, false)
}

func formatRowStyled(p model.Process, cols columnSet, nowMs int64, noColor, selected bool) string {
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		var cell string
		switch id {
		case colType:
			cell = render.TypeDisplay(p.Type)
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
		padded := padColumn(cell, id)
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
	row := strings.Join(parts, " ")
	if signals := render.ProcessSignals(p); len(signals) > 0 {
		// Keep badges outside the fixed-width table cells. Prefixing a badge
		// shifts every cell to the right while the header remains unchanged.
		if noColor || selected {
			row += "  " + strings.Join(signals, " ")
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
	w := displaywidth.String(s)
	if w >= width {
		return truncateWidth(s, width)
	}
	return s + strings.Repeat(" ", width-w)
}

func padLeft(s string, width int) string {
	w := displaywidth.String(s)
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
