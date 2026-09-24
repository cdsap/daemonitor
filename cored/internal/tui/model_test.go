package tui

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

type fakeFetcher struct {
	snap  model.Snapshot
	err   error
	calls int
}

func (f *fakeFetcher) Processes(ctx context.Context) (model.Snapshot, error) {
	f.calls++
	if f.err != nil {
		return model.Snapshot{}, f.err
	}
	return f.snap, nil
}

func TestUpdateSnapshotLoadedSortsByRSS(t *testing.T) {
	cpu := 12.5
	xmx := int64(4096)
	m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second})
	msg := snapshotLoadedMsg{
		at: time.Unix(1700000000, 0),
		snap: model.Snapshot{
			SampledAtMs: 1700000000000,
			Processes: []model.Process{
				{PID: 2, Type: "KOTLIN_DAEMON", RSSMemoryMB: 100, CPUPercent: &cpu},
				{PID: 1, Type: "GRADLE_DAEMON", RSSMemoryMB: 500, MaxHeapMB: &xmx},
				{PID: 3, Type: "GRADLE_DAEMON", RSSMemoryMB: 500},
			},
		},
	}
	next, _ := m.Update(msg)
	got := next.(Model).Processes()
	if len(got) != 3 {
		t.Fatalf("len=%d", len(got))
	}
	if got[0].PID != 1 || got[1].PID != 3 || got[2].PID != 2 {
		t.Fatalf("unexpected order: %#v", got)
	}
	if !next.(Model).Connected() {
		t.Fatal("expected connected")
	}
	if next.(Model).SelectedPID() != 1 {
		t.Fatalf("expected auto-select top PID, got %d", next.(Model).SelectedPID())
	}
}

func TestUpdateSnapshotFailedKeepsPriorSnapshot(t *testing.T) {
	m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second})
	m, _ = apply(m, snapshotLoadedMsg{
		at: time.Unix(1, 0),
		snap: model.Snapshot{
			Processes: []model.Process{{PID: 9, Type: "GRADLE_DAEMON", RSSMemoryMB: 64}},
		},
	})
	m, _ = apply(m, snapshotFailedMsg{err: errors.New("boom"), at: time.Unix(2, 0)})
	if len(m.Processes()) != 1 || m.Processes()[0].PID != 9 {
		t.Fatalf("lost snapshot: %#v", m.Processes())
	}
	if m.LastError() == nil {
		t.Fatal("expected last error")
	}
	if !m.Connected() {
		t.Fatal("expected still connected")
	}
}

func TestQuitKey(t *testing.T) {
	m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second})
	next, cmd := m.Update(tea.KeyPressMsg{Text: "q", Code: 'q'})
	if !next.(Model).quitting {
		t.Fatal("expected quitting")
	}
	if cmd == nil {
		t.Fatal("expected quit command")
	}
}

func TestWindowSizeUpdates(t *testing.T) {
	m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second})
	next, _ := m.Update(tea.WindowSizeMsg{Width: 120, Height: 40})
	got := next.(Model)
	if got.Width() != 120 || got.Height() != 40 {
		t.Fatalf("size=%dx%d", got.Width(), got.Height())
	}
	view := got.View()
	if !view.AltScreen {
		t.Fatal("expected alt screen")
	}
	content := renderContent(got)
	if content == "" {
		t.Fatal("expected rendered content")
	}
}

func TestTickSkipsFetchWhileLoading(t *testing.T) {
	f := &fakeFetcher{snap: model.Snapshot{Processes: nil}}
	m := NewModel(Config{Fetcher: f, PollInterval: 50 * time.Millisecond})
	m.loading = true
	_, cmd := m.Update(tickMsg(time.Now()))
	if cmd == nil {
		t.Fatal("expected tick reschedule")
	}
	msg := cmd()
	if _, ok := msg.(tickMsg); !ok {
		if batch, ok := msg.(tea.BatchMsg); ok {
			for _, c := range batch {
				if c != nil {
					_ = c()
				}
			}
		}
	}
	if f.calls != 0 {
		t.Fatalf("fetch should be skipped while loading, calls=%d", f.calls)
	}
}

func TestRenderEmptyConnected(t *testing.T) {
	m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second, SocketPath: "/tmp/x.sock"})
	m, _ = apply(m, tea.WindowSizeMsg{Width: 100, Height: 24})
	m, _ = apply(m, snapshotLoadedMsg{at: time.Now(), snap: model.Snapshot{Processes: nil}})
	out := renderContent(m)
	if !contains(out, "No Gradle-related processes") {
		t.Fatalf("unexpected view:\n%s", out)
	}
}

func TestFormatMemAndType(t *testing.T) {
	if got := formatMemMB(100); got != "100 MB" {
		t.Fatalf("got %q", got)
	}
	if got := formatMemMB(2048); got != "2.0 GB" {
		t.Fatalf("got %q", got)
	}
	if got := typeDisplay("GRADLE_DAEMON"); got != "Gradle daemon" {
		t.Fatalf("got %q", got)
	}
}

func TestSelectionKeysMoveWithoutEnter(t *testing.T) {
	m := seededModel(t, []model.Process{
		{PID: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 300},
		{PID: 20, Type: "KOTLIN_DAEMON", RSSMemoryMB: 200},
		{PID: 30, Type: "TEST_WORKER", RSSMemoryMB: 100},
	}, 80, 24)
	if m.SelectedPID() != 10 {
		t.Fatalf("initial=%d", m.SelectedPID())
	}
	m, _ = apply(m, tea.KeyPressMsg{Text: "j", Code: 'j'})
	if m.SelectedPID() != 20 {
		t.Fatalf("after j got %d", m.SelectedPID())
	}
	m, _ = apply(m, tea.KeyPressMsg{Code: tea.KeyDown})
	if m.SelectedPID() != 30 {
		t.Fatalf("after down got %d", m.SelectedPID())
	}
	m, _ = apply(m, tea.KeyPressMsg{Text: "k", Code: 'k'})
	if m.SelectedPID() != 20 {
		t.Fatalf("after k got %d", m.SelectedPID())
	}
	m, _ = apply(m, tea.KeyPressMsg{Code: tea.KeyUp})
	if m.SelectedPID() != 10 {
		t.Fatalf("after up got %d", m.SelectedPID())
	}
	m, _ = apply(m, tea.KeyPressMsg{Text: "G", Code: 'G'})
	if m.SelectedPID() != 30 {
		t.Fatalf("after G got %d", m.SelectedPID())
	}
	m, _ = apply(m, tea.KeyPressMsg{Text: "g", Code: 'g'})
	if m.SelectedPID() != 10 {
		t.Fatalf("after g got %d", m.SelectedPID())
	}
	m, _ = apply(m, tea.KeyPressMsg{Code: tea.KeyEnd})
	if m.SelectedPID() != 30 {
		t.Fatalf("after end got %d", m.SelectedPID())
	}
	m, _ = apply(m, tea.KeyPressMsg{Code: tea.KeyHome})
	if m.SelectedPID() != 10 {
		t.Fatalf("after home got %d", m.SelectedPID())
	}
}

func TestSelectionSurvivesReorder(t *testing.T) {
	m := seededModel(t, []model.Process{
		{PID: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 300},
		{PID: 20, Type: "KOTLIN_DAEMON", RSSMemoryMB: 200},
	}, 80, 24)
	m, _ = apply(m, tea.KeyPressMsg{Text: "j", Code: 'j'})
	if m.SelectedPID() != 20 {
		t.Fatalf("selected=%d", m.SelectedPID())
	}
	// Reorder so PID 20 becomes the top row (higher RSS).
	m, _ = apply(m, snapshotLoadedMsg{
		at: time.Unix(2, 0),
		snap: model.Snapshot{Processes: []model.Process{
			{PID: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 50},
			{PID: 20, Type: "KOTLIN_DAEMON", RSSMemoryMB: 400},
			{PID: 30, Type: "TEST_WORKER", RSSMemoryMB: 10},
		}},
	})
	if m.SelectedPID() != 20 {
		t.Fatalf("selection should follow PID across reorder, got %d", m.SelectedPID())
	}
	if m.Processes()[0].PID != 20 {
		t.Fatalf("expected PID 20 at top after reorder, got %d", m.Processes()[0].PID)
	}
}

func TestSelectionMovesWhenPIDExits(t *testing.T) {
	m := seededModel(t, []model.Process{
		{PID: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 300},
		{PID: 20, Type: "KOTLIN_DAEMON", RSSMemoryMB: 200},
		{PID: 30, Type: "TEST_WORKER", RSSMemoryMB: 100},
	}, 80, 24)
	m, _ = apply(m, tea.KeyPressMsg{Text: "j", Code: 'j'}) // select 20 (index 1)
	m, _ = apply(m, snapshotLoadedMsg{
		at: time.Unix(2, 0),
		snap: model.Snapshot{Processes: []model.Process{
			{PID: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 300},
			{PID: 30, Type: "TEST_WORKER", RSSMemoryMB: 100},
		}},
	})
	if m.SelectedPID() != 30 {
		t.Fatalf("expected move to index-1 survivor, got %d", m.SelectedPID())
	}
}

func TestHelpToggle(t *testing.T) {
	m := seededModel(t, []model.Process{
		{PID: 1, Type: "GRADLE_DAEMON", RSSMemoryMB: 64},
	}, 80, 24)
	if m.ShowHelp() {
		t.Fatal("help should start closed")
	}
	m, _ = apply(m, tea.KeyPressMsg{Text: "?", Code: '?'})
	if !m.ShowHelp() {
		t.Fatal("expected help open")
	}
	out := stripANSI(renderContent(m))
	if !strings.Contains(out, "Navigation:") {
		t.Fatalf("expected expanded help:\n%s", out)
	}
	if !strings.Contains(out, "cycle sort") {
		t.Fatalf("expected sort help:\n%s", out)
	}
	m, _ = apply(m, tea.KeyPressMsg{Text: "?", Code: '?'})
	if m.ShowHelp() {
		t.Fatal("expected help closed")
	}
}

func TestSortCycleAndReverse(t *testing.T) {
	cpuHigh := 90.0
	cpuLow := 10.0
	m := seededModel(t, []model.Process{
		{PID: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 300, CPUPercent: &cpuLow, ProjectPath: strPtr("zeta")},
		{PID: 20, Type: "KOTLIN_DAEMON", RSSMemoryMB: 100, CPUPercent: &cpuHigh, ProjectPath: strPtr("alpha")},
	}, 80, 24)
	if m.SortField() != SortRSS || m.SortOrder() != SortDesc {
		t.Fatalf("default sort=%v/%v", m.SortField(), m.SortOrder())
	}
	if m.Processes()[0].PID != 10 {
		t.Fatalf("RSS desc expected PID 10 first, got %d", m.Processes()[0].PID)
	}

	m, _ = apply(m, tea.KeyPressMsg{Text: "s", Code: 's'})
	if m.SortField() != SortCPU || m.SortOrder() != SortDesc {
		t.Fatalf("after s: sort=%v/%v", m.SortField(), m.SortOrder())
	}
	if m.Processes()[0].PID != 20 {
		t.Fatalf("CPU desc expected PID 20 first, got %d", m.Processes()[0].PID)
	}

	m, _ = apply(m, tea.KeyPressMsg{Text: "S", Code: 'S'})
	if m.SortOrder() != SortAsc {
		t.Fatalf("expected reverse to asc, got %v", m.SortOrder())
	}
	if m.Processes()[0].PID != 10 {
		t.Fatalf("CPU asc expected PID 10 first, got %d", m.Processes()[0].PID)
	}

	// Cycle through remaining fields back to RSS.
	for i := 0; i < 5; i++ {
		m, _ = apply(m, tea.KeyPressMsg{Text: "s", Code: 's'})
	}
	if m.SortField() != SortRSS {
		t.Fatalf("expected wrap to RSS, got %v", m.SortField())
	}
	out := stripANSI(renderContent(m))
	if !strings.Contains(out, "RSS↑") && !strings.Contains(out, "RSS↓") {
		t.Fatalf("active sort should appear in header:\n%s", out)
	}
}

func TestPauseAndManualRefresh(t *testing.T) {
	f := &fakeFetcher{snap: model.Snapshot{Processes: []model.Process{
		{PID: 1, Type: "GRADLE_DAEMON", RSSMemoryMB: 64},
	}}}
	m := NewModel(Config{Fetcher: f, PollInterval: time.Second})
	m, _ = apply(m, snapshotLoadedMsg{at: time.Unix(1, 0), snap: f.snap})
	m.loading = false
	f.calls = 0

	m, _ = apply(m, tea.KeyPressMsg{Text: " ", Code: ' '})
	if !m.Paused() {
		t.Fatal("expected paused")
	}
	out := stripANSI(renderContent(m))
	if !strings.Contains(out, "PAUSED") {
		t.Fatalf("expected PAUSED in header:\n%s", out)
	}

	_, cmd := m.Update(tickMsg(time.Now()))
	_ = drainCmds(cmd)
	if f.calls != 0 {
		t.Fatalf("tick must not fetch while paused, calls=%d", f.calls)
	}

	m, cmd = apply(m, tea.KeyPressMsg{Text: "r", Code: 'r'})
	if !m.Loading() {
		t.Fatal("manual refresh should set loading")
	}
	msg := cmd()
	if _, ok := msg.(snapshotLoadedMsg); !ok {
		t.Fatalf("expected snapshotLoadedMsg, got %T", msg)
	}
	if f.calls != 1 {
		t.Fatalf("expected one fetch on r while paused, calls=%d", f.calls)
	}

	m, _ = apply(m, tea.KeyPressMsg{Text: " ", Code: tea.KeySpace})
	if m.Paused() {
		t.Fatal("expected resume")
	}
}

func TestTickSkipsFetchWhilePaused(t *testing.T) {
	f := &fakeFetcher{snap: model.Snapshot{}}
	m := NewModel(Config{Fetcher: f, PollInterval: time.Second})
	m.loading = false
	m.paused = true
	_, cmd := m.Update(tickMsg(time.Now()))
	_ = drainCmds(cmd)
	if f.calls != 0 {
		t.Fatalf("paused tick fetched, calls=%d", f.calls)
	}
}

func TestSingleInFlightSnapshot(t *testing.T) {
	f := &fakeFetcher{snap: model.Snapshot{}}
	m := NewModel(Config{Fetcher: f, PollInterval: time.Second})
	m.loading = true
	m.paused = false
	_, cmd := m.Update(tickMsg(time.Now()))
	_ = drainCmds(cmd)
	if f.calls != 0 {
		t.Fatalf("in-flight tick must not start another fetch, calls=%d", f.calls)
	}
	m, cmd = apply(m, tea.KeyPressMsg{Text: "r", Code: 'r'})
	if cmd != nil {
		t.Fatal("r while loading should not return a new fetch cmd")
	}
	if f.calls != 0 {
		t.Fatalf("calls=%d", f.calls)
	}
}

func TestEmptyAndErrorStates(t *testing.T) {
	m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second, SocketPath: "/tmp/daemonitor-core.sock"})
	m, _ = apply(m, tea.WindowSizeMsg{Width: 100, Height: 24})
	m, _ = apply(m, snapshotLoadedMsg{at: time.Unix(1, 0), snap: model.Snapshot{}})
	out := stripANSI(renderContent(m))
	if !strings.Contains(out, "No Gradle-related processes") || !strings.Contains(out, "Waiting for activity") {
		t.Fatalf("empty state:\n%s", out)
	}

	m = NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second, SocketPath: "/tmp/daemonitor-core.sock"})
	m, _ = apply(m, tea.WindowSizeMsg{Width: 100, Height: 24})
	m, _ = apply(m, snapshotFailedMsg{err: errors.New("dial unix"), at: time.Unix(1, 0)})
	out = stripANSI(renderContent(m))
	if !strings.Contains(out, "Unable to reach") || !strings.Contains(out, "/tmp/daemonitor-core.sock") {
		t.Fatalf("pre-connect error missing socket/hint:\n%s", out)
	}
	if !strings.Contains(out, "start daemonitor") && !strings.Contains(out, "check --socket") {
		t.Fatalf("expected recovery hint:\n%s", out)
	}
}

func drainCmds(cmd tea.Cmd) tea.Msg {
	if cmd == nil {
		return nil
	}
	msg := cmd()
	if batch, ok := msg.(tea.BatchMsg); ok {
		var last tea.Msg
		for _, c := range batch {
			if c != nil {
				last = c()
			}
		}
		return last
	}
	return msg
}

func strPtr(s string) *string { return &s }


func TestScrollKeepsSelectionVisible(t *testing.T) {
	procs := make([]model.Process, 30)
	for i := range procs {
		procs[i] = model.Process{
			PID:         int32(100 + i),
			Type:        "GRADLE_DAEMON",
			RSSMemoryMB: int64(300 - i),
		}
	}
	m := seededModel(t, procs, 80, 15)
	visible := m.visibleRows()
	if visible < 2 {
		t.Fatalf("expected room for rows, visible=%d", visible)
	}
	// Move to last row; offset must advance so selection stays in view.
	m, _ = apply(m, tea.KeyPressMsg{Text: "G", Code: 'G'})
	if m.SelectedPID() != procs[len(procs)-1].PID {
		t.Fatalf("selected=%d", m.SelectedPID())
	}
	idx := m.selectedIndex()
	if idx < m.Offset() || idx >= m.Offset()+visible {
		t.Fatalf("selection %d not visible in [%d,%d)", idx, m.Offset(), m.Offset()+visible)
	}
}

func TestAdaptiveColumnsByWidth(t *testing.T) {
	full := selectColumns(120)
	medium := selectColumns(90)
	narrow := selectColumns(60)
	if full.tier != "full" || medium.tier != "medium" || narrow.tier != "narrow" {
		t.Fatalf("tiers=%s/%s/%s", full.tier, medium.tier, narrow.tier)
	}
	if hasColumn(narrow, colXmx) {
		t.Fatal("narrow tier must omit Xmx")
	}
	if !hasColumn(medium, colXmx) || !hasColumn(medium, colUptime) {
		t.Fatal("medium tier should include Xmx and uptime")
	}
	if !hasColumn(full, colUptime) || !hasColumn(full, colProject) {
		t.Fatal("full tier should include uptime and project")
	}
	if medium.projectWidth >= full.projectWidth {
		t.Fatalf("medium project width should be shortened: med=%d full=%d", medium.projectWidth, full.projectWidth)
	}
}

func TestUnicodeTruncation(t *testing.T) {
	got := truncateRunes("日本語プロジェクト名", 6)
	if displayWidth(got) > 6 {
		t.Fatalf("width=%d text=%q", displayWidth(got), got)
	}
	if !strings.HasSuffix(got, "…") {
		t.Fatalf("expected ellipsis, got %q", got)
	}
}

func TestNarrowTerminalEnlargeMessage(t *testing.T) {
	m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second})
	m, _ = apply(m, tea.WindowSizeMsg{Width: 20, Height: 5})
	out := renderContent(m)
	if !strings.Contains(out, "too small") {
		t.Fatalf("expected enlarge message:\n%s", out)
	}
}

func hasColumn(cols columnSet, id columnID) bool {
	for _, c := range cols.ids {
		if c == id {
			return true
		}
	}
	return false
}

func seededModel(t *testing.T, procs []model.Process, w, h int) Model {
	t.Helper()
	m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second})
	m, _ = apply(m, tea.WindowSizeMsg{Width: w, Height: h})
	m, _ = apply(m, snapshotLoadedMsg{
		at:   time.Unix(1, 0),
		snap: model.Snapshot{Processes: procs},
	})
	return m
}

func apply(m Model, msg tea.Msg) (Model, tea.Cmd) {
	next, cmd := m.Update(msg)
	return next.(Model), cmd
}

func renderContent(m Model) string {
	return m.render()
}

func contains(s, sub string) bool {
	return strings.Contains(s, sub)
}
