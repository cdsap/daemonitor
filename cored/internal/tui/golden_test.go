package tui

import (
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

var updateGolden = flag.Bool("update", false, "update golden render files")

func TestGoldenRenders(t *testing.T) {
	project := "/Users/dev/work/daemonitor-with-a-very-long-project-name"
	cpu := 42.5
	xmx := int64(4096)
	procs := []model.Process{
		{PID: 43122, Type: "GRADLE_DAEMON", RSSMemoryMB: 2100, CPUPercent: &cpu, MaxHeapMB: &xmx, ProjectPath: &project, StartTimeMs: 1_699_999_000_000},
		{PID: 43091, Type: "KOTLIN_DAEMON", RSSMemoryMB: 1300, MaxHeapMB: &xmx, WorkingDirectory: &project, StartTimeMs: 1_699_998_000_000},
		{PID: 44001, Type: "TEST_WORKER", RSSMemoryMB: 400, Name: "Worker 日本語", StartTimeMs: 1_699_997_000_000},
		{PID: 44002, Type: "GRADLE_WRAPPER", RSSMemoryMB: 200, ProjectPath: &project, StartTimeMs: 1_699_996_000_000},
		{PID: 44003, Type: "JAVA_GRADLE_RELATED", RSSMemoryMB: 150, StartTimeMs: 1_699_995_000_000},
		{PID: 44004, Type: "GRADLE_DAEMON", RSSMemoryMB: 100, ProjectPath: &project, StartTimeMs: 1_699_994_000_000},
		{PID: 44005, Type: "KOTLIN_DAEMON", RSSMemoryMB: 90, StartTimeMs: 1_699_993_000_000},
		{PID: 44006, Type: "TEST_WORKER", RSSMemoryMB: 80, StartTimeMs: 1_699_992_000_000},
	}
	fixedAt := time.Unix(1_700_000_000, 0).UTC()

	cases := []struct {
		name string
		w, h int
		sel  int // index after load, -1 = leave auto
		keys []tea.Msg
	}{
		{name: "60x15", w: 60, h: 15, sel: 1},
		{name: "80x24", w: 80, h: 24, sel: 2},
		{name: "120x30", w: 120, h: 30, sel: 0},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: 2 * time.Second, SocketPath: "/tmp/daemonitor-core.sock"})
			m, _ = apply(m, tea.WindowSizeMsg{Width: tc.w, Height: tc.h})
			m, _ = apply(m, snapshotLoadedMsg{at: fixedAt, snap: model.Snapshot{Processes: procs}})
			if tc.sel > 0 {
				m.selectIndex(tc.sel)
			}
			for _, key := range tc.keys {
				m, _ = apply(m, key)
			}

			got := normalizeGolden(stripANSI(renderContent(m)), tc.w)
			path := filepath.Join("testdata", "golden_"+tc.name+".txt")
			if *updateGolden {
				if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(path, []byte(got), 0o644); err != nil {
					t.Fatal(err)
				}
			}
			wantBytes, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("read golden (run with -update): %v", err)
			}
			want := string(wantBytes)
			if got != want {
				t.Fatalf("golden mismatch for %s\n--- got ---\n%s\n--- want ---\n%s", tc.name, got, want)
			}

			// Content must never intentionally exceed terminal width.
			for i, line := range strings.Split(got, "\n") {
				if displayWidth(line) > tc.w {
					t.Fatalf("line %d width %d > %d: %q", i+1, displayWidth(line), tc.w, line)
				}
			}
		})
	}
}

func TestGoldenEmptyAndOverflow(t *testing.T) {
	m := NewModel(Config{Fetcher: &fakeFetcher{}, PollInterval: time.Second})
	m, _ = apply(m, tea.WindowSizeMsg{Width: 80, Height: 24})
	m, _ = apply(m, snapshotLoadedMsg{at: time.Unix(1_700_000_000, 0).UTC(), snap: model.Snapshot{}})
	out := stripANSI(renderContent(m))
	if !strings.Contains(out, "No Gradle-related processes") {
		t.Fatalf("empty state missing:\n%s", out)
	}
	for i, line := range strings.Split(out, "\n") {
		if displayWidth(line) > 80 {
			t.Fatalf("line %d exceeds width: %q", i+1, line)
		}
	}
}

func normalizeGolden(s string, width int) string {
	// Ensure trailing newline for stable files.
	s = strings.ReplaceAll(s, "\r\n", "\n")
	if !strings.HasSuffix(s, "\n") {
		s += "\n"
	}
	_ = width
	return s
}

func displayWidth(s string) int {
	return lipgloss.Width(s)
}
