package builds_test

import (
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/logs"
)

const pid int64 = 75597

func context(ts int64) logs.Event {
	return logs.Event{Kind: logs.KindDaemonContext, TimestampMs: ts, UID: "uid-abc", DaemonOpts: "-Xmx512m"}
}

func start(ts int64, dir string) logs.Event {
	if dir == "" {
		dir = "/proj/a"
	}
	return logs.Event{Kind: logs.KindBuildStart, TimestampMs: ts, BuildID: "build-" + itoa(ts), CurrentDir: dir}
}

func busy(ts int64) logs.Event  { return logs.Event{Kind: logs.KindBusyMark, TimestampMs: ts} }
func idle(ts int64) logs.Event  { return logs.Event{Kind: logs.KindIdleMark, TimestampMs: ts} }
func outcome(ok bool, dur float64) logs.Event {
	return logs.Event{Kind: logs.KindOutcome, Success: &ok, DurationSeconds: &dur}
}
func env(ts int64, names ...string) logs.Event {
	return logs.Event{Kind: logs.KindBuildEnvNames, TimestampMs: ts, EnvNames: names}
}

func TestQualifiedBuildEmitsPeaks(t *testing.T) {
	cpu10, cpu50, cpu30 := 10.0, 50.0, 30.0
	samples := []builds.Sample{
		{RSSMemoryMB: 100, CPUPercent: &cpu10},
		{RSSMemoryMB: 300, CPUPercent: &cpu50},
		{RSSMemoryMB: 200, CPUPercent: &cpu30},
	}
	agg := builds.NewAggregator(func(int64, int64, int64) []builds.Sample { return samples }, nil, builds.DefaultLogSnippetLimit)
	emitted := agg.OnEvents(pid, []logs.Event{
		context(0), busy(1000), start(1010, ""), env(1020, "TERM_PROGRAM"), outcome(true, 3), idle(4000),
	})
	if len(emitted) != 1 {
		t.Fatalf("emitted=%d", len(emitted))
	}
	b := emitted[0]
	if b.PeakMemoryMB == nil || *b.PeakMemoryMB != 300 {
		t.Fatalf("peakMem=%v", b.PeakMemoryMB)
	}
	if b.AvgMemoryMB == nil || *b.AvgMemoryMB != 200 {
		t.Fatalf("avgMem=%v", b.AvgMemoryMB)
	}
	if b.PeakCPUPercent == nil || *b.PeakCPUPercent != 50 {
		t.Fatalf("peakCpu=%v", b.PeakCPUPercent)
	}
	if b.FinalStatus != builds.StatusSuccess || b.InferredSource != builds.SourceTerminal {
		t.Fatalf("status=%s source=%s", b.FinalStatus, b.InferredSource)
	}
	if b.DaemonIdentity != "uid-abc" || b.ProjectPath != "/proj/a" {
		t.Fatalf("identity/path=%s %s", b.DaemonIdentity, b.ProjectPath)
	}
}

func TestTwoSequentialBuilds(t *testing.T) {
	agg := builds.NewAggregator(nil, nil, builds.DefaultLogSnippetLimit)
	emitted := agg.OnEvents(pid, []logs.Event{
		context(0),
		busy(1000), start(1010, ""), outcome(true, 1), idle(2000),
		busy(3000), start(3010, ""), outcome(true, 1), idle(4000),
	})
	if len(emitted) != 2 {
		t.Fatalf("emitted=%d", len(emitted))
	}
	if emitted[0].BuildID == emitted[1].BuildID {
		t.Fatal("expected distinct build ids")
	}
}

func TestUnqualifiedBracketEmitsNothing(t *testing.T) {
	agg := builds.NewAggregator(nil, nil, builds.DefaultLogSnippetLimit)
	emitted := agg.OnEvents(pid, []logs.Event{context(0), busy(1000), idle(1500)})
	if len(emitted) != 0 {
		t.Fatalf("emitted=%v", emitted)
	}
}

func TestNoOutcomeCompletedNoOutcome(t *testing.T) {
	agg := builds.NewAggregator(nil, nil, builds.DefaultLogSnippetLimit)
	emitted := agg.OnEvents(pid, []logs.Event{context(0), busy(1000), start(1010, ""), idle(5000)})
	if len(emitted) != 1 || emitted[0].FinalStatus != builds.StatusCompletedNoOutcome {
		t.Fatalf("got %+v", emitted)
	}
}

func TestDaemonGoneInterrupted(t *testing.T) {
	agg := builds.NewAggregator(nil, nil, builds.DefaultLogSnippetLimit)
	_ = agg.OnEvents(pid, []logs.Event{context(0), busy(1000), start(1010, "")})
	b := agg.OnDaemonGone(pid)
	if b == nil || b.FinalStatus != builds.StatusInterrupted {
		t.Fatalf("got %+v", b)
	}
}

func TestDaemonGoneAfterOutcome(t *testing.T) {
	cpu20, cpu50 := 20.0, 50.0
	samples := []builds.Sample{{RSSMemoryMB: 100, CPUPercent: &cpu20}, {RSSMemoryMB: 140, CPUPercent: &cpu50}}
	agg := builds.NewAggregator(func(int64, int64, int64) []builds.Sample { return samples }, nil, builds.DefaultLogSnippetLimit)
	_ = agg.OnEvents(pid, []logs.Event{context(0), busy(1000), start(1010, ""), outcome(true, 2)})
	b := agg.OnDaemonGone(pid)
	if b == nil {
		t.Fatal("nil")
	}
	if b.FinalStatus != builds.StatusSuccess || b.EndTimeMs == nil || *b.EndTimeMs != 3000 {
		t.Fatalf("got %+v", b)
	}
	if b.DurationSeconds == nil || *b.DurationSeconds != 2 {
		t.Fatalf("dur=%v", b.DurationSeconds)
	}
	if b.PeakMemoryMB == nil || *b.PeakMemoryMB != 140 || b.PeakCPUPercent == nil || *b.PeakCPUPercent != 50 {
		t.Fatalf("peaks mem=%v cpu=%v", b.PeakMemoryMB, b.PeakCPUPercent)
	}
}

func TestZeroSamplesNullPeaks(t *testing.T) {
	agg := builds.NewAggregator(func(int64, int64, int64) []builds.Sample { return nil }, nil, builds.DefaultLogSnippetLimit)
	b := agg.OnEvents(pid, []logs.Event{context(0), busy(1000), start(1010, ""), outcome(true, 0.3), idle(1300)})[0]
	if b.PeakMemoryMB != nil || b.AvgMemoryMB != nil || b.PeakCPUPercent != nil {
		t.Fatalf("expected null peaks: %+v", b)
	}
}

func TestFailedOutcome(t *testing.T) {
	agg := builds.NewAggregator(nil, nil, builds.DefaultLogSnippetLimit)
	b := agg.OnEvents(pid, []logs.Event{context(0), busy(1000), start(1010, ""), outcome(false, 2), idle(3000)})[0]
	if b.FinalStatus != builds.StatusFailed {
		t.Fatalf("status=%s", b.FinalStatus)
	}
}

func TestClaudeAgentAttribution(t *testing.T) {
	agg := builds.NewAggregator(nil, nil, builds.DefaultLogSnippetLimit)
	b := agg.OnEvents(pid, []logs.Event{
		context(0), busy(1000), start(1010, ""),
		env(1020, "PATH", "CLAUDECODE", "CLAUDE_CODE_SESSION_ID", "AI_AGENT"),
		outcome(true, 1), idle(2000),
	})[0]
	if b.Agent != "Claude Code" || b.AgentProvider != "Anthropic" {
		t.Fatalf("agent=%s provider=%s", b.Agent, b.AgentProvider)
	}
}

func TestIDEDoesNotAttributeClaude(t *testing.T) {
	agg := builds.NewAggregator(nil, nil, builds.DefaultLogSnippetLimit)
	b := agg.OnEvents(pid, []logs.Event{
		context(0), busy(1000), start(1010, ""),
		env(1020, "PATH", "VSCODE_GIT_IPC_HANDLE", "CLAUDECODE", "CLAUDE_CODE_SESSION_ID", "AI_AGENT"),
		outcome(true, 1), idle(2000),
	})[0]
	if b.InferredSource != builds.SourceIDE || b.Agent != "" || b.AgentProvider != "" {
		t.Fatalf("got source=%s agent=%s provider=%s", b.InferredSource, b.Agent, b.AgentProvider)
	}
}

func TestSecondBusyFlushesFirst(t *testing.T) {
	agg := builds.NewAggregator(nil, nil, builds.DefaultLogSnippetLimit)
	emitted := agg.OnEvents(pid, []logs.Event{
		context(0),
		busy(1000), start(1010, ""), outcome(true, 1),
		busy(3000), start(3010, ""), outcome(true, 1), idle(4000),
	})
	if len(emitted) != 2 {
		t.Fatalf("emitted=%d", len(emitted))
	}
}

func TestSingleIdentityAcrossProjects(t *testing.T) {
	agg := builds.NewAggregator(nil, nil, builds.DefaultLogSnippetLimit)
	emitted := agg.OnEvents(pid, []logs.Event{
		context(0),
		busy(1000), start(1010, "/proj/a"), outcome(true, 1), idle(2000),
		busy(3000), start(3010, "/proj/b"), outcome(true, 1), idle(4000),
	})
	if emitted[0].DaemonIdentity != "uid-abc" || emitted[1].DaemonIdentity != "uid-abc" {
		t.Fatalf("identities=%v %v", emitted[0].DaemonIdentity, emitted[1].DaemonIdentity)
	}
	if emitted[0].ProjectPath != "/proj/a" || emitted[1].ProjectPath != "/proj/b" {
		t.Fatalf("paths=%v %v", emitted[0].ProjectPath, emitted[1].ProjectPath)
	}
}

func TestBoundedLogSnippet(t *testing.T) {
	limit := builds.LogSnippetLimit{Lines: 5, Chars: 500}
	agg := builds.NewAggregator(nil, nil, limit)
	bMark := busy(1000)
	sMark := start(1010, "")
	_ = agg.OnLogLine(pid, "busy", &bMark)
	_ = agg.OnLogLine(pid, "start", &sMark)
	for i := 0; i < limit.Lines+5; i++ {
		line := "line-" + itoa(int64(i)) + "-" + strings.Repeat("x", 200)
		_ = agg.OnLogLine(pid, line, nil)
	}
	b := agg.OnDaemonGone(pid)
	if b == nil || b.FinalStatus != builds.StatusInterrupted {
		t.Fatalf("got %+v", b)
	}
	snippet := b.LogSnippet
	if strings.Count(snippet, "\n")+1 > limit.Lines {
		t.Fatalf("too many lines in snippet")
	}
	if len(snippet) > limit.Chars {
		t.Fatalf("snippet too long: %d", len(snippet))
	}
	if !strings.Contains(snippet, "line-"+itoa(int64(limit.Lines+4))) {
		t.Fatalf("missing latest line: %s", snippet)
	}
}

func itoa(n int64) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
