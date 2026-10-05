package tui

import (
	"context"
	"fmt"
	"math"
	"sort"
	"strings"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/clipperhouse/displaywidth"
	"github.com/rivo/uniseg"

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

	detailProcess *model.Process
	detailLog     *logs.DaemonLog
	detailTail    *logs.Tail
	detailHistory []model.Process
	detailBuilds  client.BuildsPayload
	detailLoading bool
	detailError   string

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

type tickMsg time.Time

type snapshotLoadedMsg struct {
	snap model.Snapshot
}

type snapshotFailedMsg struct {
	err error
}

type detailsLoadedMsg struct {
	pid       int64
	startMs   int64
	log       *logs.DaemonLog
	tail      *logs.Tail
	history   []model.Process
	builds    client.BuildsPayload
	buildsErr error
	err       error
}

type killFailure struct {
	pid int32
	err error
}

type killFinishedMsg struct {
	all       bool
	requested []model.Process
	failures  []killFailure
}

// NewModel constructs the interactive monitor model.
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
	}
}

// Init starts the first fetch and tick.
func (m Model) Init() tea.Cmd {
	return tea.Batch(m.fetchSnapshot(), m.scheduleTick())
}

func (m Model) scheduleTick() tea.Cmd {
	return tea.Tick(m.pollInterval, func(t time.Time) tea.Msg {
		return tickMsg(t)
	})
}

func (m Model) fetchSnapshot() tea.Cmd {
	if m.client == nil {
		return func() tea.Msg {
			return snapshotFailedMsg{err: fmt.Errorf("no client")}
		}
	}
	c := m.client
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		snap, err := c.Processes(ctx)
		if err != nil {
			return snapshotFailedMsg{err: err}
		}
		return snapshotLoadedMsg{snap: snap}
	}
}

func (m Model) fetchDetails(pid, startMs int64) tea.Cmd {
	if m.client == nil {
		return nil
	}
	c := m.client
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		var logMeta *logs.DaemonLog
		var history model.History
		var builds client.BuildsPayload
		list, err := c.DaemonLogs(ctx)
		if err == nil {
			for i := range list {
				if list[i].PID == pid {
					logMeta = &list[i]
					break
				}
			}
		}
		tail, tailErr := c.DaemonLogTail(ctx, pid)
		history, _ = c.History(ctx, time.Now().Add(-30*time.Minute).UnixMilli(), 500)
		builds, buildsErr := c.BuildsForDaemon(ctx, pid, startMs, 20)
		msg := detailsLoadedMsg{pid: pid, startMs: startMs, log: logMeta, history: history.Processes, builds: builds, buildsErr: buildsErr}
		if tailErr != nil {
			msg.err = tailErr
			return msg
		}
		msg.tail = &tail
		return msg
	}
}

func (m Model) killProcesses(req killRequest) tea.Cmd {
	terminate := m.terminate
	return func() tea.Msg {
		msg := killFinishedMsg{all: req.all, requested: req.targets}
		for _, p := range req.targets {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			err := terminate(ctx, p.PID, p.StartTimeMs)
			cancel()
			if err != nil {
				msg.failures = append(msg.failures, killFailure{pid: p.PID, err: err})
			}
		}
		return msg
	}
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
			return m, nil
		case "ctrl+c":
			return m, tea.Quit
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
			m.pendingKill = &killRequest{targets: []model.Process{m.processes[idx]}}
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
		if len(m.processes) > 0 {
			m.selectIndex(len(m.processes) - 1)
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
	case "r":
		if !m.inFlight {
			m.inFlight = true
			return m, m.fetchSnapshot()
		}
	case "enter":
		if idx := m.selectedIndex(); idx >= 0 {
			p := m.processes[idx]
			m.detailsOpen = true
			cp := p
			m.detailProcess = &cp
			m.detailLoading = true
			m.detailLog = nil
			m.detailTail = nil
			m.detailHistory = nil
			m.detailBuilds = client.BuildsPayload{}
			m.detailError = ""
			return m, m.fetchDetails(int64(p.PID), p.StartTimeMs)
		}
	case "?":
		m.helpOpen = !m.helpOpen
	}
	return m, nil
}

func (m *Model) applySnapshot(snap model.Snapshot) {
	m.processes = append([]model.Process(nil), snap.Processes...)
	m.sampledAt = snap.SampledAtMs
	m.lastUpdated = m.now()
	m.sortProcesses()
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
	for i, p := range m.processes {
		if int64(p.PID) == m.selectedPID {
			return i
		}
	}
	return -1
}

func (m *Model) moveSelection(delta int) {
	if len(m.processes) == 0 {
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
	if idx >= len(m.processes) {
		idx = len(m.processes) - 1
	}
	m.selectIndex(idx)
}

func (m *Model) selectIndex(idx int) {
	if idx < 0 || idx >= len(m.processes) {
		return
	}
	m.selectedPID = int64(m.processes[idx].PID)
	m.ensureSelectionVisible()
}

func (m *Model) tableRows() int {
	// header(2) + blank + col header(1) + footer(2) + optional help/error
	reserved := 8
	if m.helpOpen {
		reserved += 6
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

// View renders the monitor.
func (m Model) View() tea.View {
	var body string
	if m.width < 40 || m.height < 8 {
		body = "Terminal too small.\nPlease enlarge the window."
	} else if m.detailsOpen {
		body = m.renderDetails()
	} else {
		body = m.renderTable()
	}
	v := tea.NewView(body)
	v.AltScreen = true
	return v
}

func (m Model) renderTable() string {
	var b strings.Builder
	now := m.sampledAt
	if now == 0 {
		now = m.now().UnixMilli()
	}

	updated := "—"
	if !m.lastUpdated.IsZero() {
		updated = m.lastUpdated.Format("15:04:05")
	}
	status := "Core connected"
	if !m.connected {
		if m.loading {
			status = "Connecting…"
		} else {
			status = "Core disconnected"
		}
	}
	if m.paused {
		status = "PAUSED"
	}

	totalRSS := int64(0)
	for _, p := range m.processes {
		totalRSS += p.RSSMemoryMB
	}

	right := padLeft("Updated "+updated, min(20, m.width))
	leftWidth := max(0, m.width-displaywidth.String(right))
	left := padRight("DAEMONITOR", leftWidth)
	if !m.noColor {
		pad := max(0, leftWidth-displaywidth.String("DAEMONITOR"))
		left = titleStyle(m.noColor).Render("DAEMONITOR") + strings.Repeat(" ", pad)
	}
	fmt.Fprintf(&b, "%s%s\n", left, right)
	line2 := fmt.Sprintf("%d processes   %s RSS   %s   Refresh %s",
		len(m.processes), render.RSSText(totalRSS), status, m.pollInterval)
	if !m.noColor {
		line2 = fmt.Sprintf("%d processes   %s   RSS   %s   Refresh %s", len(m.processes), rssStyle(model.Process{RSSMemoryMB: totalRSS}, m.noColor).Render(render.RSSText(totalRSS)), statusStyle(status, m.noColor).Render(status), m.pollInterval)
	}
	if m.sortField != SortRSS || m.sortOrder != SortDesc {
		line2 += fmt.Sprintf("   Sort %s %s", m.sortField, m.sortOrder)
	} else {
		line2 += fmt.Sprintf("   Sort %s %s", m.sortField, m.sortOrder)
	}
	b.WriteString(truncateWidth(line2, m.width) + "\n")
	if m.lastError != "" {
		errLine := "Last refresh failed: " + m.lastError
		if !m.noColor {
			errLine = errorStyle(m.noColor).Render(errLine)
		}
		b.WriteString(truncateWidth(errLine, m.width) + "\n")
	}
	b.WriteString("\n")

	if len(m.processes) == 0 && m.connected {
		b.WriteString("No Gradle-related processes are currently running.\nWaiting for activity…\n")
	} else if len(m.processes) == 0 && !m.connected {
		b.WriteString("Waiting for daemonitor-cored…\n")
	} else {
		cols := columnsForWidth(m.width)
		header := headerLine(cols, m.sortField)
		if !m.noColor {
			header = headerStyle(m.noColor).Render(header)
		}
		b.WriteString(truncateWidth(header, m.width) + "\n")
		visible := m.tableRows()
		end := m.offset + visible
		if end > len(m.processes) {
			end = len(m.processes)
		}
		for i := m.offset; i < end; i++ {
			p := m.processes[i]
			prefix := "  "
			selected := int64(p.PID) == m.selectedPID
			if selected {
				prefix = "> "
			}
			row := prefix + formatRowStyled(p, cols, now, m.noColor, selected)
			row = truncateWidth(row, m.width)
			if selected && !m.noColor {
				row = selectedStyle(m.noColor).Render(row)
			}
			b.WriteString(row + "\n")
		}
	}

	b.WriteString("\n")
	if status := m.killStatusLine(); status != "" {
		b.WriteString(status + "\n")
	}
	footer := "↑/↓ or j/k select   s sort   space pause   r refresh   enter details   x kill   X kill all   ? help   q quit"
	if m.helpOpen {
		footer = "Keys: q quit · arrows/jk move · g/G home/end · s/S sort · space pause · r refresh · enter details · x kill selected · X kill all · esc close details"
	}
	footer = truncateWidth(footer, m.width)
	if m.pendingKill != nil {
		footer = truncateWidth(killPrompt(*m.pendingKill), m.width)
		if !m.noColor {
			footer = errorStyle(m.noColor).Bold(true).Render(footer)
		}
	}
	b.WriteString(footer)
	return b.String()
}

func (m Model) killStatusLine() string {
	if m.killing {
		return truncateWidth("Sending termination signal…", m.width)
	}
	if m.notice == "" {
		return ""
	}
	line := truncateWidth(m.notice, m.width)
	if m.noticeError && !m.noColor {
		return errorStyle(m.noColor).Render(line)
	}
	return line
}

func killPrompt(req killRequest) string {
	const suffix = "?  y confirm · any other key cancels"
	if req.all {
		return fmt.Sprintf("Kill all %d recorded processes%s", len(req.targets), suffix)
	}
	p := req.targets[0]
	return fmt.Sprintf("Kill %s pid %d (%s)%s", render.TypeDisplay(p.Type), p.PID, render.ProjectName(p), suffix)
}

func killNotice(msg killFinishedMsg) (string, bool) {
	total := len(msg.requested)
	failed := len(msg.failures)
	if failed == 0 {
		if msg.all {
			return fmt.Sprintf("Sent termination signal to %d processes.", total), false
		}
		p := msg.requested[0]
		return fmt.Sprintf("Sent termination signal to %s pid %d.", render.TypeDisplay(p.Type), p.PID), false
	}
	first := msg.failures[0]
	if !msg.all {
		return fmt.Sprintf("Could not kill pid %d: %v", first.pid, first.err), true
	}
	text := fmt.Sprintf("Sent termination signal to %d of %d processes; pid %d: %v", total-failed, total, first.pid, first.err)
	if failed > 1 {
		text += fmt.Sprintf(" (+%d more failed)", failed-1)
	}
	return text, true
}

func (m Model) renderDetails() string {
	var b strings.Builder
	p := m.detailProcess
	if p == nil {
		return "No process selected.\nPress esc to return."
	}
	now := m.sampledAt
	if now == 0 {
		now = m.now().UnixMilli()
	}
	heading := fmt.Sprintf("PROCESS DETAILS  pid=%d", p.PID)
	if !m.noColor {
		heading = detailHeadingStyle(m.noColor).Render(heading)
	}
	fmt.Fprintf(&b, "%s\n\n", heading)
	writeDetailField(&b, m.width, "Type", render.TypeDisplay(p.Type))
	writeDetailField(&b, m.width, "PID", fmt.Sprintf("%d", p.PID))
	writeDetailField(&b, m.width, "Name", na(p.Name))
	writeDetailField(&b, m.width, "Project", render.ProjectName(*p))
	if p.WorkingDirectory != nil {
		writeDetailField(&b, m.width, "Working dir", *p.WorkingDirectory)
	} else {
		writeDetailField(&b, m.width, "Working dir", "n/a")
	}
	writeDetailField(&b, m.width, "RSS", render.RSSText(p.RSSMemoryMB))
	writeDetailField(&b, m.width, "Virtual memory", optionalInt64Text(p.VirtualMemoryMB, "MB"))
	writeDetailField(&b, m.width, "Swap memory", optionalInt64Text(p.SwapMemoryMB, "MB"))
	writeDetailField(&b, m.width, "Threads", optionalInt64Text(p.ThreadCount, ""))
	writeDetailField(&b, m.width, "Disk read", optionalInt64Text(p.ReadBytes, "bytes"))
	writeDetailField(&b, m.width, "Disk write", optionalInt64Text(p.WriteBytes, "bytes"))
	writeDetailField(&b, m.width, "Read operations", optionalInt64Text(p.ReadOperations, ""))
	writeDetailField(&b, m.width, "Write operations", optionalInt64Text(p.WriteOperations, ""))
	writeDetailField(&b, m.width, "Minor page faults", optionalInt64Text(p.MinorPageFaults, ""))
	writeDetailField(&b, m.width, "Major page faults", optionalInt64Text(p.MajorPageFaults, ""))
	writeDetailField(&b, m.width, "Voluntary switches", optionalInt64Text(p.VoluntaryContextSwitches, ""))
	writeDetailField(&b, m.width, "Involuntary switches", optionalInt64Text(p.InvoluntaryContextSwitches, ""))
	writeDetailField(&b, m.width, "Open file descriptors", optionalInt64Text(p.OpenFileDescriptors, ""))
	writeDetailField(&b, m.width, "CPU", render.CPUText(p.CPUPercent))
	writeDetailField(&b, m.width, "Xmx", render.HeapLimitText(p.MaxHeapMB))
	writeDetailField(&b, m.width, "Xms", optionalInt64Text(p.MinHeapMB, "MB"))
	writeDetailField(&b, m.width, "GC", render.GCText(p.GC))
	writeDetailField(&b, m.width, "Heap used", render.HeapText(p.HeapUsedMB))
	writeDetailField(&b, m.width, "Heap committed", render.HeapText(p.HeapCommittedMB))
	writeDetailField(&b, m.width, "Heap max", render.HeapText(p.HeapMaxMB))
	writeDetailField(&b, m.width, "Heap %", render.HeapPercentText(p.HeapUsedMB, p.HeapMaxMB))
	writeDetailField(&b, m.width, "Heap sampled", optionalInt64Text(p.HeapSampledAtMs, ""))
	writeDetailField(&b, m.width, "Heap available", fmt.Sprint(p.HeapAvailable))
	writeDetailField(&b, m.width, "Metaspace used", optionalInt64Text(p.MetaspaceUsedMB, "MB"))
	writeDetailField(&b, m.width, "Metaspace committed", optionalInt64Text(p.MetaspaceCommittedMB, "MB"))
	writeDetailField(&b, m.width, "Young GC count", optionalInt64Text(p.YoungGCCount, ""))
	writeDetailField(&b, m.width, "Young GC time", optionalInt64Text(p.YoungGCTimeMs, "ms"))
	writeDetailField(&b, m.width, "Old GC count", optionalInt64Text(p.OldGCCount, ""))
	writeDetailField(&b, m.width, "Old GC time", optionalInt64Text(p.OldGCTimeMs, "ms"))
	writeDetailField(&b, m.width, "Java version", optionalStringText(p.JavaVersion))
	writeDetailField(&b, m.width, "Java vendor", optionalStringText(p.JavaVendor))
	writeDetailField(&b, m.width, "Active processors", optionalInt64Text(p.ActiveProcessorCount, ""))
	writeDetailField(&b, m.width, "Status", na(p.Status))
	writeDetailField(&b, m.width, "Automated", fmt.Sprint(p.Automated))
	writeDetailField(&b, m.width, "Start", formatStart(p.StartTimeMs))
	writeDetailField(&b, m.width, "Uptime", render.Uptime(p.StartTimeMs, now))
	if m.detailLog != nil {
		writeDetailField(&b, m.width, "Gradle", na(m.detailLog.GradleVersion))
		writeDetailField(&b, m.width, "Daemon log", na(m.detailLog.Path))
	} else if m.detailLoading {
		writeDetailField(&b, m.width, "Gradle", "…")
		writeDetailField(&b, m.width, "Daemon log", "…")
	} else {
		writeDetailField(&b, m.width, "Gradle", "n/a")
		writeDetailField(&b, m.width, "Daemon log", "n/a")
	}
	b.WriteString("\nRecent activity:\n")
	trend := rssTrend(m.detailHistory, int32(p.PID))
	if trend == "" {
		b.WriteString("  Trend: n/a\n")
	} else {
		b.WriteString("  RSS trend: " + trend + "\n")
	}
	b.WriteString("\nRecent builds:\n")
	if m.detailLoading {
		b.WriteString("  Loading…\n")
	} else if m.detailError != "" && len(m.detailBuilds.Builds) == 0 {
		b.WriteString("  " + truncateWidth("Unavailable: "+m.detailError, m.width-2) + "\n")
	} else if len(m.detailBuilds.Builds) == 0 {
		b.WriteString("  No build history for this daemon.\n")
	} else {
		for i, build := range m.detailBuilds.Builds {
			if i == 3 {
				break
			}
			summaryWidth := m.width - 2
			if m.width >= 80 {
				summaryWidth = m.width
			}
			b.WriteString("  " + truncateWidth(buildSummary(build), summaryWidth) + "\n")
		}
	}
	if m.detailTail != nil && len(m.detailTail.Events) > 0 {
		event := m.detailTail.Events[len(m.detailTail.Events)-1]
		b.WriteString("  Last log event: " + string(event.Kind) + "\n")
	}
	b.WriteString("\nRecent log lines:\n")
	if m.detailLoading {
		b.WriteString("  Loading…\n")
	} else if m.detailError != "" && m.detailTail == nil {
		b.WriteString("  n/a (" + m.detailError + ")\n")
	} else if m.detailTail == nil || len(m.detailTail.Lines) == 0 {
		b.WriteString("  n/a\n")
	} else {
		lines := m.detailTail.Lines
		maxLines := m.height - 20
		if maxLines < 3 {
			maxLines = 3
		}
		if len(lines) > maxLines {
			lines = lines[len(lines)-maxLines:]
		}
		for _, line := range lines {
			b.WriteString("  " + truncateWidth(line, m.width-2) + "\n")
		}
	}
	b.WriteString("\nesc back   q quit")
	return b.String()
}

func buildSummary(build client.BuildRecord) string {
	duration := "-"
	if build.DurationSeconds != nil {
		duration = fmt.Sprintf("%.1fs", math.Round(*build.DurationSeconds*10)/10)
	}
	return fmt.Sprintf("%s project=%s duration=%s start=%s source=%s agent=%s",
		na(build.FinalStatus), na(build.ProjectPath), duration, formatStart(build.StartTimeMs),
		na(build.InferredSource), na(build.Agent))
}

func rssTrend(samples []model.Process, pid int32) string {
	values := make([]int64, 0, len(samples))
	for _, sample := range samples {
		if sample.PID == pid {
			values = append(values, sample.RSSMemoryMB)
		}
	}
	if len(values) < 2 {
		return ""
	}
	if len(values) > 24 {
		values = values[len(values)-24:]
	}
	minValue, maxValue := values[0], values[0]
	for _, value := range values[1:] {
		if value < minValue {
			minValue = value
		}
		if value > maxValue {
			maxValue = value
		}
	}
	levels := []rune("▁▂▃▄▅▆▇█")
	var b strings.Builder
	for _, value := range values {
		index := 0
		if maxValue > minValue {
			index = int((value - minValue) * int64(len(levels)-1) / (maxValue - minValue))
		}
		b.WriteRune(levels[index])
	}
	return b.String() + fmt.Sprintf("  %s–%s", render.RSSText(minValue), render.RSSText(maxValue))
}

func writeField(b *strings.Builder, label, value string) {
	fmt.Fprintf(b, "  %-14s %s\n", label+":", value)
}

func writeDetailField(b *strings.Builder, width int, label, value string) {
	writeField(b, label, truncateWidth(value, max(1, width-18)))
}

func na(s string) string {
	if strings.TrimSpace(s) == "" {
		return "n/a"
	}
	return s
}

func optionalInt64Text(v *int64, unit string) string {
	if v == nil {
		return "n/a"
	}
	if unit == "" {
		return fmt.Sprint(*v)
	}
	return fmt.Sprintf("%d %s", *v, unit)
}

func optionalStringText(v *string) string {
	if v == nil || strings.TrimSpace(*v) == "" {
		return "n/a"
	}
	return *v
}

func formatStart(ms int64) string {
	if ms <= 0 {
		return "n/a"
	}
	return time.UnixMilli(ms).Format(time.RFC3339)
}

type columnID int

const (
	colType columnID = iota
	colGC
	colPID
	colRSS
	colThreads
	colHeapPercent
	colMetaspace
	colHeapUsed
	colHeapCmt
	colXmx
	colCPU
	colUptime
	colProject
)

type columnSet struct {
	ids []columnID
}

func columnsForWidth(width int) columnSet {
	switch {
	case width >= 110:
		return columnSet{ids: []columnID{colType, colGC, colPID, colRSS, colThreads, colHeapPercent, colMetaspace, colHeapUsed, colHeapCmt, colXmx, colCPU, colUptime, colProject}}
	case width >= 80:
		return columnSet{ids: []columnID{colType, colGC, colPID, colRSS, colXmx, colCPU, colUptime, colProject}}
	default:
		return columnSet{ids: []columnID{colType, colGC, colPID, colRSS, colCPU, colProject}}
	}
}

func headerLine(cols columnSet, sort SortField) string {
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		label := columnLabel(id)
		if sortMatches(id, sort) {
			label = label + "*"
		}
		parts = append(parts, padColumn(label, id))
	}
	return strings.Join(parts, " ")
}

func sortMatches(id columnID, sort SortField) bool {
	switch sort {
	case SortRSS:
		return id == colRSS
	case SortCPU:
		return id == colCPU
	case SortPID:
		return id == colPID
	case SortType:
		return id == colType
	case SortUptime:
		return id == colUptime
	case SortProject:
		return id == colProject
	case SortHeapUsed:
		return id == colHeapUsed
	case SortHeapCommitted:
		return id == colHeapCmt
	case SortHeapMax:
		return id == colXmx
	}
	return false
}

func columnLabel(id columnID) string {
	switch id {
	case colType:
		return "TYPE"
	case colGC:
		return "GC"
	case colPID:
		return "PID"
	case colRSS:
		return "RSS"
	case colThreads:
		return "THR"
	case colHeapPercent:
		return "HEAP%"
	case colMetaspace:
		return "META"
	case colHeapUsed:
		return "HEAP"
	case colHeapCmt:
		return "CMT"
	case colXmx:
		return "XMX"
	case colCPU:
		return "CPU"
	case colUptime:
		return "UPTIME"
	case colProject:
		return "PROJECT"
	default:
		return ""
	}
}

func columnWidth(id columnID) int {
	switch id {
	case colType:
		return 16
	case colGC:
		return 8
	case colPID:
		return 7
	case colRSS:
		return 8
	case colThreads:
		return 6
	case colHeapPercent:
		return 7
	case colMetaspace:
		return 8
	case colHeapUsed, colHeapCmt, colXmx:
		return 8
	case colCPU:
		return 7
	case colUptime:
		return 8
	case colProject:
		return 24
	default:
		return 8
	}
}

func padColumn(s string, id columnID) string {
	w := columnWidth(id)
	if id == colPID || id == colRSS || id == colThreads || id == colHeapPercent || id == colMetaspace || id == colCPU || id == colXmx || id == colHeapUsed || id == colHeapCmt || id == colUptime {
		return padLeft(truncateWidth(s, w), w)
	}
	return padRight(truncateWidth(s, w), w)
}

func formatRow(p model.Process, cols columnSet, nowMs int64) string {
	return formatRowStyled(p, cols, nowMs, true, false)
}

func formatRowStyled(p model.Process, cols columnSet, nowMs int64, noColor, selected bool) string {
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		var cell string
		switch id {
		case colType:
			cell = render.TypeDisplay(p.Type)
		case colGC:
			cell = render.GCText(p.GC)
		case colPID:
			cell = fmt.Sprintf("%d", p.PID)
		case colRSS:
			cell = render.RSSText(p.RSSMemoryMB)
		case colThreads:
			cell = optionalInt64Text(p.ThreadCount, "")
		case colHeapPercent:
			cell = render.HeapPercentText(p.HeapUsedMB, p.HeapMaxMB)
		case colMetaspace:
			cell = render.HeapText(p.MetaspaceUsedMB)
		case colHeapUsed:
			cell = render.HeapText(p.HeapUsedMB)
		case colHeapCmt:
			cell = render.HeapText(p.HeapCommittedMB)
		case colXmx:
			cell = render.HeapLimitText(p.MaxHeapMB)
		case colCPU:
			cell = render.CPUText(p.CPUPercent)
		case colUptime:
			cell = render.Uptime(p.StartTimeMs, nowMs)
		case colProject:
			cell = render.ProjectName(p)
		}
		padded := padColumn(cell, id)
		if !noColor && !selected {
			switch id {
			case colRSS:
				padded = rssStyle(p, noColor).Render(padded)
			case colCPU:
				padded = cpuStyle(p, noColor).Render(padded)
			case colHeapUsed, colHeapCmt, colXmx:
				padded = heapStyle(noColor).Render(padded)
			}
		}
		parts = append(parts, padded)
	}
	row := strings.Join(parts, " ")
	if signals := render.ProcessSignals(p); len(signals) > 0 {
		// Keep badges outside the fixed-width table cells. Prefixing a badge
		// shifts every cell to the right while the header remains unchanged.
		if noColor || selected {
			row += "  " + strings.Join(signals, " ")
		} else {
			styled := make([]string, 0, len(signals))
			for _, signal := range signals {
				styled = append(styled, signalStyle(signal, noColor).Render(signal))
			}
			row += "  " + strings.Join(styled, " ")
		}
	}
	return row
}

func truncateWidth(s string, width int) string {
	if width <= 0 {
		return ""
	}
	if displaywidth.String(s) <= width {
		return s
	}
	if width == 1 {
		return "…"
	}
	var b strings.Builder
	remaining := width - 1
	gr := uniseg.NewGraphemes(s)
	for gr.Next() {
		g := gr.Str()
		w := displaywidth.String(g)
		if w > remaining {
			break
		}
		b.WriteString(g)
		remaining -= w
	}
	b.WriteString("…")
	return b.String()
}

func padRight(s string, width int) string {
	w := displaywidth.String(s)
	if w >= width {
		return truncateWidth(s, width)
	}
	return s + strings.Repeat(" ", width-w)
}

func padLeft(s string, width int) string {
	w := displaywidth.String(s)
	if w >= width {
		return truncateWidth(s, width)
	}
	return strings.Repeat(" ", width-w) + s
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// Run starts the Bubble Tea program.
func Run(cfg Config) error {
	m := NewModel(cfg)
	p := tea.NewProgram(m)
	_, err := p.Run()
	return err
}
