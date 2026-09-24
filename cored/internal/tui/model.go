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

// Model is the Bubble Tea presentation state for the interactive monitor.
type Model struct {
	fetcher      Fetcher
	pollInterval time.Duration
	socketPath   string

	width  int
	height int

	processes   []model.Process
	selectedPID int32 // 0 means no selection
	offset      int   // first visible row index
	showHelp    bool

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
		m.ensureSelectionVisible()
		return m, nil

	case tea.KeyPressMsg:
		switch msg.String() {
		case "q", "Q", "ctrl+c":
			m.quitting = true
			return m, tea.Quit
		case "up", "k":
			m.moveSelection(-1)
		case "down", "j":
			m.moveSelection(1)
		case "home", "g":
			m.selectIndex(0)
		case "end", "G", "shift+g":
			m.selectIndex(len(m.processes) - 1)
		case "?":
			m.showHelp = !m.showHelp
			m.ensureSelectionVisible()
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
		m.applySnapshot(msg.snap.Processes)
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

// applySnapshot replaces the process list, preserving selection by PID.
func (m *Model) applySnapshot(in []model.Process) {
	prevPID := m.selectedPID
	prevIdx := m.selectedIndex()
	m.processes = sortedProcesses(in)

	if len(m.processes) == 0 {
		m.selectedPID = 0
		m.offset = 0
		return
	}

	if prevPID != 0 {
		if idx := indexOfPID(m.processes, prevPID); idx >= 0 {
			m.selectedPID = prevPID
			m.ensureSelectionVisible()
			return
		}
		// Selected PID exited: move to the row that landed at the prior index.
		if prevIdx < 0 {
			prevIdx = 0
		}
		if prevIdx >= len(m.processes) {
			prevIdx = len(m.processes) - 1
		}
		m.selectedPID = m.processes[prevIdx].PID
		m.ensureSelectionVisible()
		return
	}

	// First successful list (or cleared selection): select the top row.
	m.selectedPID = m.processes[0].PID
	m.offset = 0
}

func (m *Model) moveSelection(delta int) {
	if len(m.processes) == 0 {
		m.selectedPID = 0
		return
	}
	idx := m.selectedIndex()
	if idx < 0 {
		if delta >= 0 {
			idx = 0
		} else {
			idx = len(m.processes) - 1
		}
	} else {
		idx += delta
		if idx < 0 {
			idx = 0
		}
		if idx >= len(m.processes) {
			idx = len(m.processes) - 1
		}
	}
	m.selectedPID = m.processes[idx].PID
	m.ensureSelectionVisible()
}

func (m *Model) selectIndex(idx int) {
	if len(m.processes) == 0 {
		m.selectedPID = 0
		return
	}
	if idx < 0 {
		idx = 0
	}
	if idx >= len(m.processes) {
		idx = len(m.processes) - 1
	}
	m.selectedPID = m.processes[idx].PID
	m.ensureSelectionVisible()
}

func (m Model) selectedIndex() int {
	return indexOfPID(m.processes, m.selectedPID)
}

// ensureSelectionVisible adjusts offset so the selected row stays in the viewport.
func (m *Model) ensureSelectionVisible() {
	visible := m.visibleRows()
	if visible < 1 {
		visible = 1
	}
	n := len(m.processes)
	if n == 0 {
		m.offset = 0
		return
	}
	maxOffset := n - visible
	if maxOffset < 0 {
		maxOffset = 0
	}
	if m.offset > maxOffset {
		m.offset = maxOffset
	}
	if m.offset < 0 {
		m.offset = 0
	}

	idx := m.selectedIndex()
	if idx < 0 {
		return
	}
	if idx < m.offset {
		m.offset = idx
	} else if idx >= m.offset+visible {
		m.offset = idx - visible + 1
	}
	if m.offset > maxOffset {
		m.offset = maxOffset
	}
	if m.offset < 0 {
		m.offset = 0
	}
}

// visibleRows is how many process rows fit given current height and chrome.
func (m Model) visibleRows() int {
	height := m.height
	if height <= 0 {
		height = 24
	}
	chrome := m.chromeLines()
	available := height - chrome
	if available < 1 {
		return 1
	}
	return available
}

// chromeLines counts non-table-body lines consumed by the layout.
func (m Model) chromeLines() int {
	// title, status, [error], blank, table header, blank, footer (+ help lines)
	lines := 2 // header
	if m.lastError != nil {
		lines++
	}
	lines++       // blank before table
	lines++       // column header
	lines++ // blank before footer
	if m.showHelp {
		lines += 2 // expanded help occupies two footer lines
	} else {
		lines++ // compact footer
	}
	return lines
}

func indexOfPID(procs []model.Process, pid int32) int {
	if pid == 0 {
		return -1
	}
	for i, p := range procs {
		if p.PID == pid {
			return i
		}
	}
	return -1
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

// SelectedPID returns the selected process id (0 if none).
func (m Model) SelectedPID() int32 { return m.selectedPID }

// Offset returns the first visible row index (for tests).
func (m Model) Offset() int { return m.offset }

// ShowHelp reports whether expanded help is visible (for tests).
func (m Model) ShowHelp() bool { return m.showHelp }

// LastError returns the most recent fetch error (for tests).
func (m Model) LastError() error { return m.lastError }

// Connected reports whether a successful snapshot has been received (for tests).
func (m Model) Connected() bool { return m.connected }

// Width returns the last known terminal width (for tests).
func (m Model) Width() int { return m.width }

// Height returns the last known terminal height (for tests).
func (m Model) Height() int { return m.height }
