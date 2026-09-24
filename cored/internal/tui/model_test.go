package tui

import (
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/clipperhouse/displaywidth"

	"github.com/cdsap/daemonitor/cored/internal/model"
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
	if n := len(columnsForWidth(120).ids); n < 8 {
		t.Fatalf("wide columns=%d", n)
	}
	if n := len(columnsForWidth(90).ids); n != 7 {
		t.Fatalf("medium columns=%d", n)
	}
	if n := len(columnsForWidth(70).ids); n != 5 {
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
	m.width, m.height = 80, 24
	m.connected = true
	m.lastUpdated = fixed
	m.applySnapshot(model.Snapshot{SampledAtMs: fixed.UnixMilli(), Processes: nil})
	body := m.View().Content
	if !strings.Contains(body, "No Gradle-related processes") {
		t.Fatalf("empty state missing: %s", body)
	}

	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", Name: "x", RSSMemoryMB: 1}
	m.detailsOpen = true
	m.detailProcess = &p
	m.detailLoading = false
	body = m.View().Content
	if !strings.Contains(body, "PROCESS DETAILS") || !strings.Contains(body, "Heap used") || !strings.Contains(body, "n/a") {
		t.Fatalf("details view unexpected: %s", body)
	}
}

func TestSmallTerminalMessage(t *testing.T) {
	m := NewModel(Config{Now: fixedNow})
	m.width, m.height = 20, 5
	if !strings.Contains(m.View().Content, "enlarge") {
		t.Fatal("expected enlarge message")
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
