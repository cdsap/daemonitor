package tui

import (
	"fmt"
	"maps"
	"math"
	"slices"
	"strings"
	"time"

	terminalansi "github.com/charmbracelet/x/ansi"

	"github.com/cdsap/daemonitor/cored/internal/analysis"
	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
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
	// Keep operator-facing process fields before the expanded telemetry and its
	// capability matrix so the details pane opens on the most useful context.
	attributes := append(detailAttributes(m, *p, m.width, now), detailTelemetry(m, *p, m.width)...)
	gradle := p.Type == "GRADLE_DAEMON"
	bazel := p.Type == "BAZEL_SERVER"
	showBuilds := gradle || bazel
	showLog := gradle || bazel
	logTitle := "GRADLE LOG"
	if bazel {
		logTitle = "BAZEL LOG"
	}
	builds := []string(nil)
	if showBuilds {
		builds = detailBuilds(m, m.width)
	}
	log := []string(nil)
	if showLog {
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
		middle := detailPanel("RESOURCE TRENDS", resource, resourceWidth, available, m.detailPaneOffset(detailPaneResources), 0, m.detailPane == detailPaneResources, m.noColor)
		if showBuilds {
			heights := splitDetailHeight(available, 2)
			resourceHeight, buildHeight := heights[0], heights[1]
			middle = joinVerticalPanes([][]string{
				detailPanel("RESOURCE TRENDS", resource, resourceWidth, resourceHeight, m.detailPaneOffset(detailPaneResources), 0, m.detailPane == detailPaneResources, m.noColor),
				detailPanel("RECENT BUILDS", builds, resourceWidth, buildHeight, m.detailPaneOffset(detailPaneBuilds), 0, m.detailPane == detailPaneBuilds, m.noColor),
			})
		}
		panes := [][]string{detailPanel("ATTRIBUTES", attributes, attrWidth, available, m.detailPaneOffset(detailPaneAttributes), 0, m.detailPane == detailPaneAttributes, m.noColor)}
		panes = append(panes, middle)
		if logWidth > 0 {
			panes = append(panes, detailPanel(logTitle, log, logWidth, available, m.detailPaneOffset(detailPaneLog), m.detailPaneHorizontalScroll[detailPaneLog], m.detailPane == detailPaneLog, m.noColor))
		}
		b.WriteString(joinDetailPanes(panes, m.width))
	} else if m.width >= 120 {
		available := max(1, m.height-5)
		attrWidth := max(34, m.width/3)
		activityWidth := m.width - attrWidth - 1
		resource := detailResources(m, *p, activityWidth)
		stack := [][]string{detailPanel("RESOURCE TRENDS", resource, activityWidth, available, m.detailPaneOffset(detailPaneResources), 0, m.detailPane == detailPaneResources, m.noColor)}
		if showBuilds {
			if showLog {
				heights := splitDetailHeight(available, 3)
				resourceHeight, buildHeight, logHeight := heights[0], heights[1], heights[2]
				stack = [][]string{
					detailPanel("RESOURCE TRENDS", resource, activityWidth, resourceHeight, m.detailPaneOffset(detailPaneResources), 0, m.detailPane == detailPaneResources, m.noColor),
					detailPanel("RECENT BUILDS", builds, activityWidth, buildHeight, m.detailPaneOffset(detailPaneBuilds), 0, m.detailPane == detailPaneBuilds, m.noColor),
					detailPanel(logTitle, log, activityWidth, max(1, logHeight), m.detailPaneOffset(detailPaneLog), m.detailPaneHorizontalScroll[detailPaneLog], m.detailPane == detailPaneLog, m.noColor),
				}
			} else {
				heights := splitDetailHeight(available, 2)
				resourceHeight, buildHeight := heights[0], heights[1]
				stack = [][]string{
					detailPanel("RESOURCE TRENDS", resource, activityWidth, resourceHeight, m.detailPaneOffset(detailPaneResources), 0, m.detailPane == detailPaneResources, m.noColor),
					detailPanel("RECENT BUILDS", builds, activityWidth, buildHeight, m.detailPaneOffset(detailPaneBuilds), 0, m.detailPane == detailPaneBuilds, m.noColor),
				}
			}
		}
		panes := [][]string{
			detailPanel("ATTRIBUTES", attributes, attrWidth, available, m.detailPaneOffset(detailPaneAttributes), 0, m.detailPane == detailPaneAttributes, m.noColor),
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
			content = append(content, "", "Recent log lines: / "+logTitle)
			content = append(content, log...)
		}
		section := detailPanel("PROCESS DETAILS", content, m.width, max(1, m.height-5), m.detailPaneOffset(detailPaneAttributes), 0, true, m.noColor)
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
		"shift+←→ horizontal log scroll",
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

// detailTelemetry is deliberately confined to the selected-process view. The
// process table remains the compact operator overview while this section gives
// expanded telemetry a place to grow without changing its columns.
func detailField(label, value string) string {
	// Wide enough for the longest shared labels ("Metaspace used", "Virtual memory").
	const labelWidth = 14
	return fmt.Sprintf("  %-*s %s", labelWidth, label, value)
}

func detailTelemetry(m Model, p model.Process, width int) []string {
	lines := []string{""}
	if p.Type == "GRADLE_DAEMON" {
		version, path := "", ""
		if m.detailLog != nil {
			version, path = m.detailLog.GradleVersion, m.detailLog.Path
		}
		lines = append(lines,
			"GRADLE DAEMON LOG",
			detailField("Gradle version", na(version)),
			detailField("Log path", na(path)),
			"",
		)
	}
	lines = append(lines,
		"OS ACTIVITY",
		detailField("Resource scope", resourceScopeText(p)),
		detailField("Page faults", "minor "+optionalInt64Text(p.MinorPageFaults, "")+" / major "+optionalInt64Text(p.MajorPageFaults, "")),
		detailField("Context switch", "voluntary "+optionalInt64Text(p.VoluntaryContextSwitches, "")+" / involuntary "+optionalInt64Text(p.InvoluntaryContextSwitches, "")),
		detailField("CPU seconds", "user "+optionalFloat64Text(p.CPUUserSecondsTotal, "s")+" / system "+optionalFloat64Text(p.CPUSystemSecondsTotal, "s")+" / total "+optionalFloat64Text(p.CPUTotalSecondsTotal, "s")),
		detailField("CPU cores", optionalFloat64Text(p.CPUCoresUsed, "")),
		"",
		"JVM MEMORY POOLS",
		detailField("Heap used", render.HeapText(p.HeapUsedMB)),
		detailField("Heap committed", render.HeapText(p.HeapCommittedMB)),
		detailField("Heap max", render.HeapText(p.HeapMaxMB)),
		detailField("Metaspace used", optionalInt64Text(p.MetaspaceUsedMB, "MB")),
		detailField("Metaspace cmt", optionalInt64Text(p.MetaspaceCommittedMB, "MB")),
		detailField("Other pools", memoryPoolsText(p.MemoryPools)),
		"",
		"NATIVE MEMORY",
		detailField("Resident", optionalBytesText(p.ResidentBytes)),
		detailField("Virtual", optionalBytesText(p.VirtualBytes)),
		detailField("Private committed", optionalBytesText(p.PrivateCommittedBytes)),
		detailField("Proportional (PSS)", optionalBytesText(p.ProportionalBytes)),
		detailField("Physical footprint", optionalBytesText(p.PhysicalFootprintBytes)),
		detailField("Swap", optionalBytesText(p.SwapBytes)),
		detailField("Peak resident", optionalBytesText(p.PeakResidentBytes)),
		"",
		"GC COUNTERS / RATES",
		detailField("Young count", optionalInt64Text(p.YoungGCCount, "")),
		detailField("Old count", optionalInt64Text(p.OldGCCount, "")),
		detailField("Total time", render.GCTimeText(p.TotalGCTimeSeconds)),
		detailField("Rate (recent)", gcRateText(m.detailHistory, p)),
		detailField("Full/conc time", render.GCTimeText(p.FullGCTimeSeconds)+" / "+render.GCTimeText(p.ConcurrentGCTimeSeconds)),
		"",
		"JIT / CODE CACHE",
		detailField("Compilation", jitText(p.JIT)),
		detailField("Code cache", memoryPoolsText(p.CodeCache)),
		"",
		"PROCESS LIFECYCLE",
		detailField("Status", na(p.Status)),
		detailField("Start", formatStart(p.StartTimeMs)),
		detailField("Sampled", formatStart(p.SampledAtMs)),
		detailField("End", "n/a (active or unavailable)"),
		"",
		"HOST RESOURCE CONTEXT",
	)
	if m.detailHostError != nil {
		lines = append(lines, detailField("Host metrics", "unavailable: "+oneLine(m.detailHostError.Error())))
	} else {
		lines = append(lines,
			detailField("CPUs", optionalMetricInt(m.detailHost.LogicalProcessors, "")),
			detailField("CPU usage", optionalMetricReal(m.detailHost.CPUUsagePercent, "%")),
			detailField("Memory used", optionalMetricBytes(m.detailHost.MemoryUsedBytes)),
			detailField("Memory avail", optionalMetricBytes(m.detailHost.MemoryAvailableBytes)),
			detailField("Memory total", optionalMetricBytes(m.detailHost.MemoryTotalBytes)),
		)
	}
	lines = append(lines, "", "COLLECTION CAPABILITIES")
	if m.detailCapabilitiesError != nil {
		lines = append(lines, detailField("Capabilities", "unavailable: "+oneLine(m.detailCapabilitiesError.Error())))
	} else if len(m.detailCapabilities.Metrics) == 0 {
		lines = append(lines, detailField("Capabilities", "n/a"))
	} else {
		for _, capability := range m.detailCapabilities.Metrics {
			lines = append(lines, detailField(capability.MetricName, capability.Availability))
		}
	}
	for i := range lines {
		lines[i] = truncateWidth(lines[i], max(1, width-2))
	}
	return lines
}

func memoryPoolsText(pools []model.JVMMemoryPool) string {
	if len(pools) == 0 {
		return "n/a"
	}
	parts := make([]string, 0, len(pools))
	for _, pool := range pools {
		name := strings.TrimSpace(pool.Name)
		if name == "" {
			name = "unnamed"
		}
		value := formatJVMRawValue(pool.Used) + "/" + formatJVMRawValue(pool.Committed)
		if pool.Max.Value != 0 {
			value += " (max " + formatJVMRawValue(pool.Max) + ")"
		}
		if pool.Peak != nil {
			value += " (peak " + formatJVMRawValue(*pool.Peak) + ")"
		}
		parts = append(parts, name+" "+value)
	}
	return strings.Join(parts, "; ")
}

func formatJVMRawValue(value model.JVMRawValue) string {
	if value.Unit == "" {
		return "n/a"
	}
	if value.Value == math.Trunc(value.Value) {
		return fmt.Sprintf("%.0f%s", value.Value, value.Unit)
	}
	return fmt.Sprintf("%.1f%s", value.Value, value.Unit)
}

func jitText(jit *model.JITMetrics) string {
	if jit == nil {
		return "n/a"
	}
	parts := make([]string, 0, 4)
	if jit.CompiledCount != nil {
		parts = append(parts, fmt.Sprintf("%d compiled", *jit.CompiledCount))
	}
	if jit.FailedCount != nil {
		parts = append(parts, fmt.Sprintf("%d failed", *jit.FailedCount))
	}
	if jit.InvalidCount != nil {
		parts = append(parts, fmt.Sprintf("%d invalid", *jit.InvalidCount))
	}
	if jit.CompilationTimeSeconds != nil {
		parts = append(parts, fmt.Sprintf("%.3f s", *jit.CompilationTimeSeconds))
	}
	if len(parts) == 0 {
		return "n/a"
	}
	return strings.Join(parts, ", ")
}

func resourceScopeText(p model.Process) string {
	if p.ResourceScope != "" {
		return string(p.ResourceScope)
	}
	return na(string(p.Resource.Scope))
}

func optionalMetricInt(metric model.IntMetric, unit string) string {
	if metric.Value == nil {
		return "n/a"
	}
	return fmt.Sprintf("%d%s", *metric.Value, unit)
}

func optionalFloat64Text(v *float64, unit string) string {
	if v == nil {
		return "n/a"
	}
	if unit == "" {
		return fmt.Sprintf("%.1f", *v)
	}
	return fmt.Sprintf("%.1f %s", *v, unit)
}

func optionalMetricReal(metric model.RealMetric, unit string) string {
	if metric.Value == nil {
		return "n/a"
	}
	return fmt.Sprintf("%.1f%s", *metric.Value, unit)
}

func optionalMetricBytes(metric model.IntMetric) string {
	if metric.Value == nil {
		return "n/a"
	}
	return render.RSSText(*metric.Value / (1024 * 1024))
}

func optionalBytesText(v *int64) string {
	if v == nil {
		return "n/a"
	}
	return formatBytes(*v)
}

func formatBytes(bytes int64) string {
	units := []string{"B", "KB", "MB", "GB"}
	value := float64(bytes)
	unit := 0
	for value >= 1024 && unit < len(units)-1 {
		value /= 1024
		unit++
	}
	formatted := fmt.Sprintf("%.1f", value)
	formatted = strings.TrimRight(strings.TrimRight(formatted, "0"), ".")
	return formatted + " " + units[unit]
}

func gcRateText(history []model.Process, p model.Process) string {
	var first, last *model.Process
	for i := range history {
		sample := history[i]
		if sample.PID != p.PID || sample.StartTimeMs != p.StartTimeMs || sample.YoungGCCount == nil {
			continue
		}
		if first == nil {
			first = &sample
		}
		last = &sample
	}
	if first == nil || last == nil || first == last || last.SampledAtMs <= first.SampledAtMs || *last.YoungGCCount < *first.YoungGCCount {
		return "n/a"
	}
	return fmt.Sprintf("%.2f young/s", float64(*last.YoungGCCount-*first.YoungGCCount)/float64(last.SampledAtMs-first.SampledAtMs)*1000)
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
	title := detailTitle(p.Type)
	line := fitSegments(append([]string{title}, segments...), "  ·  ", max(1, width))
	if !strings.Contains(line, "pid ") {
		line = fitSegments(append([]string{"PROCESS DETAILS"}, segments...), "  ·  ", max(1, width))
	}
	if noColor {
		return line
	}
	return detailHeadingStyle(noColor).Render(line)
}

func detailTitle(kind string) string {
	switch kind {
	case "GRADLE_DAEMON":
		return "GRADLE DAEMON / PROCESS DETAILS"
	case "BAZEL_SERVER":
		return "BAZEL SERVER / PROCESS DETAILS"
	default:
		return "PROCESS DETAILS"
	}
}

func detailAttributes(m Model, p model.Process, width int, now int64) []string {
	lines := []string{
		"Identity",
		detailField("Type", render.TypeDisplay(p.Type)),
		detailField("PID", fmt.Sprintf("%d", p.PID)),
		detailField("Name", na(p.Name)),
		detailField("Project", render.ProjectName(p)),
		detailField("Work dir", optionalStringText(p.WorkingDirectory)),
		detailField("Status", na(p.Status)),
		detailField("Start", formatStart(p.StartTimeMs)),
		detailField("Uptime", render.Uptime(p.StartTimeMs, now)),
	}
	if p.Type == "BAZEL_SERVER" {
		source, path := bazelLogMetadata(m)
		lines = append(lines,
			"",
			"BAZEL SERVER",
			detailField("Bazel version", na(bazelVersionFromBuilds(m.detailBuilds.Builds))),
			detailField("Workspace", render.ProjectName(p)),
			detailField("Output base", na(p.BazelOutputBase)),
			detailField("Install base", na(p.BazelInstallBase)),
			detailField("Workers", fmt.Sprintf("%d", p.ActiveWorkerCount)),
			detailField("Sandboxed", fmt.Sprintf("%d", p.ActiveSandboxedActionCount)),
			detailField("Subtree RSS", optionalRSSText(p.SubtreeRSSMemoryMB)),
			detailField("Subtree CPU", render.CPUText(p.SubtreeCPUPercent)),
			detailField("Log source", na(source)),
			detailField("Log path", na(path)),
		)
	}
	lines = append(lines,
		"",
		"JVM / memory",
		detailField("RSS", render.RSSText(p.RSSMemoryMB)),
		detailField("CPU", render.CPUText(p.CPUPercent)),
		detailField("GC", render.GCText(p.GC)),
		detailField("Xmx", render.HeapLimitText(p.MaxHeapMB)),
		detailField("Xms", optionalInt64Text(p.MinHeapMB, "MB")),
		detailField("Heap used", render.HeapText(p.HeapUsedMB)),
		detailField("Heap cmt", render.HeapText(p.HeapCommittedMB)),
		detailField("Heap max", render.HeapText(p.HeapMaxMB)),
		detailField("Heap %", render.HeapPercentText(p.HeapUsedMB, p.HeapMaxMB)),
		detailField("Metaspace used", optionalInt64Text(p.MetaspaceUsedMB, "MB")),
		"",
		"Diagnostics",
		detailField("Threads", optionalInt64Text(p.ThreadCount, "")),
		detailField("VM", optionalStringText(p.JavaVMName)),
		detailField("Java version", optionalStringText(p.JavaVersion)),
		detailField("OS", optionalStringText(p.OSName)+" / "+optionalStringText(p.OSArch)),
		detailField("Processors", optionalInt64Text(p.ActiveProcessorCount, "")),
		detailField("GC young", optionalInt64Text(p.YoungGCCount, "")+" / "+optionalInt64Text(p.YoungGCTimeMs, "ms")),
		detailField("GC old", optionalInt64Text(p.OldGCCount, "")+" / "+optionalInt64Text(p.OldGCTimeMs, "ms")),
		detailField("VM version", optionalStringText(p.JavaVMVersion)),
		detailField("Runtime", optionalStringText(p.JavaRuntimeVersion)),
		detailField("Vendor", optionalStringText(p.JavaVendor)),
		detailField("Open files", optionalInt64Text(p.OpenFileDescriptors, "")),
		detailField("Virtual memory", optionalInt64Text(p.VirtualMemoryMB, "MB")),
		detailField("Swap memory", optionalInt64Text(p.SwapMemoryMB, "MB")),
		detailField("Disk read", optionalInt64Text(p.ReadBytes, "bytes")),
		detailField("Disk write", optionalInt64Text(p.WriteBytes, "bytes")),
		detailField("Young GC time", optionalInt64Text(p.YoungGCTimeMs, "ms")),
		detailField("GC time (s)", "young "+render.GCTimeText(p.YoungGCTimeSeconds)+
			" · full "+render.GCTimeText(p.FullGCTimeSeconds)+
			" · conc "+render.GCTimeText(p.ConcurrentGCTimeSeconds)+
			" · total "+render.GCTimeText(p.TotalGCTimeSeconds)),
	)
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
		if retention.HeapBeforeMB != nil && retention.HeapAfterMB != nil && retention.HeapRetainedMB != nil {
			lines = append(lines, fmt.Sprintf("  Heap %s → %s (%s)", render.RSSText(*retention.HeapBeforeMB), render.RSSText(*retention.HeapAfterMB), signedMemory(*retention.HeapRetainedMB)))
		}
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
		if m.detailProcess != nil && m.detailProcess.Type == "BAZEL_SERVER" {
			return []string{
				"No Bazel builds captured yet.",
				"No command.profile.gz or BEP.",
			}
		}
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
	source := na(build.InferredSource)
	if build.Tool != "" && !strings.EqualFold(build.Tool, "GRADLE") {
		source = strings.ToLower(build.Tool) + "/" + source
	}
	lines := []string{na(build.FinalStatus), "project=" + na(build.ProjectPath), "duration=" + duration, "start=" + formatStart(build.StartTimeMs), "source=" + source, "agent=" + na(build.Agent)}
	if strings.EqualFold(build.Tool, "BAZEL") {
		lines = append(lines, bazelBuildMetricLines(build)...)
	}
	return lines
}

func bazelLogMetadata(m Model) (source, path string) {
	if m.detailTail != nil {
		source, path = m.detailTail.Source, m.detailTail.Path
	}
	if m.detailLog != nil {
		if source == "" {
			source = m.detailLog.Source
		}
		if path == "" {
			path = m.detailLog.Path
		}
	}
	return source, path
}

func bazelVersionFromBuilds(builds []client.BuildRecord) string {
	for _, b := range builds {
		if b.BazelDetails != nil && strings.TrimSpace(b.BazelDetails.BazelVersion) != "" {
			return strings.TrimSpace(b.BazelDetails.BazelVersion)
		}
		if b.BazelProfile != nil && strings.TrimSpace(b.BazelProfile.BazelVersion) != "" {
			return strings.TrimSpace(b.BazelProfile.BazelVersion)
		}
	}
	return ""
}

func optionalRSSText(v *int64) string {
	if v == nil {
		return "n/a"
	}
	return render.RSSText(*v)
}

func bazelBuildMetricLines(build client.BuildRecord) []string {
	var lines []string
	if build.PeakMemoryMB != nil {
		lines = append(lines, "peak_rss="+render.RSSText(*build.PeakMemoryMB))
	}
	if phases := bazelPhaseSummary(build); phases != "" {
		lines = append(lines, "phases="+phases)
	}
	if build.BazelDetails != nil {
		m := build.BazelDetails.Metrics
		if actions := bazelActionSummary(m.Actions); actions != "" {
			lines = append(lines, "actions="+actions)
		}
		if len(m.Workers) > 0 {
			lines = append(lines, fmt.Sprintf("workers=%d", len(m.Workers)))
		}
		if m.TestTotals != nil && m.TestTotals.Total > 0 {
			lines = append(lines, fmt.Sprintf("tests=%d pass/%d fail/%d cached", m.TestTotals.Passed, m.TestTotals.Failed, m.TestTotals.Cached))
		}
		if mem := bazelMemorySummary(m.Memory); mem != "" {
			lines = append(lines, "memory="+mem)
		}
	}
	if win := bazelWindowSummary(build.WindowSummaries); win != "" {
		lines = append(lines, "window="+win)
	}
	return lines
}

func bazelPhaseSummary(build client.BuildRecord) string {
	var parts []string
	if build.BazelDetails != nil && build.BazelDetails.Metrics.Timing != nil {
		t := build.BazelDetails.Metrics.Timing
		if t.AnalysisPhaseMs != nil {
			parts = append(parts, fmt.Sprintf("analysis %.1fs", float64(*t.AnalysisPhaseMs)/1000))
		}
		if t.ExecutionPhaseMs != nil {
			parts = append(parts, fmt.Sprintf("exec %.1fs", float64(*t.ExecutionPhaseMs)/1000))
		}
		if t.WallTimeMs != nil && len(parts) == 0 {
			parts = append(parts, fmt.Sprintf("wall %.1fs", float64(*t.WallTimeMs)/1000))
		}
	}
	if len(parts) == 0 && build.BazelProfile != nil && len(build.BazelProfile.PhaseSeconds) > 0 {
		keys := slices.Sorted(maps.Keys(build.BazelProfile.PhaseSeconds))
		for _, k := range keys {
			if len(parts) == 3 {
				break
			}
			parts = append(parts, fmt.Sprintf("%s %.1fs", k, build.BazelProfile.PhaseSeconds[k]))
		}
	}
	if build.BazelProfile != nil && build.BazelProfile.CriticalPathSeconds > 0 {
		parts = append(parts, fmt.Sprintf("crit %.1fs", build.BazelProfile.CriticalPathSeconds))
	}
	return strings.Join(parts, ", ")
}

func bazelActionSummary(actions *builds.BazelActionSummary) string {
	if actions == nil {
		return ""
	}
	var parts []string
	if actions.Executed != nil {
		parts = append(parts, fmt.Sprintf("exec %d", *actions.Executed))
	}
	if actions.RemoteCacheHits != nil && *actions.RemoteCacheHits > 0 {
		parts = append(parts, fmt.Sprintf("remote_hit %d", *actions.RemoteCacheHits))
	}
	if actions.LocalCacheHits != nil && *actions.LocalCacheHits > 0 {
		parts = append(parts, fmt.Sprintf("local_hit %d", *actions.LocalCacheHits))
	}
	if actions.LocalCacheMisses != nil && *actions.LocalCacheMisses > 0 {
		parts = append(parts, fmt.Sprintf("miss %d", *actions.LocalCacheMisses))
	}
	return strings.Join(parts, ", ")
}

func bazelMemorySummary(mem *builds.BazelMemoryMetrics) string {
	if mem == nil {
		return ""
	}
	var parts []string
	if mem.UsedHeapPostBuildBytes != nil {
		parts = append(parts, "post_heap "+formatBytes(*mem.UsedHeapPostBuildBytes))
	}
	if mem.PeakPostGCHeapBytes != nil {
		parts = append(parts, "peak_gc_heap "+formatBytes(*mem.PeakPostGCHeapBytes))
	}
	return strings.Join(parts, ", ")
}

func bazelWindowSummary(summaries []sessions.RSSSummary) string {
	if len(summaries) == 0 {
		return ""
	}
	selected := summaries[0]
	for _, s := range summaries {
		if s.ProcessType == "BAZEL_SERVER" {
			selected = s
			break
		}
	}
	parts := []string{"rss " + signedMemory(selected.DeltaRSSMB)}
	if selected.DeltaHeapUsedMB != nil {
		parts = append(parts, "heap "+signedMemory(*selected.DeltaHeapUsedMB))
	}
	if selected.DeltaTotalGCCount != nil {
		parts = append(parts, fmt.Sprintf("gc %+d", *selected.DeltaTotalGCCount))
	}
	return strings.Join(parts, ", ")
}

func detailLog(m Model, width int) []string {
	bazel := m.detailProcess != nil && m.detailProcess.Type == "BAZEL_SERVER"
	if m.detailLoading {
		return []string{"Loading…"}
	}
	if m.detailError != "" && m.detailTail == nil {
		return []string{"Unavailable: " + oneLine(m.detailError)}
	}
	if m.detailTail == nil {
		if bazel {
			return []string{"No Bazel log available yet (command.log / server/jvm.out)."}
		}
		return []string{"No matching Gradle daemon log is available."}
	}
	if len(m.detailTail.Lines) == 0 {
		if bazel {
			return []string{"Bazel log is available but empty."}
		}
		return []string{"Gradle log is available but empty."}
	}
	lines := m.detailTail.Lines
	result := make([]string, 0, len(lines)+1)
	for _, line := range lines {
		result = append(result, line)
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

func detailPanel(title string, content []string, width, height, offset, horizontalOffset int, active, noColor bool) []string {
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
	maxHorizontalOffset := 0
	for _, line := range content {
		maxHorizontalOffset = max(maxHorizontalOffset, terminalansi.StringWidth(line)-max(1, inner-3))
	}
	horizontalOffset = min(max(0, horizontalOffset), maxHorizontalOffset)
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
		lines = append(lines, detailPanelLine(line, inner, offset+i, len(content), viewport, horizontalOffset, vertical, active, noColor))
	}
	for len(lines) < max(1, height-1) {
		lines = append(lines, detailPanelLine("", inner, offset+len(lines)-1, len(content), viewport, horizontalOffset, vertical, active, noColor))
	}
	bottom := bottomLeft + strings.Repeat(horizontal, inner) + bottomRight
	if active {
		bottom = frame.Render(bottom)
	}
	lines = append(lines, bottom)
	return lines
}

func detailPanelLine(line string, inner, row, total, viewport, horizontalOffset int, vertical string, active, noColor bool) string {
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
	text := terminalansi.Cut(line, horizontalOffset, horizontalOffset+textWidth)
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
	minValue, maxValue := slices.Min(values), slices.Max(values)
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
	minValue, maxValue := slices.Min(values), slices.Max(values)
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
