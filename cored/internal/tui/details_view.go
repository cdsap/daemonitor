package tui

import (
	"fmt"
	"math"
	"strings"
	"time"

	terminalansi "github.com/charmbracelet/x/ansi"

	"github.com/cdsap/daemonitor/cored/internal/analysis"
	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
)

func (m Model) renderDetails() string {
	p := m.detailProcess
	if p == nil {
		return "No process selected.\nPress esc to return."
	}
	now := m.sampledAt
	if now == 0 {
		now = m.now().UnixMilli()
	}
	header := detailHeader(*p, now, m.detailIsEnded(), m.detailIsStale(), m.width, m.noColor)
	attributes := detailAttributes(*p, m.width, now)
	gradle := p.Type == "GRADLE_DAEMON"
	builds := []string(nil)
	if gradle {
		builds = detailBuilds(m, m.width)
	}
	log := []string(nil)
	if gradle {
		log = detailLog(m, m.width)
	}

	var b strings.Builder
	b.WriteString(header)
	b.WriteString("\n")
	if m.width >= 140 {
		available := max(1, m.height-5)
		attrWidth := max(28, m.width/4)
		resourceWidth := max(28, m.width/4)
		logWidth := m.width - attrWidth - resourceWidth - 2
		if len(log) == 0 {
			resourceWidth += logWidth + 1
			logWidth = 0
		}
		resource := detailResources(m, *p, resourceWidth)
		middle := detailPanel("RESOURCE TRENDS", resource, resourceWidth, available, m.detailPaneOffset(detailPaneResources), m.detailPane == detailPaneResources, m.noColor)
		if gradle {
			heights := splitDetailHeight(available, 2)
			resourceHeight, buildHeight := heights[0], heights[1]
			middle = joinVerticalPanes([][]string{
				detailPanel("RESOURCE TRENDS", resource, resourceWidth, resourceHeight, m.detailPaneOffset(detailPaneResources), m.detailPane == detailPaneResources, m.noColor),
				detailPanel("RECENT BUILDS", builds, resourceWidth, buildHeight, m.detailPaneOffset(detailPaneBuilds), m.detailPane == detailPaneBuilds, m.noColor),
			})
		}
		panes := [][]string{detailPanel("ATTRIBUTES", attributes, attrWidth, available, m.detailPaneOffset(detailPaneAttributes), m.detailPane == detailPaneAttributes, m.noColor)}
		panes = append(panes, middle)
		if logWidth > 0 {
			panes = append(panes, detailPanel("GRADLE LOG", log, logWidth, available, m.detailPaneOffset(detailPaneLog), m.detailPane == detailPaneLog, m.noColor))
		}
		b.WriteString(joinDetailPanes(panes, m.width))
	} else if m.width >= 120 {
		available := max(1, m.height-5)
		attrWidth := max(34, m.width/3)
		activityWidth := m.width - attrWidth - 1
		resource := detailResources(m, *p, activityWidth)
		stack := [][]string{detailPanel("RESOURCE TRENDS", resource, activityWidth, available, m.detailPaneOffset(detailPaneResources), m.detailPane == detailPaneResources, m.noColor)}
		if gradle {
			heights := splitDetailHeight(available, 3)
			resourceHeight, buildHeight, logHeight := heights[0], heights[1], heights[2]
			stack = [][]string{
				detailPanel("RESOURCE TRENDS", resource, activityWidth, resourceHeight, m.detailPaneOffset(detailPaneResources), m.detailPane == detailPaneResources, m.noColor),
				detailPanel("RECENT BUILDS", builds, activityWidth, buildHeight, m.detailPaneOffset(detailPaneBuilds), m.detailPane == detailPaneBuilds, m.noColor),
				detailPanel("GRADLE LOG", log, activityWidth, max(1, logHeight), m.detailPaneOffset(detailPaneLog), m.detailPane == detailPaneLog, m.noColor),
			}
		}
		panes := [][]string{
			detailPanel("ATTRIBUTES", attributes, attrWidth, available, m.detailPaneOffset(detailPaneAttributes), m.detailPane == detailPaneAttributes, m.noColor),
			joinVerticalPanes(stack),
		}
		b.WriteString(joinDetailPanes(panes, m.width))
	} else {
		resource := detailResources(m, *p, m.width)
		content := []string{"ATTRIBUTES"}
		content = append(content, attributes...)
		content = append(content, "", "RESOURCE TRENDS")
		content = append(content, resource...)
		if len(builds) > 0 {
			content = append(content, "", "Recent builds: / RECENT BUILDS")
			content = append(content, builds...)
		}
		if len(log) > 0 {
			content = append(content, "", "Recent log lines: / GRADLE LOG")
			content = append(content, log...)
		}
		section := detailPanel("PROCESS DETAILS", content, m.width, max(1, m.height-5), m.detailPaneOffset(detailPaneAttributes), true, m.noColor)
		for _, line := range section {
			b.WriteString(line)
			b.WriteByte('\n')
		}
	}
	b.WriteByte('\n')
	focus := detailPaneName(m.detailPane)
	b.WriteString(fitSegments([]string{
		"tab/←→ focus " + focus,
		"esc back",
		"q quit",
		"↑/↓ or j/k scroll",
		"pgup/pgdn page",
		"g/G top/bottom",
		"r refresh",
	}, "   ", m.width))
	return strings.TrimRight(b.String(), "\n")
}

func detailPaneName(pane detailPaneKind) string {
	switch pane {
	case detailPaneResources:
		return "resources"
	case detailPaneBuilds:
		return "builds"
	case detailPaneLog:
		return "log"
	default:
		return "attributes"
	}
}

func (m Model) detailIsEnded() bool {
	status := strings.ToLower(m.detailProcess.Status)
	if strings.Contains(status, "end") || strings.Contains(status, "terminated") || strings.Contains(status, "stopped") || strings.Contains(status, "exited") || strings.Contains(status, "dead") {
		return true
	}
	if len(m.processes) == 0 {
		return false
	}
	for _, p := range m.processes {
		if p.PID == m.detailProcess.PID && p.StartTimeMs == m.detailProcess.StartTimeMs {
			return false
		}
	}
	return true
}

func (m Model) detailIsStale() bool {
	return m.sampledAt > 0 && m.detailProcess.SampledAtMs > 0 && m.sampledAt > m.detailProcess.SampledAtMs
}

func detailHeader(p model.Process, now int64, ended, stale bool, width int, noColor bool) string {
	state := na(p.Status)
	if ended {
		state = "ENDED"
	}
	if stale {
		state += " · snapshot stale"
	}
	// Ordered by importance: trailing segments are dropped first on narrow terminals.
	segments := []string{
		fmt.Sprintf("%s  ·  pid %d", render.TypeDisplay(p.Type), p.PID),
		state,
		"RSS " + render.RSSText(p.RSSMemoryMB),
		"CPU " + render.CPUText(p.CPUPercent),
	}
	if project := render.ProjectName(p); project != "—" {
		segments = append(segments, project)
	}
	segments = append(segments, "GC "+render.GCText(p.GC), "uptime "+render.Uptime(p.StartTimeMs, now), na(p.Name))
	title := "GRADLE COMMANDER CENTER / PROCESS DETAILS"
	line := fitSegments(append([]string{title}, segments...), "  ·  ", max(1, width))
	if !strings.Contains(line, "pid ") {
		line = fitSegments(append([]string{"PROCESS DETAILS"}, segments...), "  ·  ", max(1, width))
	}
	if noColor {
		return line
	}
	return detailHeadingStyle(noColor).Render(line)
}

func detailAttributes(p model.Process, width int, now int64) []string {
	// Wide enough for the longest label ("Metaspace used", "Virtual memory").
	const labelWidth = 14
	field := func(label, value string) string {
		return fmt.Sprintf("  %-*s %s", labelWidth, label, value)
	}
	lines := []string{
		"Identity",
		field("Type", render.TypeDisplay(p.Type)),
		field("PID", fmt.Sprintf("%d", p.PID)),
		field("Name", na(p.Name)),
		field("Project", render.ProjectName(p)),
		field("Work dir", optionalStringText(p.WorkingDirectory)),
		field("Status", na(p.Status)),
		field("Start", formatStart(p.StartTimeMs)),
		field("Uptime", render.Uptime(p.StartTimeMs, now)),
		"",
		"JVM / memory",
		field("RSS", render.RSSText(p.RSSMemoryMB)),
		field("CPU", render.CPUText(p.CPUPercent)),
		field("GC", render.GCText(p.GC)),
		field("Xmx", render.HeapLimitText(p.MaxHeapMB)),
		field("Xms", optionalInt64Text(p.MinHeapMB, "MB")),
		field("Heap used", render.HeapText(p.HeapUsedMB)),
		field("Heap cmt", render.HeapText(p.HeapCommittedMB)),
		field("Heap max", render.HeapText(p.HeapMaxMB)),
		field("Heap %", render.HeapPercentText(p.HeapUsedMB, p.HeapMaxMB)),
		field("Metaspace used", optionalInt64Text(p.MetaspaceUsedMB, "MB")),
		"",
		"Diagnostics",
		field("Threads", optionalInt64Text(p.ThreadCount, "")),
		field("VM", optionalStringText(p.JavaVMName)),
		field("Java version", optionalStringText(p.JavaVersion)),
		field("OS", optionalStringText(p.OSName)+" / "+optionalStringText(p.OSArch)),
		field("Processors", optionalInt64Text(p.ActiveProcessorCount, "")),
		field("GC young", optionalInt64Text(p.YoungGCCount, "")+" / "+optionalInt64Text(p.YoungGCTimeMs, "ms")),
		field("GC old", optionalInt64Text(p.OldGCCount, "")+" / "+optionalInt64Text(p.OldGCTimeMs, "ms")),
		field("VM version", optionalStringText(p.JavaVMVersion)),
		field("Runtime", optionalStringText(p.JavaRuntimeVersion)),
		field("Vendor", optionalStringText(p.JavaVendor)),
		field("Open files", optionalInt64Text(p.OpenFileDescriptors, "")),
		field("Virtual memory", optionalInt64Text(p.VirtualMemoryMB, "MB")),
		field("Swap memory", optionalInt64Text(p.SwapMemoryMB, "MB")),
		field("Disk read", optionalInt64Text(p.ReadBytes, "bytes")),
		field("Disk write", optionalInt64Text(p.WriteBytes, "bytes")),
		field("Young GC time", optionalInt64Text(p.YoungGCTimeMs, "ms")),
		field("GC time (s)", "young "+render.GCTimeText(p.YoungGCTimeSeconds)+
			" · full "+render.GCTimeText(p.FullGCTimeSeconds)+
			" · conc "+render.GCTimeText(p.ConcurrentGCTimeSeconds)+
			" · total "+render.GCTimeText(p.TotalGCTimeSeconds)),
	}
	for i := range lines {
		lines[i] = truncateWidth(lines[i], max(1, width-2))
	}
	return lines
}

func detailResources(m Model, p model.Process, width int) []string {
	memory := analysis.Analyze(m.detailHistory, p.PID, p.StartTimeMs)
	lines := []string{"MEMORY TREND / RSS / heap history", memoryTrendLine("RSS", memory.RSS, width)}
	if memory.Heap.Available {
		lines = append(lines, memoryTrendLine("Heap", memory.Heap, width))
	} else {
		lines = append(lines, "  Heap  n/a (insufficient valid heap samples)")
	}
	lines = append(lines, "  Assessment: "+memoryAssessmentText(memory.Assessment))
	retentions := analysis.AnalyzeBuildRetention(m.detailHistory, analysisBuilds(m.detailBuilds.Builds), p.PID, p.StartTimeMs)
	for i, retention := range retentions {
		if i == 3 {
			break
		}
		lines = append(lines, fmt.Sprintf("  Retention %s → %s (%s)", render.RSSText(retention.BeforeMB), render.RSSText(retention.AfterMB), signedMemory(retention.RetainedMB)))
	}
	if trend := rssTrend(m.detailHistory, p.PID); trend != "" {
		lines = append(lines, "  RSS trend: "+trend)
	} else {
		lines = append(lines, "  RSS trend: n/a")
	}
	return lines
}

func detailBuilds(m Model, width int) []string {
	if m.detailLoading {
		return []string{"Loading…"}
	}
	if m.detailError != "" && len(m.detailBuilds.Builds) == 0 {
		return []string{truncateWidth("Unavailable: "+oneLine(m.detailError), max(1, width-2))}
	}
	if len(m.detailBuilds.Builds) == 0 {
		return []string{"No build history for this daemon."}
	}
	lines := make([]string, 0, 4)
	for i, build := range m.detailBuilds.Builds {
		if i == 3 {
			break
		}
		for _, field := range buildSummaryLines(build) {
			lines = append(lines, truncateWidth(field, max(1, width-2)))
		}
	}
	return lines
}

func buildSummaryLines(build client.BuildRecord) []string {
	duration := "-"
	if build.DurationSeconds != nil {
		duration = fmt.Sprintf("%.1fs", math.Round(*build.DurationSeconds*10)/10)
	}
	return []string{na(build.FinalStatus), "project=" + na(build.ProjectPath), "duration=" + duration, "start=" + formatStart(build.StartTimeMs), "source=" + na(build.InferredSource), "agent=" + na(build.Agent)}
}

func detailLog(m Model, width int) []string {
	if m.detailLoading {
		return []string{"Loading…"}
	}
	if m.detailError != "" && m.detailTail == nil {
		return []string{"Unavailable: " + oneLine(m.detailError)}
	}
	if m.detailTail == nil {
		return []string{"No matching Gradle daemon log is available."}
	}
	if len(m.detailTail.Lines) == 0 {
		return []string{"Gradle log is available but empty."}
	}
	lines := m.detailTail.Lines
	result := make([]string, 0, len(lines)+1)
	for _, line := range lines {
		result = append(result, truncateWidth(line, max(1, width-2)))
	}
	return result
}

func splitDetailHeight(total, panes int) []int {
	if panes <= 1 {
		return []int{total}
	}
	heights := make([]int, panes)
	remaining := total
	for i := range heights {
		remainingPanes := panes - i
		heights[i] = max(1, remaining/remainingPanes)
		remaining -= heights[i]
	}
	return heights
}

func detailPanel(title string, content []string, width, height, offset int, active, noColor bool) []string {
	if width < 1 {
		return nil
	}
	inner := max(1, width-2)
	viewport := max(1, height-2)
	if height <= 0 {
		viewport = len(content)
	}
	maxOffset := max(0, len(content)-viewport)
	offset = min(max(0, offset), maxOffset)
	end := min(len(content), offset+viewport)
	visible := content[offset:end]
	topLeft, topRight, bottomLeft, bottomRight, horizontal, vertical := "┌", "┐", "└", "┘", "─", "│"
	if active {
		topLeft, topRight, bottomLeft, bottomRight, horizontal, vertical = "╔", "╗", "╚", "╝", "═", "║"
	}
	titleText := truncateWidth(horizontal+" "+title+" ", inner)
	if active {
		titleText = activePaneStyle(noColor).Render(titleText)
	}
	frame := activePaneBorderStyle(noColor)
	remaining := strings.Repeat(horizontal, max(0, inner-terminalansi.StringWidth(titleText)))
	top := topLeft + titleText + remaining + topRight
	if active {
		top = frame.Render(topLeft) + titleText + frame.Render(remaining+topRight)
	}
	lines := []string{top}
	for i, line := range visible {
		lines = append(lines, detailPanelLine(line, inner, offset+i, len(content), viewport, vertical, active, noColor))
	}
	for len(lines) < max(1, height-1) {
		lines = append(lines, detailPanelLine("", inner, offset+len(lines)-1, len(content), viewport, vertical, active, noColor))
	}
	bottom := bottomLeft + strings.Repeat(horizontal, inner) + bottomRight
	if active {
		bottom = frame.Render(bottom)
	}
	lines = append(lines, bottom)
	return lines
}

func detailPanelLine(line string, inner, row, total, viewport int, vertical string, active, noColor bool) string {
	textWidth := max(1, inner-3)
	scroll := " "
	if total > viewport {
		thumbSize := max(1, viewport*viewport/total)
		maxStart := viewport - thumbSize
		maxOffset := total - viewport
		offset := row
		if offset > maxOffset {
			offset = maxOffset
		}
		thumbStart := 0
		if maxOffset > 0 {
			thumbStart = offset * maxStart / maxOffset
		}
		if row >= thumbStart && row < thumbStart+thumbSize {
			scroll = "█"
		} else {
			scroll = "░"
		}
	}
	text := truncateWidth(line, textWidth)
	left, right := vertical, vertical
	if active {
		border := activePaneBorderStyle(noColor)
		left, right = border.Render(left), border.Render(right)
	}
	return left + " " + text + strings.Repeat(" ", max(0, textWidth-terminalansi.StringWidth(text))) + " " + scroll + right
}

func joinVerticalPanes(panes [][]string) []string {
	var lines []string
	for _, pane := range panes {
		lines = append(lines, pane...)
	}
	return lines
}

func joinDetailPanes(panes [][]string, width int) string {
	maxLines := 0
	for _, pane := range panes {
		if len(pane) > maxLines {
			maxLines = len(pane)
		}
	}
	var b strings.Builder
	for row := 0; row < maxLines; row++ {
		parts := make([]string, 0, len(panes))
		for _, pane := range panes {
			if row < len(pane) {
				parts = append(parts, pane[row])
			}
		}
		line := strings.Join(parts, " ")
		b.WriteString(truncateWidth(line, width))
		if row+1 < maxLines {
			b.WriteByte('\n')
		}
	}
	return b.String()
}

func analysisBuilds(builds []client.BuildRecord) []analysis.Build {
	result := make([]analysis.Build, 0, len(builds))
	for _, build := range builds {
		result = append(result, analysis.Build{ID: build.BuildID, PID: int32(build.DaemonPID), StartTimeMs: build.StartTimeMs, EndTimeMs: build.EndTimeMs})
	}
	return result
}

func memoryTrendLine(label string, metric analysis.Metric, width int) string {
	if !metric.Available {
		return "  " + label + "  n/a"
	}
	trend := memorySparkline(metric.Values, width)
	return truncateWidth(fmt.Sprintf("  %-5s %s  %s → %s  %s", label, trend, render.RSSText(metric.BaselineMB), render.RSSText(metric.CurrentMB), signedMemory(metric.AbsoluteGrowthMB)), max(1, width))
}

func memorySparkline(values []int64, width int) string {
	if len(values) == 0 {
		return "n/a"
	}
	maxValues := 16
	if width < 60 {
		maxValues = 8
	}
	if len(values) > maxValues {
		values = values[len(values)-maxValues:]
	}
	minValue, maxValue := values[0], values[0]
	for _, value := range values[1:] {
		if value < minValue {
			minValue = value
		}
		if value > maxValue {
			maxValue = value
		}
	}
	levels := []rune("▁▂▃▄▅▆▇█")
	var b strings.Builder
	for _, value := range values {
		index := 0
		if maxValue > minValue {
			index = int((value - minValue) * int64(len(levels)-1) / (maxValue - minValue))
		}
		b.WriteRune(levels[index])
	}
	return b.String()
}

func signedMemory(value int64) string {
	if value > 0 {
		return "+" + render.RSSText(value)
	}
	if value < 0 {
		return "-" + render.RSSText(-value)
	}
	return render.RSSText(0)
}

func memoryAssessmentText(assessment analysis.Assessment) string {
	switch assessment {
	case analysis.Growing, analysis.HighGrowth:
		return "⚠ Sustained memory growth detected"
	case analysis.Stable:
		return "Stable in recent window"
	default:
		return "INSUFFICIENT_DATA"
	}
}

func buildSummary(build client.BuildRecord) string {
	duration := "-"
	if build.DurationSeconds != nil {
		duration = fmt.Sprintf("%.1fs", math.Round(*build.DurationSeconds*10)/10)
	}
	return fmt.Sprintf("%s project=%s duration=%s start=%s source=%s agent=%s",
		na(build.FinalStatus), na(build.ProjectPath), duration, formatStart(build.StartTimeMs),
		na(build.InferredSource), na(build.Agent))
}

func rssTrend(samples []model.Process, pid int32) string {
	values := make([]int64, 0, len(samples))
	for _, sample := range samples {
		if sample.PID == pid {
			values = append(values, sample.RSSMemoryMB)
		}
	}
	if len(values) < 2 {
		return ""
	}
	if len(values) > 24 {
		values = values[len(values)-24:]
	}
	minValue, maxValue := values[0], values[0]
	for _, value := range values[1:] {
		if value < minValue {
			minValue = value
		}
		if value > maxValue {
			maxValue = value
		}
	}
	levels := []rune("▁▂▃▄▅▆▇█")
	var b strings.Builder
	for _, value := range values {
		index := 0
		if maxValue > minValue {
			index = int((value - minValue) * int64(len(levels)-1) / (maxValue - minValue))
		}
		b.WriteRune(levels[index])
	}
	return b.String() + fmt.Sprintf("  %s–%s", render.RSSText(minValue), render.RSSText(maxValue))
}

func writeField(b *strings.Builder, label, value string) {
	fmt.Fprintf(b, "  %-14s %s\n", label+":", value)
}

func writeDetailField(b *strings.Builder, width int, label, value string) {
	writeField(b, label, truncateWidth(value, max(1, width-18)))
}

func na(s string) string {
	if strings.TrimSpace(s) == "" {
		return "n/a"
	}
	return s
}

func optionalInt64Text(v *int64, unit string) string {
	if v == nil {
		return "n/a"
	}
	if unit == "" {
		return fmt.Sprint(*v)
	}
	return fmt.Sprintf("%d %s", *v, unit)
}

func optionalStringText(v *string) string {
	if v == nil || strings.TrimSpace(*v) == "" {
		return "n/a"
	}
	return *v
}

func formatStart(ms int64) string {
	if ms <= 0 {
		return "n/a"
	}
	return time.UnixMilli(ms).Format(time.RFC3339)
}
