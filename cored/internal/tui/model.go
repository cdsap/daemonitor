package tui

import (
	"context"
	"fmt"
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
}

// Model is Bubble Tea presentation state for the process monitor.
type Model struct {
	client       *client.Client
	pollInterval time.Duration
	noColor      bool
	now          func() time.Time

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
	detailLoading bool
	detailError   string
}

type tickMsg time.Time

type snapshotLoadedMsg struct {
	snap model.Snapshot
}

type snapshotFailedMsg struct {
	err error
}

type detailsLoadedMsg struct {
	pid  int64
	log  *logs.DaemonLog
	tail *logs.Tail
	err  error
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
	return Model{
		client:       cfg.Client,
		pollInterval: interval,
		noColor:      cfg.NoColor,
		now:          now,
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

func (m Model) fetchDetails(pid int64) tea.Cmd {
	if m.client == nil {
		return nil
	}
	c := m.client
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		var logMeta *logs.DaemonLog
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
		if tailErr != nil {
			return detailsLoadedMsg{pid: pid, log: logMeta, err: tailErr}
		}
		return detailsLoadedMsg{pid: pid, log: logMeta, tail: &tail}
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
		m.detailLoading = false
		m.detailLog = msg.log
		m.detailTail = msg.tail
		if msg.err != nil {
			m.detailError = msg.err.Error()
		} else {
			m.detailError = ""
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
			m.detailError = ""
			m.detailLoading = false
			return m, nil
		case "ctrl+c":
			return m, tea.Quit
		}
		return m, nil
	}

	switch key {
	case "q", "ctrl+c":
		return m, tea.Quit
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
			m.detailError = ""
			return m, m.fetchDetails(int64(p.PID))
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

func (m *Model) cycleSortField() {
	m.sortField = (m.sortField + 1) % 6
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
		left = titleStyle(false).Render("DAEMONITOR") + strings.Repeat(" ", pad)
	}
	fmt.Fprintf(&b, "%s%s\n", left, right)
	line2 := fmt.Sprintf("%d processes   %s RSS   %s   Refresh %s",
		len(m.processes), render.RSSText(totalRSS), status, m.pollInterval)
	if m.sortField != SortRSS || m.sortOrder != SortDesc {
		line2 += fmt.Sprintf("   Sort %s %s", m.sortField, m.sortOrder)
	} else {
		line2 += fmt.Sprintf("   Sort %s %s", m.sortField, m.sortOrder)
	}
	b.WriteString(truncateWidth(line2, m.width) + "\n")
	if m.lastError != "" {
		errLine := "Last refresh failed: " + m.lastError
		if !m.noColor {
			errLine = errorStyle(false).Render(errLine)
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
		b.WriteString(truncateWidth(headerLine(cols, m.sortField), m.width) + "\n")
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
			row := prefix + formatRow(p, cols, now)
			row = truncateWidth(row, m.width)
			if selected && !m.noColor {
				row = selectedStyle(false).Render(row)
			}
			b.WriteString(row + "\n")
		}
	}

	b.WriteString("\n")
	footer := "↑/↓ or j/k select   s sort   space pause   r refresh   enter details   ? help   q quit"
	if m.helpOpen {
		footer = "Keys: q quit · arrows/jk move · g/G home/end · s/S sort · space pause · r refresh · enter details · esc close details"
	}
	b.WriteString(truncateWidth(footer, m.width))
	return b.String()
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
	fmt.Fprintf(&b, "PROCESS DETAILS  pid=%d\n\n", p.PID)
	writeField(&b, "Type", render.TypeDisplay(p.Type))
	writeField(&b, "PID", fmt.Sprintf("%d", p.PID))
	writeField(&b, "Name", na(p.Name))
	writeField(&b, "Project", render.ProjectName(*p))
	if p.WorkingDirectory != nil {
		writeField(&b, "Working dir", *p.WorkingDirectory)
	} else {
		writeField(&b, "Working dir", "n/a")
	}
	writeField(&b, "RSS", render.RSSText(p.RSSMemoryMB))
	writeField(&b, "CPU", render.CPUText(p.CPUPercent))
	writeField(&b, "Xmx", render.HeapLimitText(p.MaxHeapMB))
	writeField(&b, "Heap used", "n/a")
	writeField(&b, "Heap committed", "n/a")
	writeField(&b, "Start", formatStart(p.StartTimeMs))
	writeField(&b, "Uptime", render.Uptime(p.StartTimeMs, now))
	if m.detailLog != nil {
		writeField(&b, "Gradle", na(m.detailLog.GradleVersion))
		writeField(&b, "Daemon log", na(m.detailLog.Path))
	} else if m.detailLoading {
		writeField(&b, "Gradle", "…")
		writeField(&b, "Daemon log", "…")
	} else {
		writeField(&b, "Gradle", "n/a")
		writeField(&b, "Daemon log", "n/a")
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

func writeField(b *strings.Builder, label, value string) {
	fmt.Fprintf(b, "  %-14s %s\n", label+":", value)
}

func na(s string) string {
	if strings.TrimSpace(s) == "" {
		return "n/a"
	}
	return s
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
	colPID
	colRSS
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
		return columnSet{ids: []columnID{colType, colPID, colRSS, colHeapUsed, colHeapCmt, colXmx, colCPU, colUptime, colProject}}
	case width >= 80:
		return columnSet{ids: []columnID{colType, colPID, colRSS, colXmx, colCPU, colUptime, colProject}}
	default:
		return columnSet{ids: []columnID{colType, colPID, colRSS, colCPU, colProject}}
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
	}
	return false
}

func columnLabel(id columnID) string {
	switch id {
	case colType:
		return "TYPE"
	case colPID:
		return "PID"
	case colRSS:
		return "RSS"
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
	case colPID:
		return 7
	case colRSS:
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
	if id == colPID || id == colRSS || id == colCPU || id == colXmx || id == colHeapUsed || id == colHeapCmt || id == colUptime {
		return padLeft(truncateWidth(s, w), w)
	}
	return padRight(truncateWidth(s, w), w)
}

func formatRow(p model.Process, cols columnSet, nowMs int64) string {
	parts := make([]string, 0, len(cols.ids))
	for _, id := range cols.ids {
		var cell string
		switch id {
		case colType:
			cell = render.TypeDisplay(p.Type)
		case colPID:
			cell = fmt.Sprintf("%d", p.PID)
		case colRSS:
			cell = render.RSSText(p.RSSMemoryMB)
		case colHeapUsed, colHeapCmt:
			cell = "n/a"
		case colXmx:
			cell = render.HeapLimitText(p.MaxHeapMB)
		case colCPU:
			cell = render.CPUText(p.CPUPercent)
		case colUptime:
			cell = render.Uptime(p.StartTimeMs, nowMs)
		case colProject:
			cell = render.ProjectName(p)
		}
		parts = append(parts, padColumn(cell, id))
	}
	return strings.Join(parts, " ")
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
