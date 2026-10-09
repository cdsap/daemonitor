package tui

import (
	"strings"
	"testing"
	"time"

	terminalansi "github.com/charmbracelet/x/ansi"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

var layoutNow = time.Date(2026, 9, 24, 10, 42, 18, 0, time.UTC)

func layoutProcesses() []model.Process {
	cpu := 84.2
	xmx := int64(4096)
	gc := "G1"
	return []model.Process{
		{PID: 43122, Type: "GRADLE_DAEMON", GC: &gc, RSSMemoryMB: 2100, CPUPercent: &cpu, MaxHeapMB: &xmx, StartTimeMs: layoutNow.Add(-12 * time.Minute).UnixMilli(), ProjectPath: strPtr("/tmp/daemonitor")},
		{PID: 43150, ParentPID: 43122, Type: "TEST_WORKER", RSSMemoryMB: 300, StartTimeMs: layoutNow.Add(-1 * time.Minute).UnixMilli(), ProjectPath: strPtr("/tmp/core")},
		{PID: 43151, ParentPID: 43122, Type: "GRADLE_WORKER", RSSMemoryMB: 200, StartTimeMs: layoutNow.Add(-1 * time.Minute).UnixMilli(), ProjectPath: strPtr("/tmp/workers")},
		{PID: 43091, Type: "KOTLIN_DAEMON", RSSMemoryMB: 1300, StartTimeMs: layoutNow.Add(-14 * time.Minute).UnixMilli(), ProjectPath: strPtr("/tmp/daemonitor")},
	}
}

func layoutModel(noColor bool, w, h int) Model {
	m := NewModel(Config{Now: func() time.Time { return layoutNow }, NoColor: noColor})
	m.applySnapshot(model.Snapshot{SampledAtMs: layoutNow.UnixMilli(), Processes: layoutProcesses()})
	m.connected = true
	m.lastUpdated = layoutNow
	m.width, m.height = w, h
	return m
}

func plainLines(m Model) []string {
	return strings.Split(terminalansi.Strip(m.View().Content), "\n")
}

func lineWith(t *testing.T, lines []string, needle string) string {
	t.Helper()
	for _, line := range lines {
		if strings.Contains(line, needle) {
			return line
		}
	}
	t.Fatalf("no line contains %q:\n%s", needle, strings.Join(lines, "\n"))
	return ""
}

// column returns the terminal cell where needle starts in line.
func column(line, needle string) int {
	idx := strings.Index(line, needle)
	if idx < 0 {
		return -1
	}
	return terminalansi.StringWidth(line[:idx])
}

func assertAligned(t *testing.T, label, header, row, headerText, cellText string, rightAligned bool) {
	t.Helper()
	got, want := column(header, headerText), column(row, cellText)
	if rightAligned {
		got += len(headerText)
		want += len(cellText)
	}
	if got != want || want < 0 {
		t.Fatalf("%s: header %q at %d, cell %q at %d\n%s\n%s", label, headerText, got, cellText, want, header, row)
	}
}

func TestHeaderAlignsWithRowCells(t *testing.T) {
	for _, w := range []int{70, 90, 120, 170} {
		lines := plainLines(layoutModel(true, w, 20))
		header := lineWith(t, lines, "PID")
		for _, pid := range []string{"43122", "43150"} {
			row := lineWith(t, lines, pid)
			assertAligned(t, "TYPE", header, row, "TYPE", strings.TrimSpace(row[2:18]), false)
			assertAligned(t, "PID", header, row, "PID", pid, true)
			assertAligned(t, "PROJECT", header, row, "PROJECT", strings.Fields(row)[len(strings.Fields(row))-1], false)
		}
	}
}

func TestGroupedRowsAlignAndKeepSelectionMarker(t *testing.T) {
	m := layoutModel(true, 120, 20)
	next, _ := m.Update(key('v'))
	m = next.(Model)
	m.selectedPID = 43150
	lines := plainLines(m)
	header := lineWith(t, lines, "PID")
	root := lineWith(t, lines, "43122")
	child := lineWith(t, lines, "43150")
	last := lineWith(t, lines, "43151")
	standalone := lineWith(t, lines, "43091")

	if !strings.HasPrefix(root, "  ▼ ") || !strings.HasPrefix(child, "> ├ ") || !strings.HasPrefix(last, "  └ ") || !strings.HasPrefix(standalone, "    ") {
		t.Fatalf("unexpected gutters:\n%s\n%s\n%s\n%s", root, child, last, standalone)
	}
	for _, tc := range []struct{ row, typeLabel, rss string }{
		{root, "Gradle daemon", "2.5 GB"},
		{child, "Test worker", "300 MB"},
		{last, "Gradle worker", "200 MB"},
		{standalone, "Kotlin daemon", "1.3 GB"},
	} {
		assertAligned(t, "TYPE", header, tc.row, "TYPE", tc.typeLabel, false)
		assertAligned(t, "RSS", header, tc.row, "RSS", tc.rss, true)
	}
	if strings.Contains(root, "…") {
		t.Fatalf("group root row truncated at 120 columns: %q", root)
	}

	for w, want := range map[int]string{150: "+2 children", 200: "(2 children; includes daemon)"} {
		m.width = w
		if root := lineWith(t, plainLines(m), "43122"); !strings.Contains(root, want) || strings.Contains(root, "…") {
			t.Fatalf("width %d: group summary=%q want %q", w, root, want)
		}
	}
}

func TestSortMarkerShowsDirection(t *testing.T) {
	m := layoutModel(true, 100, 20)
	if header := lineWith(t, plainLines(m), "PID"); !strings.Contains(header, "▼RSS") {
		t.Fatalf("descending RSS marker missing: %q", header)
	}
	m.sortOrder = SortAsc
	if header := lineWith(t, plainLines(m), "PID"); !strings.Contains(header, "▲RSS") {
		t.Fatalf("ascending RSS marker missing: %q", header)
	}
}

func TestColoredLayoutFitsWidthWithoutSpuriousEllipsis(t *testing.T) {
	for _, w := range []int{80, 120, 170} {
		m := layoutModel(false, w, 20)
		for _, line := range strings.Split(m.View().Content, "\n") {
			if terminalansi.StringWidth(line) > w {
				t.Fatalf("width %d: colored line exceeds width (%d): %q", w, terminalansi.StringWidth(line), line)
			}
		}
		if w < 120 {
			// PROJECT is only minProjectCols wide here, so long names are shortened.
			continue
		}
		for _, line := range plainLines(m) {
			if strings.Contains(line, "…") {
				t.Fatalf("width %d: content was truncated although it fits: %q", w, line)
			}
		}
	}
}

func TestColumnLayoutsFitTheirThresholds(t *testing.T) {
	for _, w := range []int{60, 80, 100, 120, 140, 170, 200} {
		for _, grouped := range []bool{false, true} {
			gutter := len(rowPrefix)
			if grouped {
				gutter += treeMarkerWidth
			}
			cols := columnsFor(w, gutter)
			if len(cols.ids) > 6 && gutter+cols.fixedWidth()+cols.projectWidth > w {
				t.Fatalf("width %d grouped=%v: layout of %d columns overflows", w, grouped, len(cols.ids))
			}
		}
	}
	if n := len(columnsForWidth(170).ids); n != len(columnLayouts[0]) {
		t.Fatalf("170 columns should show the full layout, got %d columns", n)
	}
}

func TestSignalsStayInsideTerminalWidth(t *testing.T) {
	m := layoutModel(true, 100, 20)
	m.processes[0].RSSMemoryMB = 9000
	m.refreshDisplayRows()
	row := lineWith(t, plainLines(m), "43122")
	if !strings.Contains(row, "CRIT MEM") || terminalansi.StringWidth(row) > 100 {
		t.Fatalf("badge row=%q (width %d)", row, terminalansi.StringWidth(row))
	}
}

func TestSummaryAndFooterCollapseOnNarrowTerminals(t *testing.T) {
	for _, w := range []int{60, 80, 100} {
		lines := plainLines(layoutModel(true, w, 20))
		summary := lineWith(t, lines, "processes")
		footer := lines[len(lines)-1]
		if strings.Contains(summary, "…") || strings.Contains(footer, "…") || !strings.Contains(footer, "q quit") {
			t.Fatalf("width %d: summary=%q footer=%q", w, summary, footer)
		}
	}
}

func TestHelpRendersAsBlockWithinHeight(t *testing.T) {
	m := layoutModel(true, 90, 16)
	m.helpOpen = true
	lines := plainLines(m)
	if len(lines) > m.height {
		t.Fatalf("view has %d lines for height %d", len(lines), m.height)
	}
	lineWith(t, lines, "S reverse direction")
	lineWith(t, lines, "E/C expand/collapse all")
}

func TestMultilineErrorsRenderOnOneLine(t *testing.T) {
	m := layoutModel(true, 100, 20)
	next, _ := m.Update(snapshotFailedMsg{err: errString("HTTP 404: not found\n")})
	m = next.(Model)
	if line := lineWith(t, plainLines(m), "Last refresh failed"); !strings.HasSuffix(line, "not found") {
		t.Fatalf("error line=%q", line)
	}
}

func TestEndKeySelectsLastVisibleRowWhenGroupsCollapsed(t *testing.T) {
	m := layoutModel(true, 100, 20)
	for _, code := range []rune{'v', 'C', 'G'} {
		next, _ := m.Update(key(code))
		m = next.(Model)
	}
	if want := int64(m.displayRows[len(m.displayRows)-1].process.PID); m.selectedPID != want {
		t.Fatalf("G selected %d want %d (rows=%d)", m.selectedPID, want, len(m.displayRows))
	}
}

func TestDetailAttributeValuesAlign(t *testing.T) {
	m := layoutModel(true, 100, 60)
	p := m.processes[0]
	m.detailsOpen, m.detailProcess = true, &p
	lines := plainLines(m)
	want := -1
	for _, label := range []string{"Type", "Metaspace used", "Virtual memory", "Java version", "Open files"} {
		line := lineWith(t, lines, "  "+label+" ")
		rest := line[strings.Index(line, label)+len(label):]
		start := column(line, strings.TrimLeft(rest, " "))
		if want == -1 {
			want = start
		}
		if start != want {
			t.Fatalf("%s value starts at %d, want %d: %q", label, start, want, line)
		}
	}
}

func TestDetailsHeaderAndFooterFitWithoutEllipsis(t *testing.T) {
	for _, w := range []int{80, 120} {
		m := layoutModel(true, w, 30)
		p := m.processes[0]
		m.detailsOpen, m.detailProcess = true, &p
		lines := plainLines(m)
		for _, line := range []string{lines[0], lines[len(lines)-1]} {
			if strings.Contains(line, "…") || terminalansi.StringWidth(line) > w {
				t.Fatalf("width %d: line=%q", w, line)
			}
		}
		if !strings.Contains(lines[0], "pid 43122") || !strings.Contains(lines[len(lines)-1], "esc back") {
			t.Fatalf("width %d: header=%q footer=%q", w, lines[0], lines[len(lines)-1])
		}
	}
}
