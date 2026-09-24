package tui

import (
	"context"
	"fmt"
	"strings"
	"time"
	"unicode"

	tea "charm.land/bubbletea/v2"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

// LogFetcher loads a daemon's redacted log tail on demand. Implementations
// return (nil, nil) when the core has no log for pid.
type LogFetcher interface {
	DaemonLogTail(ctx context.Context, pid int32) (*model.DaemonLogTail, error)
}

type logState int

const (
	logUnavailable logState = iota
	logLoading
	logLoaded
	logMissing
	logFailed
)

const detailsLabelWidth = 16

// Only Gradle daemons write daemon-<pid>.out.log files the core can tail.
const daemonLogProcessType = "GRADLE_DAEMON"

type detailsState struct {
	open   bool
	proc   model.Process
	exited bool

	logState logState
	tail     *model.DaemonLogTail
	logErr   error
	logSeq   int
}

type logTailLoadedMsg struct {
	pid  int32
	seq  int
	tail *model.DaemonLogTail
	err  error
}

func (m Model) updateDetailsKey(msg tea.KeyPressMsg) (tea.Model, tea.Cmd) {
	switch msg.String() {
	case "q", "Q", "ctrl+c":
		m.quitting = true
		return m, tea.Quit
	case "esc":
		m.details.open = false
		m.ensureSelectionVisible()
	case " ", "space":
		m.paused = !m.paused
	case "r", "R":
		var cmds []tea.Cmd
		if !m.loading {
			m.loading = true
			cmds = append(cmds, m.fetchSnapshot())
		}
		if m.details.logState != logLoading {
			cmds = append(cmds, m.fetchLogTail())
		}
		return m, tea.Batch(cmds...)
	}
	return m, nil
}

// openDetails shows the selected process and starts the only log request;
// table rows never trigger log fetches.
func (m *Model) openDetails() tea.Cmd {
	idx := m.selectedIndex()
	if idx < 0 {
		return nil
	}
	m.details = detailsState{open: true, proc: m.processes[idx], logSeq: m.details.logSeq}
	return m.fetchLogTail()
}

func (m *Model) fetchLogTail() tea.Cmd {
	m.details.logSeq++
	m.details.tail = nil
	m.details.logErr = nil
	if m.logs == nil || m.details.proc.Type != daemonLogProcessType {
		m.details.logState = logUnavailable
		return nil
	}
	m.details.logState = logLoading
	logs, pid, seq := m.logs, m.details.proc.PID, m.details.logSeq
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		tail, err := logs.DaemonLogTail(ctx, pid)
		return logTailLoadedMsg{pid: pid, seq: seq, tail: tail, err: err}
	}
}

func (m *Model) applyLogTail(msg logTailLoadedMsg) {
	if !m.details.open || msg.seq != m.details.logSeq || msg.pid != m.details.proc.PID {
		return
	}
	switch {
	case msg.err != nil:
		m.details.logState = logFailed
		m.details.logErr = msg.err
	case msg.tail == nil:
		m.details.logState = logMissing
	default:
		m.details.logState = logLoaded
		m.details.tail = msg.tail
	}
}

// syncDetailsProcess keeps live metrics current and retains the last known
// values once the process disappears from snapshots.
func (m *Model) syncDetailsProcess() {
	if !m.details.open {
		return
	}
	if idx := indexOfPID(m.processes, m.details.proc.PID); idx >= 0 {
		m.details.proc = m.processes[idx]
		m.details.exited = false
		return
	}
	m.details.exited = true
}

// renderDetails lays out a full-screen view. Fields and the footer take
// priority; blank separators and then the log section collapse first when
// the terminal is short.
func (m Model) renderDetails(width, height int) string {
	p := m.details.proc
	header := strings.Split(m.renderHeader(width), "\n")

	title := fmt.Sprintf("%s · PID %d", typeDisplay(p.Type), p.PID)
	if m.details.exited {
		title += " · exited (last known values)"
	}
	fields := m.detailFields(width)
	footer := m.styled(footerStyle, truncateRunes("esc back   r refresh   space pause   q quit", width))

	spare := height - len(header) - 1 - len(fields) - 1
	topGap := spare >= 1
	if topGap {
		spare--
	}
	footerGap := spare >= 1
	if footerGap {
		spare--
	}

	body := header
	if topGap {
		body = append(body, "")
	}
	body = append(body, m.styled(titleStyle, truncateRunes(title, width)))
	body = append(body, fields...)
	// Separator + heading + at least one line of log content.
	if spare >= 3 {
		body = append(body, "", m.styled(headerStyle, truncateRunes("RECENT DAEMON LOG (redacted)", width)))
		body = append(body, m.logLines(width, spare-2)...)
	}
	if maxBody := height - 1; len(body) > maxBody {
		body = body[:max(maxBody, 0)]
	}
	if footerGap {
		body = append(body, "")
	}
	return strings.Join(append(body, footer), "\n")
}

func (m Model) detailFields(width int) []string {
	p := m.details.proc
	ref := m.lastUpdated
	if ref.IsZero() && p.SampledAtMs > 0 {
		ref = time.UnixMilli(p.SampledAtMs)
	}
	fields := [][2]string{
		{"Name", orNA(p.Name)},
		{"Project", orNAPtr(p.ProjectPath)},
		{"Working dir", orNAPtr(p.WorkingDirectory)},
		{"RSS", formatMemMB(p.RSSMemoryMB)},
		{"CPU", formatCPU(p.CPUPercent)},
		{"Xmx", formatMaxHeap(p.MaxHeapMB)},
		// The Go core does not expose live heap yet; never show a value here.
		{"Live heap", "used n/a   committed n/a"},
		{"Started", formatStarted(p.StartTimeMs, ref)},
		{"Gradle version", m.logField(func(t *model.DaemonLogTail) string { return t.GradleVersion })},
		{"Daemon log", m.logField(func(t *model.DaemonLogTail) string { return t.Path })},
	}
	lines := make([]string, len(fields))
	for i, f := range fields {
		lines[i] = truncateRunes(padRight(f[0], detailsLabelWidth)+sanitizeForTerminal(f[1]), width)
	}
	return lines
}

func (m Model) logField(get func(*model.DaemonLogTail) string) string {
	switch m.details.logState {
	case logLoading:
		return "loading…"
	case logLoaded:
		return orNA(get(m.details.tail))
	default:
		return "n/a"
	}
}

func (m Model) logLines(width, avail int) []string {
	if avail <= 0 {
		return nil
	}
	var msg string
	switch m.details.logState {
	case logLoading:
		msg = "Loading daemon log…"
	case logMissing:
		msg = "n/a (no daemon log found for this process)"
	case logFailed:
		msg = fmt.Sprintf("Could not load daemon log: %v", m.details.logErr)
	case logUnavailable:
		msg = "n/a"
		if m.details.proc.Type != daemonLogProcessType {
			msg = "n/a (daemon logs are only available for Gradle daemons)"
		}
	case logLoaded:
		lines := m.details.tail.Lines
		if len(lines) == 0 {
			msg = "n/a (no recent log lines)"
			break
		}
		if len(lines) > avail {
			lines = lines[len(lines)-avail:]
		}
		out := make([]string, len(lines))
		for i, line := range lines {
			out[i] = truncateRunes(sanitizeForTerminal(line), width)
		}
		return out
	}
	return []string{truncateRunes(msg, width)}
}

// sanitizeForTerminal drops control characters so log or process text cannot
// emit terminal escape sequences or break the layout.
func sanitizeForTerminal(line string) string {
	line = strings.TrimRight(line, "\r\n")
	return strings.Map(func(r rune) rune {
		if r == '\t' {
			return ' '
		}
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, line)
}

func orNA(s string) string {
	if strings.TrimSpace(s) == "" {
		return "n/a"
	}
	return s
}

func orNAPtr(s *string) string {
	if s == nil {
		return "n/a"
	}
	return orNA(*s)
}

func formatCPU(cpu *float64) string {
	if cpu == nil {
		return "n/a"
	}
	return fmt.Sprintf("%.1f%%", *cpu)
}

func formatMaxHeap(mb *int64) string {
	if mb == nil {
		return "n/a"
	}
	return formatMemMB(*mb)
}

// formatStarted renders start time in the same location as the header's
// "Updated" timestamp, followed by uptime.
func formatStarted(startMs int64, ref time.Time) string {
	if startMs <= 0 {
		return "n/a"
	}
	loc := time.Local
	if !ref.IsZero() {
		loc = ref.Location()
	}
	started := time.UnixMilli(startMs).In(loc).Format("2006-01-02 15:04:05")
	return fmt.Sprintf("%s (up %s)", started, formatUptime(startMs, ref))
}
