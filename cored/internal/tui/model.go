package tui

import (
	"sort"
	"strings"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
)

// SortField is a table sort column.
type SortField int

const (
	SortRSS SortField = iota
	SortCPU
	SortPID
	SortType
	SortUptime
	SortProject
	SortHeapUsed
	SortHeapCommitted
	SortHeapMax
)

func (f SortField) String() string {
	switch f {
	case SortRSS:
		return "RSS"
	case SortCPU:
		return "CPU"
	case SortPID:
		return "PID"
	case SortType:
		return "TYPE"
	case SortUptime:
		return "UPTIME"
	case SortProject:
		return "PROJECT"
	case SortHeapUsed:
		return "HEAP"
	case SortHeapCommitted:
		return "CMT"
	case SortHeapMax:
		return "XMX"
	default:
		return "?"
	}
}

// SortOrder is ascending or descending.
type SortOrder int

const (
	SortDesc SortOrder = iota
	SortAsc
)

func (o SortOrder) String() string {
	if o == SortAsc {
		return "asc"
	}
	return "desc"
}

// Config seeds the TUI model.
type Config struct {
	Client       *client.Client
	PollInterval time.Duration
	NoColor      bool
	Now          func() time.Time
	// Terminate stops a process; defaults to TerminateProcess.
	Terminate TerminateFunc
}

type detailPaneKind int

const (
	detailPaneAttributes detailPaneKind = iota
	detailPaneResources
	detailPaneBuilds
	detailPaneLog
)

// Model is Bubble Tea presentation state for the process monitor.
type Model struct {
	client       *client.Client
	pollInterval time.Duration
	noColor      bool
	now          func() time.Time
	terminate    TerminateFunc

	width       int
	height      int
	processes   []model.Process
	hierarchy   processHierarchy
	groupedView bool
	displayRows []displayRow
	selectedPID int64
	offset      int
	sortField   SortField
	sortOrder   SortOrder
	paused      bool
	loading     bool
	inFlight    bool
	connected   bool
	detailsOpen bool
	helpOpen    bool
	lastUpdated time.Time
	lastError   string
	sampledAt   int64

	detailProcess              *model.Process
	detailLog                  *logs.DaemonLog
	detailTail                 *logs.Tail
	detailHistory              []model.Process
	detailBuilds               client.BuildsPayload
	detailLoading              bool
	detailError                string
	detailScroll               int
	detailPane                 detailPaneKind
	detailPaneScroll           [4]int
	detailPaneScrolled         [4]bool
	detailPaneHorizontalScroll [4]int

	pendingKill *killRequest
	killing     bool
	notice      string
	noticeError bool
}

// killRequest captures the exact processes shown in the confirmation prompt so
// a refresh between prompt and confirmation cannot change what gets stopped.
type killRequest struct {
	all     bool
	targets []model.Process
}

func NewModel(cfg Config) Model {
	interval := cfg.PollInterval
	if interval <= 0 {
		interval = 2 * time.Second
	}
	now := cfg.Now
	if now == nil {
		now = time.Now
	}
	terminate := cfg.Terminate
	if terminate == nil {
		terminate = TerminateProcess
	}
	return Model{
		client:       cfg.Client,
		pollInterval: interval,
		noColor:      cfg.NoColor,
		now:          now,
		terminate:    terminate,
		width:        80,
		height:       24,
		sortField:    SortRSS,
		sortOrder:    SortDesc,
		loading:      true,
		hierarchy:    newProcessHierarchy(),
	}
}

// Init starts the first fetch and tick.
func (m Model) Init() tea.Cmd {
	return tea.Batch(m.fetchSnapshot(), m.scheduleTick())
}

// Update handles Bubble Tea messages.
func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.ensureSelectionVisible()
		return m, nil

	case tickMsg:
		cmds := []tea.Cmd{m.scheduleTick()}
		if !m.paused && !m.inFlight && !m.detailsOpen {
			m.inFlight = true
			m.loading = !m.connected
			cmds = append(cmds, m.fetchSnapshot())
		}
		return m, tea.Batch(cmds...)

	case snapshotLoadedMsg:
		m.inFlight = false
		m.loading = false
		m.connected = true
		m.lastError = ""
		m.applySnapshot(msg.snap)
		return m, nil

	case snapshotFailedMsg:
		m.inFlight = false
		m.loading = false
		m.lastError = msg.err.Error()
		return m, nil

	case detailsLoadedMsg:
		if !m.detailsOpen || m.selectedPID != msg.pid {
			return m, nil
		}
		if m.detailProcess == nil || m.detailProcess.StartTimeMs != msg.startMs {
			return m, nil
		}
		m.detailLoading = false
		m.detailLog = msg.log
		m.detailTail = msg.tail
		m.detailHistory = msg.history
		m.detailBuilds = msg.builds
		if msg.err != nil {
			m.detailError = msg.err.Error()
		} else if msg.buildsErr != nil {
			m.detailError = "build history unavailable: " + msg.buildsErr.Error()
		} else {
			m.detailError = ""
		}
		return m, nil

	case killFinishedMsg:
		m.killing = false
		m.notice, m.noticeError = killNotice(msg)
		if !m.inFlight {
			m.inFlight = true
			return m, m.fetchSnapshot()
		}
		return m, nil

	case tea.KeyPressMsg:
		return m.handleKey(msg)
	}
	return m, nil
}

func (m Model) handleKey(msg tea.KeyPressMsg) (tea.Model, tea.Cmd) {
	key := msg.String()
	if m.detailsOpen {
		switch key {
		case "esc", "q":
			if key == "q" {
				return m, tea.Quit
			}
			m.detailsOpen = false
			m.detailProcess = nil
			m.detailLog = nil
			m.detailTail = nil
			m.detailHistory = nil
			m.detailBuilds = client.BuildsPayload{}
			m.detailError = ""
			m.detailLoading = false
			m.detailScroll = 0
			m.detailPane = detailPaneAttributes
			m.detailPaneScroll = [4]int{}
			m.detailPaneScrolled = [4]bool{}
			return m, nil
		case "ctrl+c":
			return m, tea.Quit
		case "up", "k":
			m.setDetailScroll(m.detailScroll - 1)
		case "down", "j":
			m.setDetailScroll(m.detailScroll + 1)
		case "pgup", "ctrl+u":
			m.setDetailScroll(m.detailScroll - max(1, m.height-8))
		case "pgdown", "ctrl+d":
			m.setDetailScroll(m.detailScroll + max(1, m.height-8))
		case "home", "g":
			m.setDetailScroll(0)
		case "end", "G":
			m.setDetailScroll(int(^uint(0) >> 1))
		case "shift+left", "ctrl+left":
			m.setDetailHorizontalScroll(m.detailPaneHorizontalScroll[m.detailPane] - 8)
		case "shift+right", "ctrl+right":
			m.setDetailHorizontalScroll(m.detailPaneHorizontalScroll[m.detailPane] + 8)
		case "tab", "right", "l":
			m.selectDetailPane(1)
		case "shift+tab", "left", "h":
			m.selectDetailPane(-1)
		case "r":
			if m.detailProcess != nil && !m.detailLoading {
				m.detailLoading = true
				m.detailError = ""
				return m, m.fetchDetails(int64(m.detailProcess.PID), m.detailProcess.StartTimeMs)
			}
		}
		return m, nil
	}

	if m.pendingKill != nil {
		req := *m.pendingKill
		m.pendingKill = nil
		switch key {
		case "ctrl+c":
			return m, tea.Quit
		case "y", "Y":
			m.killing = true
			return m, m.killProcesses(req)
		}
		m.notice, m.noticeError = "Kill cancelled.", false
		return m, nil
	}
	m.notice = ""

	switch key {
	case "q", "ctrl+c":
		return m, tea.Quit
	case "x":
		if idx := m.selectedIndex(); idx >= 0 && !m.killing {
			m.pendingKill = &killRequest{targets: []model.Process{m.displayRows[idx].target}}
		}
	case "X":
		if len(m.processes) > 0 && !m.killing {
			m.pendingKill = &killRequest{all: true, targets: append([]model.Process(nil), m.processes...)}
		}
	case "up", "k":
		m.moveSelection(-1)
	case "down", "j":
		m.moveSelection(1)
	case "home", "g":
		m.selectIndex(0)
	case "end", "G":
		if len(m.displayRows) > 0 {
			m.selectIndex(len(m.displayRows) - 1)
		}
	case "s":
		m.cycleSortField()
		m.sortProcesses()
		m.ensureSelectionVisible()
	case "S":
		if m.sortOrder == SortAsc {
			m.sortOrder = SortDesc
		} else {
			m.sortOrder = SortAsc
		}
		m.sortProcesses()
		m.ensureSelectionVisible()
	case "space":
		m.paused = !m.paused
	case "v":
		m.groupedView = !m.groupedView
		m.refreshDisplayRows()
		m.ensureSelectionVisible()
	case "left", "h":
		m.collapseSelectedGroup()
	case "right", "l":
		m.expandSelectedGroup()
	case "E":
		m.setAllGroups(true)
	case "C":
		m.setAllGroups(false)
	case "r":
		if !m.inFlight {
			m.inFlight = true
			return m, m.fetchSnapshot()
		}
	case "enter":
		if idx := m.selectedIndex(); idx >= 0 {
			p := m.displayRows[idx].target
			m.detailsOpen = true
			cp := p
			m.detailProcess = &cp
			m.detailLoading = true
			m.detailLog = nil
			m.detailTail = nil
			m.detailHistory = nil
			m.detailBuilds = client.BuildsPayload{}
			m.detailError = ""
			m.detailScroll = 0
			m.detailPane = detailPaneAttributes
			m.detailPaneScroll = [4]int{}
			m.detailPaneScrolled = [4]bool{}
			m.detailPaneHorizontalScroll = [4]int{}
			return m, m.fetchDetails(int64(p.PID), p.StartTimeMs)
		}
	case "?":
		m.helpOpen = !m.helpOpen
	}
	return m, nil
}

func (m *Model) setDetailScroll(offset int) {
	m.detailScroll = max(0, offset)
	m.detailPaneScroll[m.detailPane] = m.detailScroll
	m.detailPaneScrolled[m.detailPane] = true
}

func (m *Model) setDetailHorizontalScroll(offset int) {
	if m.detailPane != detailPaneLog {
		return
	}
	m.detailPaneHorizontalScroll[m.detailPane] = max(0, offset)
}

func (m Model) detailPaneOffset(pane detailPaneKind) int {
	if m.detailPaneScrolled[pane] {
		return m.detailPaneScroll[pane]
	}
	// Keep direct model construction in tests and integrations compatible with
	// the original single-viewport state.
	return m.detailScroll
}

func (m Model) detailPaneCount() int {
	if m.width >= 120 && m.detailProcess != nil && m.detailProcess.Type == "GRADLE_DAEMON" {
		return 4
	}
	if m.width >= 120 {
		return 2
	}
	return 1
}

func (m *Model) selectDetailPane(delta int) {
	count := m.detailPaneCount()
	if count <= 1 {
		return
	}
	next := int(m.detailPane) + delta
	if next < 0 {
		next = count - 1
	}
	if next >= count {
		next = 0
	}
	m.detailPane = detailPaneKind(next)
	if m.detailPaneScrolled[m.detailPane] {
		m.detailScroll = m.detailPaneScroll[m.detailPane]
	} else {
		m.detailScroll = 0
	}
}

func (m *Model) applySnapshot(snap model.Snapshot) {
	m.processes = append([]model.Process(nil), snap.Processes...)
	m.sampledAt = snap.SampledAtMs
	m.lastUpdated = m.now()
	m.sortProcesses()
	// Keep ancestry history warm even while the user is in flat mode. A later
	// switch to grouped mode must not lose relationships observed in between.
	m.hierarchy.observe(m.processes)
	m.refreshDisplayRows()
	if len(m.processes) == 0 {
		m.selectedPID = 0
		m.offset = 0
		return
	}
	if m.selectedPID == 0 {
		m.selectedPID = int64(m.processes[0].PID)
	} else if m.selectedIndex() < 0 {
		m.selectedPID = int64(m.processes[0].PID)
	}
	m.ensureSelectionVisible()
}

func (m *Model) refreshDisplayRows() {
	if m.groupedView {
		m.displayRows = m.hierarchy.rows(m.processes, true)
	} else {
		m.displayRows = make([]displayRow, 0, len(m.processes))
		for _, p := range m.processes {
			m.displayRows = append(m.displayRows, displayRow{process: p, target: p})
		}
	}
}

func (m *Model) sortProcesses() {
	field := m.sortField
	asc := m.sortOrder == SortAsc
	sort.SliceStable(m.processes, func(i, j int) bool {
		a, b := m.processes[i], m.processes[j]
		cmp := compareProcesses(a, b, field)
		if cmp == 0 {
			return a.PID < b.PID
		}
		if asc {
			return cmp < 0
		}
		return cmp > 0
	})
}

func compareProcesses(a, b model.Process, field SortField) int {
	switch field {
	case SortRSS:
		return cmpInt64(a.RSSMemoryMB, b.RSSMemoryMB)
	case SortCPU:
		return cmpFloatPtr(a.CPUPercent, b.CPUPercent)
	case SortPID:
		return cmpInt32(a.PID, b.PID)
	case SortType:
		return strings.Compare(a.Type, b.Type)
	case SortUptime:
		// Ascending uptime ≡ descending start time (newer processes first when asc).
		return cmpInt64(b.StartTimeMs, a.StartTimeMs)
	case SortProject:
		return strings.Compare(render.ProjectName(a), render.ProjectName(b))
	case SortHeapUsed:
		return cmpOptionalInt64(a.HeapUsedMB, b.HeapUsedMB)
	case SortHeapCommitted:
		return cmpOptionalInt64(a.HeapCommittedMB, b.HeapCommittedMB)
	case SortHeapMax:
		return cmpOptionalInt64(a.MaxHeapMB, b.MaxHeapMB)
	default:
		return 0
	}
}

func cmpInt64(a, b int64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}

func cmpInt32(a, b int32) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}

func cmpFloatPtr(a, b *float64) int {
	av, bv := -1.0, -1.0
	if a != nil {
		av = *a
	}
	if b != nil {
		bv = *b
	}
	switch {
	case av < bv:
		return -1
	case av > bv:
		return 1
	default:
		return 0
	}
}

func cmpOptionalInt64(a, b *int64) int {
	if a == nil && b == nil {
		return 0
	}
	if a == nil {
		return -1
	}
	if b == nil {
		return 1
	}
	return cmpInt64(*a, *b)
}

func (m *Model) cycleSortField() {
	m.sortField = (m.sortField + 1) % 9
}

func (m *Model) selectedIndex() int {
	for i, row := range m.displayRows {
		if int64(row.process.PID) == m.selectedPID {
			return i
		}
	}
	return -1
}

func (m *Model) moveSelection(delta int) {
	if len(m.displayRows) == 0 {
		return
	}
	idx := m.selectedIndex()
	if idx < 0 {
		idx = 0
	} else {
		idx += delta
	}
	if idx < 0 {
		idx = 0
	}
	if idx >= len(m.displayRows) {
		idx = len(m.displayRows) - 1
	}
	m.selectIndex(idx)
}

func (m *Model) selectIndex(idx int) {
	if idx < 0 || idx >= len(m.displayRows) {
		return
	}
	m.selectedPID = int64(m.displayRows[idx].process.PID)
	m.ensureSelectionVisible()
}

func (m *Model) collapseSelectedGroup() {
	idx := m.selectedIndex()
	if idx < 0 || idx >= len(m.displayRows) || !m.displayRows[idx].groupRoot {
		return
	}
	m.hierarchy.setExpanded(identity(m.displayRows[idx].process), false)
	m.refreshDisplayRows()
	m.ensureSelectionVisible()
}

func (m *Model) expandSelectedGroup() {
	idx := m.selectedIndex()
	if idx < 0 || idx >= len(m.displayRows) || !m.displayRows[idx].groupRoot {
		return
	}
	m.hierarchy.setExpanded(identity(m.displayRows[idx].process), true)
	m.refreshDisplayRows()
	m.ensureSelectionVisible()
}

func (m *Model) setAllGroups(expanded bool) {
	for _, p := range m.processes {
		if p.Type == "GRADLE_DAEMON" {
			m.hierarchy.setExpanded(identity(p), expanded)
		}
	}
	m.refreshDisplayRows()
	m.ensureSelectionVisible()
}

func (m *Model) tableRows() int {
	// title, summary, blank, column header, blank, footer (or help block)
	reserved := 6
	if m.helpOpen && m.pendingKill == nil {
		reserved += len(helpLines) - 1
	}
	if m.lastError != "" {
		reserved++
	}
	if m.notice != "" || m.killing {
		reserved++
	}
	rows := m.height - reserved
	if rows < 1 {
		rows = 1
	}
	return rows
}

func (m *Model) ensureSelectionVisible() {
	idx := m.selectedIndex()
	if idx < 0 {
		m.offset = 0
		return
	}
	visible := m.tableRows()
	if idx < m.offset {
		m.offset = idx
	}
	if idx >= m.offset+visible {
		m.offset = idx - visible + 1
	}
	if m.offset < 0 {
		m.offset = 0
	}
}

// Run starts the Bubble Tea program.
func Run(cfg Config) error {
	m := NewModel(cfg)
	p := tea.NewProgram(m)
	_, err := p.Run()
	return err
}

// View renders the monitor.
