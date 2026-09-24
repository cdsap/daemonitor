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
	snap model.Snapshot
	err  error
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
	// Drain: only scheduleTick should run (no fetch). Execute once.
	msg := cmd()
	if _, ok := msg.(tickMsg); !ok {
		// BatchMsg may wrap a single tick; either way no snapshot call yet.
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

func apply(m Model, msg tea.Msg) (Model, tea.Cmd) {
	next, cmd := m.Update(msg)
	return next.(Model), cmd
}

func renderContent(m Model) string {
	// View builds tea.View; re-use render for assertions.
	return m.render()
}

func contains(s, sub string) bool {
	return strings.Contains(s, sub)
}
