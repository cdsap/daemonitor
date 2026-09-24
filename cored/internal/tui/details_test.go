package tui

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

type fakeLogFetcher struct {
	tails map[int32]*model.DaemonLogTail
	err   error
	calls map[int32]int
}

func (f *fakeLogFetcher) DaemonLogTail(_ context.Context, pid int32) (*model.DaemonLogTail, error) {
	if f.calls == nil {
		f.calls = map[int32]int{}
	}
	f.calls[pid]++
	if f.err != nil {
		return nil, f.err
	}
	return f.tails[pid], nil
}

func (f *fakeLogFetcher) total() int {
	n := 0
	for _, c := range f.calls {
		n += c
	}
	return n
}

var (
	enterKey = tea.KeyPressMsg{Code: tea.KeyEnter}
	escKey   = tea.KeyPressMsg{Code: tea.KeyEscape}
)

func detailsModel(t *testing.T, logs LogFetcher, procs []model.Process, w, h int) Model {
	t.Helper()
	m := NewModel(Config{Fetcher: &fakeFetcher{}, Logs: logs, PollInterval: 2 * time.Second, SocketPath: "/tmp/daemonitor-core.sock"})
	m, _ = apply(m, tea.WindowSizeMsg{Width: w, Height: h})
	m, _ = apply(m, snapshotLoadedMsg{at: time.Unix(1_700_000_000, 0).UTC(), snap: model.Snapshot{Processes: procs}})
	return m
}

func twoDaemons() []model.Process {
	return []model.Process{
		{PID: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 300},
		{PID: 20, Type: "KOTLIN_DAEMON", RSSMemoryMB: 200},
	}
}

func TestEnterAndEscKeyNames(t *testing.T) {
	if got := enterKey.String(); got != "enter" {
		t.Fatalf("enter key string = %q", got)
	}
	if got := escKey.String(); got != "esc" {
		t.Fatalf("esc key string = %q", got)
	}
}

func TestEnterOpensDetailsAndEscReturnsToTable(t *testing.T) {
	m := detailsModel(t, &fakeLogFetcher{}, twoDaemons(), 80, 24)
	m, _ = apply(m, tea.KeyPressMsg{Text: "j", Code: 'j'})

	m, _ = apply(m, enterKey)
	if !m.DetailsOpen() {
		t.Fatal("enter should open details")
	}
	out := stripANSI(renderContent(m))
	if !strings.Contains(out, "Kotlin daemon · PID 20") || !strings.Contains(out, "esc back") {
		t.Fatalf("expected details view:\n%s", out)
	}

	// Table navigation is inert while details are open.
	m, _ = apply(m, tea.KeyPressMsg{Text: "k", Code: 'k'})
	m, _ = apply(m, tea.KeyPressMsg{Text: "s", Code: 's'})
	if m.SelectedPID() != 20 || m.SortField() != SortRSS {
		t.Fatalf("details must not change table state: pid=%d sort=%v", m.SelectedPID(), m.SortField())
	}

	m, _ = apply(m, escKey)
	if m.DetailsOpen() {
		t.Fatal("esc should close details")
	}
	if m.SelectedPID() != 20 {
		t.Fatalf("selection should be preserved, got %d", m.SelectedPID())
	}
	if out := stripANSI(renderContent(m)); !strings.Contains(out, "TYPE") {
		t.Fatalf("expected table after esc:\n%s", out)
	}
}

func TestEnterWithoutProcessesIsNoop(t *testing.T) {
	m := detailsModel(t, &fakeLogFetcher{}, nil, 80, 24)
	m, cmd := apply(m, enterKey)
	if m.DetailsOpen() || cmd != nil {
		t.Fatal("enter with no selection must not open details")
	}
}

func TestQuitFromDetails(t *testing.T) {
	m := detailsModel(t, &fakeLogFetcher{}, twoDaemons(), 80, 24)
	m, _ = apply(m, enterKey)
	m, cmd := apply(m, tea.KeyPressMsg{Text: "q", Code: 'q'})
	if !m.quitting || cmd == nil {
		t.Fatal("q should quit from details")
	}
}

func TestLogTailFetchedLazilyForSelectedDaemonOnly(t *testing.T) {
	logs := &fakeLogFetcher{tails: map[int32]*model.DaemonLogTail{
		10: {PID: 10, GradleVersion: "8.10.2", Path: "/home/dev/.gradle/daemon/8.10.2/daemon-10.out.log", Lines: []string{"line one", "line two"}},
	}}
	f := &fakeFetcher{snap: model.Snapshot{Processes: twoDaemons()}}
	m := NewModel(Config{Fetcher: f, Logs: logs, PollInterval: time.Second})
	m, _ = apply(m, tea.WindowSizeMsg{Width: 100, Height: 30})
	m, _ = apply(m, snapshotLoadedMsg{at: time.Unix(1, 0), snap: f.snap})
	m, _ = apply(m, tea.KeyPressMsg{Text: "j", Code: 'j'})
	m, _ = apply(m, tea.KeyPressMsg{Text: "k", Code: 'k'})
	_, cmd := m.Update(tickMsg(time.Now()))
	_ = drainCmds(cmd)
	if logs.total() != 0 {
		t.Fatalf("table rendering/navigation must not fetch logs, calls=%v", logs.calls)
	}

	m, cmd = apply(m, enterKey)
	if cmd == nil {
		t.Fatal("expected lazy log fetch command")
	}
	if out := stripANSI(renderContent(m)); !strings.Contains(out, "Loading daemon log") {
		t.Fatalf("expected loading state:\n%s", out)
	}
	msg := cmd()
	if logs.calls[10] != 1 || logs.total() != 1 {
		t.Fatalf("expected exactly one fetch for PID 10, calls=%v", logs.calls)
	}
	m, _ = apply(m, msg)
	out := stripANSI(renderContent(m))
	for _, want := range []string{"8.10.2", "daemon-10.out.log", "line one", "line two"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q:\n%s", want, out)
		}
	}

	// Snapshot refreshes while details are open do not refetch the tail.
	m.loading = false
	_, cmd = m.Update(tickMsg(time.Now()))
	_ = drainCmds(cmd)
	if logs.total() != 1 {
		t.Fatalf("ticks must not refetch logs, calls=%v", logs.calls)
	}
}

func TestRefreshInDetailsRefetchesLogTail(t *testing.T) {
	logs := &fakeLogFetcher{tails: map[int32]*model.DaemonLogTail{10: {PID: 10, Lines: []string{"x"}}}}
	f := &fakeFetcher{snap: model.Snapshot{Processes: twoDaemons()}}
	m := NewModel(Config{Fetcher: f, Logs: logs, PollInterval: time.Second})
	m, _ = apply(m, snapshotLoadedMsg{at: time.Unix(1, 0), snap: f.snap})
	m, cmd := apply(m, enterKey)
	m, _ = apply(m, cmd())

	f.calls = 0
	m, cmd = apply(m, tea.KeyPressMsg{Text: "r", Code: 'r'})
	_ = drainCmds(cmd)
	if logs.calls[10] != 2 || f.calls != 1 {
		t.Fatalf("r should refresh snapshot and tail: logs=%v snapshots=%d", logs.calls, f.calls)
	}

	// A second r while the tail is still loading must not stack requests.
	m.loading = true
	_, cmd = apply(m, tea.KeyPressMsg{Text: "r", Code: 'r'})
	_ = drainCmds(cmd)
	if logs.calls[10] != 2 {
		t.Fatalf("concurrent tail request started, calls=%v", logs.calls)
	}
}

func TestNonDaemonDetailsSkipLogFetch(t *testing.T) {
	logs := &fakeLogFetcher{}
	m := detailsModel(t, logs, twoDaemons(), 100, 30)
	m, _ = apply(m, tea.KeyPressMsg{Text: "j", Code: 'j'})
	m, cmd := apply(m, enterKey)
	if cmd != nil {
		t.Fatal("non-daemon processes have no daemon log to fetch")
	}
	if logs.total() != 0 {
		t.Fatalf("calls=%v", logs.calls)
	}
	out := stripANSI(renderContent(m))
	if !strings.Contains(out, "only available for Gradle daemons") {
		t.Fatalf("expected non-daemon log hint:\n%s", out)
	}
}

func TestMissingAndFailedLogRenderNA(t *testing.T) {
	m := detailsModel(t, &fakeLogFetcher{}, twoDaemons(), 100, 30)
	m, cmd := apply(m, enterKey)
	m, _ = apply(m, cmd())
	out := stripANSI(renderContent(m))
	if !strings.Contains(out, "no daemon log found") {
		t.Fatalf("expected missing-log state:\n%s", out)
	}
	assertFieldValue(t, out, "Gradle version", "n/a")
	assertFieldValue(t, out, "Daemon log", "n/a")

	m = detailsModel(t, &fakeLogFetcher{err: errors.New("HTTP 500: boom")}, twoDaemons(), 100, 30)
	m, cmd = apply(m, enterKey)
	m, _ = apply(m, cmd())
	out = stripANSI(renderContent(m))
	if !strings.Contains(out, "Could not load daemon log: HTTP 500: boom") {
		t.Fatalf("expected failure state:\n%s", out)
	}
	assertFieldValue(t, out, "Gradle version", "n/a")
}

func TestDetailsWithoutLogFetcherRenderNA(t *testing.T) {
	m := detailsModel(t, nil, twoDaemons(), 100, 30)
	m, cmd := apply(m, enterKey)
	if cmd != nil {
		t.Fatal("no log fetcher configured: expected no command")
	}
	out := stripANSI(renderContent(m))
	assertFieldValue(t, out, "Daemon log", "n/a")
}

func TestUnavailableFieldsRenderNA(t *testing.T) {
	m := detailsModel(t, &fakeLogFetcher{}, []model.Process{{PID: 7, Type: "TEST_WORKER", RSSMemoryMB: 64}}, 100, 30)
	m, _ = apply(m, enterKey)
	out := stripANSI(renderContent(m))
	for _, label := range []string{"Name", "Project", "Working dir", "CPU", "Xmx", "Started", "Gradle version", "Daemon log"} {
		assertFieldValue(t, out, label, "n/a")
	}
	assertFieldValue(t, out, "Live heap", "used n/a   committed n/a")
}

func TestLiveHeapNeverShowsValue(t *testing.T) {
	cpu := 50.0
	xmx := int64(2048)
	project := "/work/app"
	m := detailsModel(t, &fakeLogFetcher{}, []model.Process{
		{PID: 1, Type: "GRADLE_DAEMON", RSSMemoryMB: 1500, CPUPercent: &cpu, MaxHeapMB: &xmx, ProjectPath: &project, StartTimeMs: 1_699_999_000_000},
	}, 100, 30)
	m, _ = apply(m, enterKey)
	out := stripANSI(renderContent(m))
	assertFieldValue(t, out, "Live heap", "used n/a   committed n/a")
	assertFieldValue(t, out, "Xmx", "2.0 GB")
	assertFieldValue(t, out, "CPU", "50.0%")
	assertFieldValue(t, out, "Started", "2023-11-14 21:56:40 (up 16m)")
}

func TestStaleLogResponseIgnored(t *testing.T) {
	logs := &fakeLogFetcher{tails: map[int32]*model.DaemonLogTail{
		10: {PID: 10, Lines: []string{"from ten"}},
		30: {PID: 30, Lines: []string{"from thirty"}},
	}}
	procs := []model.Process{
		{PID: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 300},
		{PID: 30, Type: "GRADLE_DAEMON", RSSMemoryMB: 100},
	}
	m := detailsModel(t, logs, procs, 100, 30)
	m, first := apply(m, enterKey)
	m, _ = apply(m, escKey)
	m, _ = apply(m, tea.KeyPressMsg{Text: "j", Code: 'j'})
	m, second := apply(m, enterKey)

	m, _ = apply(m, first())
	out := stripANSI(renderContent(m))
	if strings.Contains(out, "from ten") {
		t.Fatalf("stale tail for PID 10 leaked into PID 30 details:\n%s", out)
	}
	m, _ = apply(m, second())
	if out := stripANSI(renderContent(m)); !strings.Contains(out, "from thirty") {
		t.Fatalf("expected PID 30 tail:\n%s", out)
	}

	// A response arriving after esc is dropped.
	m, _ = apply(m, escKey)
	m, _ = apply(m, logTailLoadedMsg{pid: 30, seq: m.details.logSeq, tail: &model.DaemonLogTail{}})
	if m.details.logState != logLoaded {
		t.Fatalf("closed details must ignore late responses, state=%v", m.details.logState)
	}
}

func TestDetailsFollowLiveSnapshotAndExit(t *testing.T) {
	m := detailsModel(t, &fakeLogFetcher{}, twoDaemons(), 100, 30)
	m, _ = apply(m, enterKey)
	m, _ = apply(m, snapshotLoadedMsg{at: time.Unix(1_700_000_010, 0).UTC(), snap: model.Snapshot{Processes: []model.Process{
		{PID: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 900},
		{PID: 20, Type: "KOTLIN_DAEMON", RSSMemoryMB: 200},
	}}})
	out := stripANSI(renderContent(m))
	assertFieldValue(t, out, "RSS", "900 MB")

	m, _ = apply(m, snapshotLoadedMsg{at: time.Unix(1_700_000_020, 0).UTC(), snap: model.Snapshot{Processes: []model.Process{
		{PID: 20, Type: "KOTLIN_DAEMON", RSSMemoryMB: 200},
	}}})
	if !m.DetailsOpen() {
		t.Fatal("details should stay open after the process exits")
	}
	out = stripANSI(renderContent(m))
	if !strings.Contains(out, "exited (last known values)") {
		t.Fatalf("expected exited marker:\n%s", out)
	}
	assertFieldValue(t, out, "RSS", "900 MB")
}

func TestSanitizeForTerminalStripsControlSequences(t *testing.T) {
	got := sanitizeForTerminal("\x1b[31mred\x1b[0m\ttab\x07bell\r\n")
	if strings.ContainsRune(got, '\x1b') || strings.ContainsRune(got, '\x07') || strings.ContainsAny(got, "\r\n\t") {
		t.Fatalf("control characters survived: %q", got)
	}
	if got != "[31mred[0m tabbell" {
		t.Fatalf("got %q", got)
	}

	name := "evil\x1b]0;title\x07"
	m := detailsModel(t, &fakeLogFetcher{}, []model.Process{{PID: 5, Type: "TEST_WORKER", Name: name}}, 100, 30)
	m, _ = apply(m, enterKey)
	if out := stripANSI(renderContent(m)); strings.ContainsAny(out, "\x1b\x07") {
		t.Fatalf("control sequence from process name reached the view: %q", out)
	}
}

func TestDetailsFitSmallTerminal(t *testing.T) {
	logs := &fakeLogFetcher{tails: map[int32]*model.DaemonLogTail{10: {PID: 10, Lines: []string{"a", "b", "c"}}}}
	m := detailsModel(t, logs, twoDaemons(), 40, 8)
	m, cmd := apply(m, enterKey)
	m, _ = apply(m, cmd())
	out := stripANSI(renderContent(m))
	lines := strings.Split(out, "\n")
	if len(lines) > 8 {
		t.Fatalf("details overflow height: %d lines\n%s", len(lines), out)
	}
	if !strings.Contains(lines[len(lines)-1], "esc back") {
		t.Fatalf("footer must stay visible on small terminals:\n%s", out)
	}
	for i, line := range lines {
		if displayWidth(line) > 40 {
			t.Fatalf("line %d exceeds width: %q", i+1, line)
		}
	}
}

func TestGoldenDetails(t *testing.T) {
	project := "/Users/dev/work/daemonitor"
	cpu := 42.5
	xmx := int64(4096)
	daemon := model.Process{
		PID: 43122, Type: "GRADLE_DAEMON", Name: "GradleDaemon", RSSMemoryMB: 2100,
		CPUPercent: &cpu, MaxHeapMB: &xmx, ProjectPath: &project, WorkingDirectory: &project,
		StartTimeMs: 1_699_999_000_000,
	}
	worker := model.Process{PID: 44001, Type: "TEST_WORKER", RSSMemoryMB: 400, StartTimeMs: 1_699_997_000_000}
	logLines := make([]string, 0, 12)
	for i := 1; i <= 12; i++ {
		logLines = append(logLines, fmt.Sprintf("2023-11-14T22:13:%02d INFO build step %02d --password=***", i, i))
	}
	logs := &fakeLogFetcher{tails: map[int32]*model.DaemonLogTail{
		43122: {PID: 43122, GradleVersion: "8.10.2", Path: "/Users/dev/.gradle/daemon/8.10.2/daemon-43122.out.log", Lines: logLines},
	}}

	cases := []struct {
		name  string
		w, h  int
		procs []model.Process
	}{
		{name: "details_80x24", w: 80, h: 24, procs: []model.Process{daemon, worker}},
		{name: "details_120x30", w: 120, h: 30, procs: []model.Process{daemon, worker}},
		{name: "details_na_60x15", w: 60, h: 15, procs: []model.Process{worker}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			m := detailsModel(t, logs, tc.procs, tc.w, tc.h)
			m, cmd := apply(m, enterKey)
			if cmd != nil {
				m, _ = apply(m, cmd())
			}
			got := normalizeGolden(stripANSI(renderContent(m)), tc.w)
			path := filepath.Join("testdata", "golden_"+tc.name+".txt")
			if *updateGolden {
				if err := os.WriteFile(path, []byte(got), 0o644); err != nil {
					t.Fatal(err)
				}
			}
			want, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("read golden (run with -update): %v", err)
			}
			if got != string(want) {
				t.Fatalf("golden mismatch for %s\n--- got ---\n%s\n--- want ---\n%s", tc.name, got, want)
			}
			lines := strings.Split(strings.TrimSuffix(got, "\n"), "\n")
			if len(lines) > tc.h {
				t.Fatalf("%d lines > height %d", len(lines), tc.h)
			}
			for i, line := range lines {
				if displayWidth(line) > tc.w {
					t.Fatalf("line %d width %d > %d: %q", i+1, displayWidth(line), tc.w, line)
				}
			}
		})
	}
}

func assertFieldValue(t *testing.T, out, label, want string) {
	t.Helper()
	prefix := padRight(label, detailsLabelWidth)
	for _, line := range strings.Split(out, "\n") {
		if strings.HasPrefix(line, prefix) {
			if got := strings.TrimRight(strings.TrimPrefix(line, prefix), " "); got != want {
				t.Fatalf("%s = %q, want %q", label, got, want)
			}
			return
		}
	}
	t.Fatalf("field %q not rendered:\n%s", label, out)
}
