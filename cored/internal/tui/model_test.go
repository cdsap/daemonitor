package tui

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/clipperhouse/displaywidth"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
)

func TestSortByRSSDescending(t *testing.T) {
	m := NewModel(Config{Now: fixedNow})
	m.applySnapshot(model.Snapshot{
		SampledAtMs: 1_000_000,
		Processes: []model.Process{
			{PID: 1, RSSMemoryMB: 100, Type: "A"},
			{PID: 2, RSSMemoryMB: 300, Type: "B"},
			{PID: 3, RSSMemoryMB: 200, Type: "C"},
		},
	})
	if m.processes[0].PID != 2 || m.processes[1].PID != 3 || m.processes[2].PID != 1 {
		t.Fatalf("unexpected order: %+v", pids(m.processes))
	}
}

func TestSortByHeapMetrics(t *testing.T) {
	usedLow, usedHigh := int64(512), int64(2048)
	committedLow, committedHigh := int64(1024), int64(4096)
	xmxLow, xmxHigh := int64(2048), int64(8192)
	processes := []model.Process{
		{PID: 1, HeapUsedMB: &usedLow, HeapCommittedMB: &committedLow, MaxHeapMB: &xmxLow},
		{PID: 2, HeapUsedMB: &usedHigh, HeapCommittedMB: &committedHigh, MaxHeapMB: &xmxHigh},
		{PID: 3},
	}

	for _, field := range []SortField{SortHeapUsed, SortHeapCommitted, SortHeapMax} {
		m := NewModel(Config{Now: fixedNow})
		m.sortField = field
		m.applySnapshot(model.Snapshot{SampledAtMs: 1_000_000, Processes: processes})
		if got := pids(m.processes); !equalInt32s(got, []int32{2, 1, 3}) {
			t.Fatalf("field %s descending order=%v", field, got)
		}

		m.sortOrder = SortAsc
		m.sortProcesses()
		if got := pids(m.processes); !equalInt32s(got, []int32{3, 1, 2}) {
			t.Fatalf("field %s ascending order=%v", field, got)
		}
	}
}

func TestSortCyclesThroughHeapMetrics(t *testing.T) {
	m := NewModel(Config{Now: fixedNow})
	for _, want := range []SortField{SortCPU, SortPID, SortType, SortUptime, SortProject, SortHeapUsed, SortHeapCommitted, SortHeapMax, SortRSS} {
		m.cycleSortField()
		if m.sortField != want {
			t.Fatalf("sort field=%s want %s", m.sortField, want)
		}
	}
}

func equalInt32s(a, b []int32) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestSelectionPreservedByPIDAcrossReorder(t *testing.T) {
	m := NewModel(Config{Now: fixedNow})
	m.applySnapshot(model.Snapshot{
		SampledAtMs: 1,
		Processes: []model.Process{
			{PID: 10, RSSMemoryMB: 50},
			{PID: 20, RSSMemoryMB: 40},
		},
	})
	m.selectedPID = 20
	m.applySnapshot(model.Snapshot{
		SampledAtMs: 2,
		Processes: []model.Process{
			{PID: 20, RSSMemoryMB: 90},
			{PID: 10, RSSMemoryMB: 10},
			{PID: 30, RSSMemoryMB: 5},
		},
	})
	if m.selectedPID != 20 {
		t.Fatalf("selected PID=%d want 20", m.selectedPID)
	}
}

func TestSelectionMovesWhenPIDExits(t *testing.T) {
	m := NewModel(Config{Now: fixedNow})
	m.applySnapshot(model.Snapshot{
		SampledAtMs: 1,
		Processes: []model.Process{
			{PID: 10, RSSMemoryMB: 50},
			{PID: 20, RSSMemoryMB: 40},
		},
	})
	m.selectedPID = 20
	m.applySnapshot(model.Snapshot{
		SampledAtMs: 2,
		Processes: []model.Process{
			{PID: 10, RSSMemoryMB: 50},
		},
	})
	if m.selectedPID != 10 {
		t.Fatalf("selected PID=%d want 10", m.selectedPID)
	}
}

func TestPauseAndRefreshKeys(t *testing.T) {
	m := NewModel(Config{Now: fixedNow})
	m.connected = true
	next, _ := m.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeySpace}))
	m = next.(Model)
	if !m.paused {
		t.Fatal("expected paused after space")
	}
	next, cmd := m.Update(tea.KeyPressMsg(tea.Key{Code: 'r'}))
	m = next.(Model)
	if cmd == nil {
		t.Fatal("expected refresh command while paused")
	}
}

func TestNavigationKeys(t *testing.T) {
	m := NewModel(Config{Now: fixedNow})
	m.applySnapshot(model.Snapshot{
		SampledAtMs: 1,
		Processes: []model.Process{
			{PID: 1, RSSMemoryMB: 30},
			{PID: 2, RSSMemoryMB: 20},
			{PID: 3, RSSMemoryMB: 10},
		},
	})
	m.selectedPID = 1
	next, _ := m.Update(tea.KeyPressMsg(tea.Key{Code: tea.KeyDown}))
	m = next.(Model)
	if m.selectedPID != 2 {
		t.Fatalf("down: got %d", m.selectedPID)
	}
	next, _ = m.Update(tea.KeyPressMsg(tea.Key{Code: 'G'}))
	m = next.(Model)
	if m.selectedPID != 3 {
		t.Fatalf("end: got %d", m.selectedPID)
	}
	next, _ = m.Update(tea.KeyPressMsg(tea.Key{Code: 'g'}))
	m = next.(Model)
	if m.selectedPID != 1 {
		t.Fatalf("home: got %d", m.selectedPID)
	}
}

func TestErrorKeepsLastSnapshot(t *testing.T) {
	m := NewModel(Config{Now: fixedNow})
	m.applySnapshot(model.Snapshot{
		SampledAtMs: 1,
		Processes:   []model.Process{{PID: 7, RSSMemoryMB: 11}},
	})
	next, _ := m.Update(snapshotFailedMsg{err: errString("boom")})
	m = next.(Model)
	if len(m.processes) != 1 || m.processes[0].PID != 7 {
		t.Fatalf("lost snapshot: %+v", m.processes)
	}
	if m.lastError == "" {
		t.Fatal("expected lastError")
	}
}

func TestAdaptiveColumns(t *testing.T) {
	if n := len(columnsForWidth(120).ids); n < 11 {
		t.Fatalf("wide columns=%d", n)
	}
	if n := len(columnsForWidth(90).ids); n != 8 {
		t.Fatalf("medium columns=%d", n)
	}
	if n := len(columnsForWidth(70).ids); n != 6 {
		t.Fatalf("narrow columns=%d", n)
	}
}

func TestTruncateUnicode(t *testing.T) {
	got := truncateWidth("こんにちは世界", 5)
	if displayLen(got) > 5 {
		t.Fatalf("width=%d text=%q", displayLen(got), got)
	}
	if !strings.HasSuffix(got, "…") {
		t.Fatalf("expected ellipsis: %q", got)
	}
}

func TestGoldenRenderSizes(t *testing.T) {
	fixed := time.Date(2026, 9, 24, 10, 42, 18, 0, time.UTC)
	m := NewModel(Config{Now: func() time.Time { return fixed }, NoColor: true})
	cpu := 84.2
	xmx := int64(4096)
	m.applySnapshot(model.Snapshot{
		SampledAtMs: fixed.UnixMilli(),
		Processes: []model.Process{
			{PID: 43122, Type: "GRADLE_DAEMON", Name: "GradleDaemon", RSSMemoryMB: 2100, CPUPercent: &cpu, MaxHeapMB: &xmx, StartTimeMs: fixed.Add(-12 * time.Minute).UnixMilli(), ProjectPath: strPtr("/tmp/daemonitor")},
			{PID: 43091, Type: "KOTLIN_DAEMON", Name: "Kotlin", RSSMemoryMB: 1300, StartTimeMs: fixed.Add(-14 * time.Minute).UnixMilli(), ProjectPath: strPtr("/tmp/daemonitor")},
		},
	})
	m.connected = true
	m.lastUpdated = fixed
	m.selectedPID = 43122

	cases := []struct {
		w, h int
	}{
		{60, 15},
		{80, 24},
		{120, 30},
	}
	for _, tc := range cases {
		m.width, m.height = tc.w, tc.h
		view := m.View()
		body := view.Content
		for _, line := range strings.Split(body, "\n") {
			if displayLen(line) > tc.w {
				t.Fatalf("%dx%d line exceeds width (%d): %q", tc.w, tc.h, displayLen(line), line)
			}
		}
		if !strings.Contains(body, "DAEMONITOR") {
			t.Fatalf("%dx%d missing header", tc.w, tc.h)
		}
	}
}

func TestGoldenEmptyAndDetails(t *testing.T) {
	fixed := time.Date(2026, 9, 24, 10, 42, 18, 0, time.UTC)
	m := NewModel(Config{Now: func() time.Time { return fixed }, NoColor: true})
	m.width, m.height = 80, 50
	m.connected = true
	m.lastUpdated = fixed
	m.applySnapshot(model.Snapshot{SampledAtMs: fixed.UnixMilli(), Processes: nil})
	body := m.View().Content
	if !strings.Contains(body, "No Gradle-related processes") {
		t.Fatalf("empty state missing: %s", body)
	}

	used := int64(1536)
	committed := int64(2048)
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", Name: "x", RSSMemoryMB: 1, HeapUsedMB: &used, HeapCommittedMB: &committed}
	m.detailsOpen = true
	m.detailProcess = &p
	m.detailLoading = false
	body = m.View().Content
	if !strings.Contains(body, "PROCESS DETAILS") || !strings.Contains(body, "Heap used") || !strings.Contains(body, "1.5 GB") || !strings.Contains(body, "2 GB") {
		t.Fatalf("details view unexpected: %s", body)
	}
}

func TestDetailsBuildHistoryLoadingEmptyAndFailureStates(t *testing.T) {
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", StartTimeMs: 200}
	m := NewModel(Config{Now: fixedNow, NoColor: true, Client: client.New("/tmp/missing-daemonitor.sock")})
	m.width, m.height = 80, 24
	m.detailsOpen = true
	m.selectedPID = int64(p.PID)
	m.detailProcess = &p
	m.detailLoading = true
	m.detailScroll = 1000
	if body := m.View().Content; !strings.Contains(body, "Recent builds:") || !strings.Contains(body, "Loading…") {
		t.Fatalf("loading state missing: %s", body)
	}

	m.detailLoading = false
	if body := m.View().Content; !strings.Contains(body, "No build history for this daemon.") {
		t.Fatalf("empty state missing: %s", body)
	}

	next, _ := m.Update(detailsLoadedMsg{pid: 9, startMs: 200, buildsErr: errString("HTTP 500")})
	m = next.(Model)
	if body := m.View().Content; !strings.Contains(body, "Unavailable: build history unavailable: HTTP 500") {
		t.Fatalf("failure state missing: %s", body)
	}
}

func TestDetailsShowScopedBuildFieldsAndFitNarrowWidth(t *testing.T) {
	duration := 1.25
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", StartTimeMs: 200}
	m := NewModel(Config{Now: fixedNow, NoColor: true})
	m.width, m.height = 40, 20
	m.detailsOpen = true
	m.selectedPID = int64(p.PID)
	m.detailProcess = &p
	m.detailBuilds = client.BuildsPayload{Builds: []client.BuildRecord{{
		FinalStatus: "SUCCESS", ProjectPath: "/very/long/project/path", DurationSeconds: &duration,
		StartTimeMs: 300, InferredSource: "TERMINAL", Agent: "Claude Code",
	}}}
	m.width = 120
	m.detailScroll = 1000
	body := m.View().Content
	start := "start=" + formatStart(300)
	for _, want := range []string{"SUCCESS", "project=/very/long/project/path", "duration=1.3s", start, "source=TERMINAL", "agent=Claude Code"} {
		if !strings.Contains(body, want) {
			t.Fatalf("build summary missing %q: %s", want, body)
		}
	}
	m.width = 40
	m.detailScroll = 1000
	body = m.View().Content
	if !strings.Contains(body, "SUCCESS") || !strings.Contains(body, "Recent builds:") {
		t.Fatalf("build summary missing: %s", body)
	}
	for _, line := range strings.Split(body, "\n") {
		if displayLen(line) > m.width {
			t.Fatalf("line exceeds width (%d): %q", m.width, line)
		}
	}
}

func TestDetailsRendersCautiousMemoryGrowthAnalysis(t *testing.T) {
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", StartTimeMs: 100}
	m := NewModel(Config{Now: fixedNow, NoColor: true})
	m.width, m.height, m.detailsOpen, m.detailProcess = 80, 30, true, &p
	for i, rss := range []int64{100, 180, 260, 340, 420, 500} {
		m.detailHistory = append(m.detailHistory, model.Process{PID: 9, StartTimeMs: 100, SampledAtMs: int64(i+1) * 30_000, RSSMemoryMB: rss})
	}
	m.detailScroll = 1000
	body := m.View().Content
	for _, want := range []string{"MEMORY TREND", "RSS", "180 MB → 500 MB", "+320 MB", "⚠ Sustained memory growth detected", "Heap  n/a"} {
		if !strings.Contains(body, want) {
			t.Fatalf("memory analysis missing %q: %s", want, body)
		}
	}
	for _, line := range strings.Split(body, "\n") {
		if displayLen(line) > m.width {
			t.Fatalf("line exceeds width (%d): %q", m.width, line)
		}
	}
}

func TestDetailsMemoryAnalysisDoesNotMixProcessIncarnations(t *testing.T) {
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", StartTimeMs: 200}
	m := NewModel(Config{Now: fixedNow, NoColor: true})
	m.width, m.height, m.detailsOpen, m.detailProcess = 80, 30, true, &p
	for i, rss := range []int64{100, 200, 300, 400, 500, 600} {
		m.detailHistory = append(m.detailHistory, model.Process{PID: 9, StartTimeMs: 100, SampledAtMs: int64(i+1) * 30_000, RSSMemoryMB: rss})
	}
	m.detailScroll = 1000
	body := m.View().Content
	if !strings.Contains(body, "Assessment: INSUFFICIENT_DATA") {
		t.Fatalf("mixed incarnation was analyzed: %s", body)
	}
}

func TestDetailsIgnoreBuildsForReplacedPID(t *testing.T) {
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", StartTimeMs: 200}
	m := NewModel(Config{Now: fixedNow, NoColor: true})
	m.detailsOpen = true
	m.selectedPID = int64(p.PID)
	m.detailProcess = &p
	m.detailLoading = true
	next, _ := m.Update(detailsLoadedMsg{pid: 9, startMs: 100, builds: client.BuildsPayload{Builds: []client.BuildRecord{{FinalStatus: "SUCCESS"}}}})
	m = next.(Model)
	if !m.detailLoading || len(m.detailBuilds.Builds) != 0 {
		t.Fatalf("stale details result applied: loading=%v builds=%v", m.detailLoading, m.detailBuilds.Builds)
	}
}

func TestDetailsWideCommanderPanesAndLogTail(t *testing.T) {
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", Name: "gradle", StartTimeMs: 100}
	m := NewModel(Config{Now: fixedNow, NoColor: true})
	m.width, m.height, m.detailsOpen, m.detailProcess = 160, 30, true, &p
	m.detailLog = &logs.DaemonLog{PID: 9, GradleVersion: "8.0", Path: "/tmp/daemon.log"}
	m.detailTail = &logs.Tail{PID: 9, Lines: []string{"old line", "newest Gradle line"}}
	body := m.View().Content
	for _, want := range []string{"ATTRIBUTES", "RESOURCE / BUILDS", "GRADLE LOG", "newest Gradle line"} {
		if !strings.Contains(body, want) {
			t.Fatalf("wide commander view missing %q: %s", want, body)
		}
	}
	for _, line := range strings.Split(body, "\n") {
		if displayLen(line) > m.width {
			t.Fatalf("wide line exceeds width (%d): %q", m.width, line)
		}
	}
}

func TestDetailsScrollsWithVisibleScrollbar(t *testing.T) {
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", Name: "gradle", StartTimeMs: 100}
	m := NewModel(Config{Now: fixedNow, NoColor: true})
	m.width, m.height, m.detailsOpen, m.detailProcess = 160, 20, true, &p
	lines := make([]string, 0, 40)
	for i := 0; i < 40; i++ {
		lines = append(lines, fmt.Sprintf("log line %02d", i))
	}
	m.detailTail = &logs.Tail{PID: 9, Lines: lines}

	before := m.View().Content
	if !strings.Contains(before, "░") && !strings.Contains(before, "█") {
		t.Fatalf("scrollbar missing from overflowing detail pane: %s", before)
	}
	next, _ := m.handleKey(tea.KeyPressMsg{Text: "down"})
	after := next.(Model).View().Content
	if before == after {
		t.Fatalf("down did not move the detail viewport")
	}
}

func TestDetailsPanesKeepStableColumnAlignment(t *testing.T) {
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", Name: "gradle", StartTimeMs: 100}
	for _, width := range []int{120, 160} {
		m := NewModel(Config{Now: fixedNow, NoColor: true})
		m.width, m.height, m.detailsOpen, m.detailProcess = width, 24, true, &p
		m.detailTail = &logs.Tail{PID: 9, Lines: []string{"first log line", "second log line"}}
		for _, line := range strings.Split(m.View().Content, "\n") {
			if strings.Contains(line, "│") && displayLen(line) != width {
				t.Fatalf("width=%d detail row has unstable width %d: %q", width, displayLen(line), line)
			}
		}
	}
}

func TestDetailsRefreshKeyReloadsOpenProcess(t *testing.T) {
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", StartTimeMs: 100}
	m := NewModel(Config{Now: fixedNow, NoColor: true, Client: client.New("/tmp/missing-daemonitor.sock")})
	m.width, m.height, m.detailsOpen, m.detailProcess = 80, 20, true, &p
	m.detailLoading = false
	next, cmd := m.handleKey(tea.KeyPressMsg{Text: "r"})
	refreshed := next.(Model)
	if cmd == nil || !refreshed.detailLoading {
		t.Fatalf("r should refresh the open detail view: loading=%v cmd=%v", refreshed.detailLoading, cmd != nil)
	}
}

func TestDetailsHideGradleOnlyPanelsForNonDaemons(t *testing.T) {
	for _, processType := range []string{"KOTLIN_DAEMON", "TEST_WORKER"} {
		p := model.Process{PID: 9, Type: processType, StartTimeMs: 100}
		m := NewModel(Config{Now: fixedNow, NoColor: true})
		m.width, m.height, m.detailsOpen, m.detailProcess = 100, 30, true, &p
		m.detailBuilds = client.BuildsPayload{Builds: []client.BuildRecord{{FinalStatus: "SUCCESS"}}}
		m.detailTail = &logs.Tail{PID: 9, Lines: []string{"should be hidden"}}
		body := m.View().Content
		if strings.Contains(body, "RECENT BUILDS") || strings.Contains(body, "GRADLE LOG") || strings.Contains(body, "should be hidden") {
			t.Fatalf("%s rendered Gradle-only panels: %s", processType, body)
		}
		m.detailScroll = 1000
		body = m.View().Content
		if !strings.Contains(body, "RESOURCE TRENDS") {
			t.Fatalf("%s omitted resource trends: %s", processType, body)
		}
	}
}

func TestFormatRowUsesLiveHeapValues(t *testing.T) {
	used := int64(512)
	committed := int64(1024)
	p := model.Process{PID: 9, HeapUsedMB: &used, HeapCommittedMB: &committed}
	row := formatRow(p, columnSet{ids: []columnID{colHeapUsed, colHeapCmt}}, fixedNow().UnixMilli())
	if !strings.Contains(row, "512 MB") || !strings.Contains(row, "1 GB") {
		t.Fatalf("row=%q missing live heap values", row)
	}
}

func TestFormatRowIncludesExtendedColumns(t *testing.T) {
	threads, used, max := int64(12), int64(512), int64(1024)
	row := formatRow(model.Process{ThreadCount: &threads, HeapUsedMB: &used, HeapMaxMB: &max, MetaspaceUsedMB: &used}, columnSet{ids: []columnID{colThreads, colHeapPercent, colMetaspace}}, fixedNow().UnixMilli())
	for _, want := range []string{"12", "50.0%", "512 MB"} {
		if !strings.Contains(row, want) {
			t.Fatalf("row=%q missing %q", row, want)
		}
	}
}

func TestDetailsShowExtendedMetricsAndUnavailableValues(t *testing.T) {
	threads, used, max := int64(12), int64(512), int64(1024)
	p := model.Process{PID: 9, ThreadCount: &threads, HeapUsedMB: &used, HeapMaxMB: &max, MetaspaceUsedMB: &used}
	m := NewModel(Config{Now: fixedNow, NoColor: true})
	m.width, m.height, m.detailsOpen, m.detailProcess = 120, 30, true, &p
	m.detailScroll = 1000
	body := m.View().Content
	for _, want := range []string{"Threads", "Heap %", "Metaspace used", "Virtual memory", "Disk read", "Young GC time", "Java version", "n/a"} {
		if !strings.Contains(body, want) {
			t.Fatalf("details missing %q: %s", want, body)
		}
	}
}

func TestFormatRowIncludesGarbageCollector(t *testing.T) {
	gc := "G1"
	row := formatRow(model.Process{GC: &gc}, columnSet{ids: []columnID{colGC}}, fixedNow().UnixMilli())
	if !strings.Contains(row, "G1") {
		t.Fatalf("row=%q missing GC type", row)
	}
}

func TestFormatRowIncludesProcessSignals(t *testing.T) {
	p := model.Process{PID: 9, RSSMemoryMB: render.MemoryCritMB, Automated: true}
	row := formatRow(p, columnSet{ids: []columnID{colType, colPID, colRSS, colProject}}, fixedNow().UnixMilli())
	if !strings.Contains(row, "CRIT MEM") || !strings.Contains(row, "AUTOMATED") {
		t.Fatalf("row=%q missing process signals", row)
	}
}

func TestFormatRowKeepsTableColumnsAlignedWithMemorySignal(t *testing.T) {
	cols := columnSet{ids: []columnID{colType, colPID, colRSS, colProject}}
	row := formatRow(model.Process{PID: 9, Type: "GRADLE_DAEMON", RSSMemoryMB: render.MemoryWarnMB}, cols, fixedNow().UnixMilli())

	if !strings.HasPrefix(row, "Gradle daemon") {
		t.Fatalf("memory signal shifted table columns: row=%q", row)
	}
	if strings.Index(row, "HIGH MEM") < strings.Index(row, "Gradle daemon") {
		t.Fatalf("memory signal should follow table cells: row=%q", row)
	}
}

func TestSmallTerminalMessage(t *testing.T) {
	m := NewModel(Config{Now: fixedNow})
	m.width, m.height = 20, 5
	if !strings.Contains(m.View().Content, "enlarge") {
		t.Fatal("expected enlarge message")
	}
}

func TestNoColorRemovesAllANSIFromTable(t *testing.T) {
	m := NewModel(Config{Now: fixedNow, NoColor: true})
	m.width, m.height = 100, 20
	m.connected = true
	cpu := 92.0
	m.applySnapshot(model.Snapshot{SampledAtMs: fixedNow().UnixMilli(), Processes: []model.Process{{PID: 9, Type: "GRADLE_DAEMON", RSSMemoryMB: 5_000, CPUPercent: &cpu, Automated: true}}})
	if strings.Contains(m.View().Content, "\x1b[") {
		t.Fatalf("--no-color emitted ANSI: %q", m.View().Content)
	}
}

func TestColoredRowsUseSemanticANSI(t *testing.T) {
	row := formatRowStyled(model.Process{PID: 9, Type: "GRADLE_DAEMON", RSSMemoryMB: 5_000, Automated: true}, columnSet{ids: []columnID{colType, colPID, colRSS}}, fixedNow().UnixMilli(), false, false)
	if !strings.Contains(row, "\x1b[") {
		t.Fatalf("expected semantic row colors: %q", row)
	}
}

func TestRSSTrendIsCompactAndTracksRange(t *testing.T) {
	trend := rssTrend([]model.Process{
		{PID: 9, RSSMemoryMB: 100},
		{PID: 9, RSSMemoryMB: 200},
		{PID: 9, RSSMemoryMB: 150},
		{PID: 10, RSSMemoryMB: 999},
	}, 9)
	if !strings.Contains(trend, "100 MB–200 MB") {
		t.Fatalf("trend=%q", trend)
	}
	if strings.Contains(trend, "999 MB") {
		t.Fatalf("trend included another PID: %q", trend)
	}
}

type terminateCall struct {
	pid         int32
	startTimeMs int64
}

func recordingTerminate(calls *[]terminateCall, failPIDs ...int32) TerminateFunc {
	return func(_ context.Context, pid int32, startTimeMs int64) error {
		*calls = append(*calls, terminateCall{pid: pid, startTimeMs: startTimeMs})
		for _, fail := range failPIDs {
			if fail == pid {
				return errString("operation not permitted")
			}
		}
		return nil
	}
}

func killTestModel(calls *[]terminateCall, failPIDs ...int32) Model {
	m := NewModel(Config{Now: fixedNow, NoColor: true, Terminate: recordingTerminate(calls, failPIDs...)})
	m.width, m.height = 100, 24
	m.connected = true
	m.applySnapshot(model.Snapshot{
		SampledAtMs: 1,
		Processes: []model.Process{
			{PID: 1, Type: "GRADLE_DAEMON", RSSMemoryMB: 30, StartTimeMs: 1001},
			{PID: 2, Type: "KOTLIN_DAEMON", RSSMemoryMB: 20, StartTimeMs: 1002},
			{PID: 3, Type: "GRADLE_WORKER", RSSMemoryMB: 10, StartTimeMs: 1003},
		},
	})
	return m
}

func press(t *testing.T, m Model, code rune) (Model, tea.Cmd) {
	t.Helper()
	next, cmd := m.Update(tea.KeyPressMsg(tea.Key{Code: code}))
	return next.(Model), cmd
}

func TestKillSelectedRequiresConfirmationAndTargetsSelectedProcess(t *testing.T) {
	var calls []terminateCall
	m := killTestModel(&calls)
	m.selectedPID = 2

	m, cmd := press(t, m, 'x')
	if cmd != nil || len(calls) != 0 {
		t.Fatalf("x must only prompt; calls=%v", calls)
	}
	if m.pendingKill == nil || len(m.pendingKill.targets) != 1 || m.pendingKill.targets[0].PID != 2 {
		t.Fatalf("pending kill=%+v want pid 2", m.pendingKill)
	}
	if body := m.View().Content; !strings.Contains(body, "Kill Kotlin daemon pid 2") || !strings.Contains(body, "y confirm") {
		t.Fatalf("missing confirmation prompt: %s", body)
	}

	m, cmd = press(t, m, 'y')
	if cmd == nil || !m.killing || m.pendingKill != nil {
		t.Fatalf("y should start kill: killing=%v pending=%v", m.killing, m.pendingKill)
	}
	next, refresh := m.Update(cmd())
	m = next.(Model)
	if len(calls) != 1 || calls[0] != (terminateCall{pid: 2, startTimeMs: 1002}) {
		t.Fatalf("terminate calls=%v want pid 2 with start time", calls)
	}
	if m.killing || m.noticeError || !strings.Contains(m.notice, "pid 2") {
		t.Fatalf("unexpected notice=%q error=%v killing=%v", m.notice, m.noticeError, m.killing)
	}
	if refresh == nil {
		t.Fatal("expected snapshot refresh after kill")
	}
}

func TestKillAllFreezesTargetsAndReportsFailures(t *testing.T) {
	var calls []terminateCall
	m := killTestModel(&calls, 3)

	m, _ = press(t, m, 'X')
	if m.pendingKill == nil || !m.pendingKill.all {
		t.Fatalf("X should prompt for kill all: %+v", m.pendingKill)
	}
	if body := m.View().Content; !strings.Contains(body, "Kill all 3 recorded processes") {
		t.Fatalf("missing kill-all prompt: %s", body)
	}
	// A refresh while the prompt is open must not change what was confirmed.
	m.applySnapshot(model.Snapshot{SampledAtMs: 2, Processes: []model.Process{{PID: 99, RSSMemoryMB: 1}}})

	m, cmd := press(t, m, 'y')
	next, _ := m.Update(cmd())
	m = next.(Model)
	if len(calls) != 3 || calls[0].pid != 1 || calls[1].pid != 2 || calls[2].pid != 3 {
		t.Fatalf("terminate calls=%v want pids 1,2,3", calls)
	}
	if !m.noticeError || !strings.Contains(m.notice, "2 of 3") || !strings.Contains(m.notice, "pid 3: operation not permitted") {
		t.Fatalf("unexpected failure notice=%q", m.notice)
	}
	if body := m.View().Content; !strings.Contains(body, "pid 3: operation not permitted") {
		t.Fatalf("failure notice not rendered: %s", body)
	}
}

func TestKillPromptCancelsOnOtherKey(t *testing.T) {
	var calls []terminateCall
	m := killTestModel(&calls)

	m, _ = press(t, m, 'X')
	m, cmd := press(t, m, 'n')
	if cmd != nil || len(calls) != 0 || m.pendingKill != nil {
		t.Fatalf("cancel should not kill: calls=%v pending=%v", calls, m.pendingKill)
	}
	if !strings.Contains(m.View().Content, "Kill cancelled") {
		t.Fatalf("missing cancel notice: %s", m.View().Content)
	}

	m, _ = press(t, m, 'x')
	m, cmd = press(t, m, 'q')
	if cmd != nil || len(calls) != 0 {
		t.Fatal("q while confirming should cancel, not quit or kill")
	}
}

func TestKillKeysIgnoredWithoutProcesses(t *testing.T) {
	var calls []terminateCall
	m := NewModel(Config{Now: fixedNow, Terminate: recordingTerminate(&calls)})
	m.connected = true
	m.applySnapshot(model.Snapshot{SampledAtMs: 1})
	for _, key := range []rune{'x', 'X'} {
		m, _ = press(t, m, key)
		if m.pendingKill != nil {
			t.Fatalf("%c prompted with no processes", key)
		}
	}
}

func TestKillPromptFitsNarrowTerminal(t *testing.T) {
	var calls []terminateCall
	m := killTestModel(&calls)
	m.width, m.height = 40, 10
	m, _ = press(t, m, 'x')
	for _, line := range strings.Split(m.View().Content, "\n") {
		if displayLen(line) > m.width {
			t.Fatalf("line exceeds width (%d): %q", displayLen(line), line)
		}
	}
}

func fixedNow() time.Time {
	return time.Unix(1_700_000_000, 0).UTC()
}

func pids(ps []model.Process) []int32 {
	out := make([]int32, len(ps))
	for i, p := range ps {
		out[i] = p.PID
	}
	return out
}

func strPtr(s string) *string { return &s }

type errString string

func (e errString) Error() string { return string(e) }

func displayLen(s string) int {
	return displaywidth.String(s)
}
