package tui

import (
	"context"
	"fmt"
	"sort"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

// Fetcher loads process snapshots for the TUI. Implementations must not block
// the Bubble Tea update loop; calls run inside tea.Cmd goroutines.
type Fetcher interface {
	Processes(ctx context.Context) (model.Snapshot, error)
}

// Config holds runtime options for the interactive monitor.
type Config struct {
	Fetcher      Fetcher
	PollInterval time.Duration
	SocketPath   string
}

// Model is the Bubble Tea presentation state for slice-1 process monitoring.
type Model struct {
	fetcher      Fetcher
	pollInterval time.Duration
	socketPath   string

	width  int
	height int

	processes   []model.Process
	lastUpdated time.Time
	lastError   error
	connected   bool
	loading     bool
	quitting    bool
}

// NewModel constructs the interactive monitor model.
func NewModel(cfg Config) Model {
	interval := cfg.PollInterval
	if interval <= 0 {
		interval = 2 * time.Second
	}
	return Model{
		fetcher:      cfg.Fetcher,
		pollInterval: interval,
		socketPath:   cfg.SocketPath,
		loading:      true,
	}
}

type tickMsg time.Time

type snapshotLoadedMsg struct {
	snap model.Snapshot
	at   time.Time
}

type snapshotFailedMsg struct {
	err error
	at  time.Time
}

// Init starts the first fetch and refresh ticker.
func (m Model) Init() tea.Cmd {
	return tea.Batch(m.fetchSnapshot(), m.scheduleTick())
}

// Update handles keyboard, resize, tick, and snapshot messages.
func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		return m, nil

	case tea.KeyPressMsg:
		switch msg.String() {
		case "q", "Q", "ctrl+c":
			m.quitting = true
			return m, tea.Quit
		}
		return m, nil

	case tickMsg:
		cmds := []tea.Cmd{m.scheduleTick()}
		if !m.loading {
			m.loading = true
			cmds = append(cmds, m.fetchSnapshot())
		}
		return m, tea.Batch(cmds...)

	case snapshotLoadedMsg:
		m.loading = false
		m.connected = true
		m.lastError = nil
		m.lastUpdated = msg.at
		m.processes = sortedProcesses(msg.snap.Processes)
		return m, nil

	case snapshotFailedMsg:
		m.loading = false
		m.lastError = msg.err
		if !m.connected {
			m.lastUpdated = msg.at
		}
		return m, nil
	}
	return m, nil
}

func (m Model) scheduleTick() tea.Cmd {
	return tea.Tick(m.pollInterval, func(t time.Time) tea.Msg {
		return tickMsg(t)
	})
}

func (m Model) fetchSnapshot() tea.Cmd {
	if m.fetcher == nil {
		return func() tea.Msg {
			return snapshotFailedMsg{err: fmt.Errorf("no process fetcher configured"), at: time.Now()}
		}
	}
	fetcher := m.fetcher
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		snap, err := fetcher.Processes(ctx)
		if err != nil {
			return snapshotFailedMsg{err: err, at: time.Now()}
		}
		return snapshotLoadedMsg{snap: snap, at: time.Now()}
	}
}

// sortedProcesses applies the default RSS-descending, PID-ascending order.
func sortedProcesses(in []model.Process) []model.Process {
	out := make([]model.Process, len(in))
	copy(out, in)
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].RSSMemoryMB != out[j].RSSMemoryMB {
			return out[i].RSSMemoryMB > out[j].RSSMemoryMB
		}
		return out[i].PID < out[j].PID
	})
	return out
}

// Processes returns the current process list (for tests).
func (m Model) Processes() []model.Process { return m.processes }

// LastError returns the most recent fetch error (for tests).
func (m Model) LastError() error { return m.lastError }

// Connected reports whether a successful snapshot has been received (for tests).
func (m Model) Connected() bool { return m.connected }

// Width returns the last known terminal width (for tests).
func (m Model) Width() int { return m.width }

// Height returns the last known terminal height (for tests).
func (m Model) Height() int { return m.height }
