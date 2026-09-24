package builds

import (
	"fmt"
	"strings"

	"github.com/cdsap/daemonitor/cored/internal/logs"
)

// SampleProvider returns RSS/CPU samples for a PID inside [startMs, endMs].
type SampleProvider func(pid, startMs, endMs int64) []Sample

// LogSnippetLimit bounds in-window build log excerpts.
type LogSnippetLimit struct {
	Lines int
	Chars int
}

// DefaultLogSnippetLimit mirrors Kotlin BuildAggregator.DEFAULT_LOG_SNIPPET_LIMIT.
var DefaultLogSnippetLimit = LogSnippetLimit{Lines: 100, Chars: 16_000}

// Aggregator correlates daemon-log events with poll samples into confirmed Builds (U5 / KTD-1).
type Aggregator struct {
	sampleProvider  SampleProvider
	ambientEnvNames map[string]struct{}
	logSnippetLimit LogSnippetLimit
	daemons         map[int64]*daemonState
}

func NewAggregator(sampleProvider SampleProvider, ambientEnvNames map[string]struct{}, limit LogSnippetLimit) *Aggregator {
	if sampleProvider == nil {
		sampleProvider = func(int64, int64, int64) []Sample { return nil }
	}
	if ambientEnvNames == nil {
		ambientEnvNames = map[string]struct{}{}
	}
	if limit.Lines <= 0 {
		limit.Lines = DefaultLogSnippetLimit.Lines
	}
	if limit.Chars <= 0 {
		limit.Chars = DefaultLogSnippetLimit.Chars
	}
	return &Aggregator{
		sampleProvider:  sampleProvider,
		ambientEnvNames: ambientEnvNames,
		logSnippetLimit: limit,
		daemons:         make(map[int64]*daemonState),
	}
}

// OnEvents feeds parsed events without accompanying log text.
func (a *Aggregator) OnEvents(daemonPID int64, events []logs.Event) []Build {
	var out []Build
	for _, ev := range events {
		ev := ev
		out = append(out, a.processLogLine(daemonPID, "", &ev)...)
	}
	return out
}

// OnLogLine correlates one redacted log line with its event while preserving window boundaries.
func (a *Aggregator) OnLogLine(daemonPID int64, line string, event *logs.Event) []Build {
	return a.processLogLine(daemonPID, line, event)
}

func (a *Aggregator) processLogLine(daemonPID int64, line string, event *logs.Event) []Build {
	state := a.daemons[daemonPID]
	if state == nil {
		state = &daemonState{}
		a.daemons[daemonPID] = state
	}
	var emitted []Build

	// A busy marker belongs to the window it opens. Every other line belongs to the currently
	// open window, including the idle marker that closes it.
	isBusy := event != nil && event.Kind == logs.KindBusyMark
	if !isBusy && state.window != nil && line != "" {
		state.window.appendLogLine(line)
	}

	if event == nil {
		return emitted
	}

	switch event.Kind {
	case logs.KindDaemonContext:
		if event.UID != "" {
			state.uid = event.UID
		}
	case logs.KindBusyMark:
		if state.window != nil && state.window.qualified {
			emitted = append(emitted, state.window.toBuild(daemonPID, state.uid, &event.TimestampMs, a.sampleProvider, a.ambientEnvNames, false))
		}
		state.window = newWindow(event.TimestampMs, a.logSnippetLimit)
		if line != "" {
			state.window.appendLogLine(line)
		}
	case logs.KindBuildStart:
		if state.window != nil {
			state.window.qualified = true
			if event.BuildID != "" {
				state.window.buildID = event.BuildID
			}
			if event.CurrentDir != "" {
				state.window.currentDir = event.CurrentDir
			}
		}
	case logs.KindBuildEnvNames:
		if state.window != nil {
			state.window.envNames = append([]string{}, event.EnvNames...)
		}
	case logs.KindOutcome:
		if state.window != nil && event.Success != nil {
			state.window.outcomeSuccess = event.Success
			state.window.outcomeDurationSeconds = event.DurationSeconds
		}
	case logs.KindIdleMark:
		if state.window != nil && state.window.qualified {
			emitted = append(emitted, state.window.toBuild(daemonPID, state.uid, &event.TimestampMs, a.sampleProvider, a.ambientEnvNames, false))
		}
		state.window = nil
	}
	return emitted
}

// OnDaemonGone emits a qualified open build when a daemon PID disappears.
func (a *Aggregator) OnDaemonGone(daemonPID int64) *Build {
	state := a.daemons[daemonPID]
	if state == nil {
		return nil
	}
	delete(a.daemons, daemonPID)
	w := state.window
	if w == nil || !w.qualified {
		return nil
	}
	var endMs *int64
	if w.outcomeDurationSeconds != nil {
		v := w.busyTimeMs + int64(*w.outcomeDurationSeconds*1000)
		endMs = &v
	}
	interrupted := w.outcomeSuccess == nil
	b := w.toBuild(daemonPID, state.uid, endMs, a.sampleProvider, a.ambientEnvNames, interrupted)
	return &b
}

type daemonState struct {
	uid    string
	window *window
}

type window struct {
	busyTimeMs             int64
	logSnippetLimit        LogSnippetLimit
	qualified              bool
	buildID                string
	currentDir             string
	envNames               []string
	outcomeSuccess         *bool
	outcomeDurationSeconds *float64
	logLines               []string
	logChars               int
}

func newWindow(busyTimeMs int64, limit LogSnippetLimit) *window {
	return &window{busyTimeMs: busyTimeMs, logSnippetLimit: limit}
}

func (w *window) appendLogLine(line string) {
	bounded := line
	if len(bounded) > w.logSnippetLimit.Chars {
		bounded = bounded[len(bounded)-w.logSnippetLimit.Chars:]
	}
	w.logLines = append(w.logLines, bounded)
	w.logChars += len(bounded)
	if len(w.logLines) > 1 {
		w.logChars++ // newline between lines
	}
	for len(w.logLines) > w.logSnippetLimit.Lines || w.logChars > w.logSnippetLimit.Chars {
		removed := w.logLines[0]
		w.logLines = w.logLines[1:]
		w.logChars -= len(removed)
		if len(w.logLines) > 0 {
			w.logChars--
		}
	}
}

func (w *window) toBuild(
	daemonPID int64,
	uid string,
	endMs *int64,
	sampleProvider SampleProvider,
	ambientEnvNames map[string]struct{},
	interrupted bool,
) Build {
	var samples []Sample
	if endMs != nil {
		samples = sampleProvider(daemonPID, w.busyTimeMs, *endMs)
	}

	var rss []int64
	var cpu []float64
	for _, s := range samples {
		rss = append(rss, s.RSSMemoryMB)
		if s.CPUPercent != nil {
			cpu = append(cpu, *s.CPUPercent)
		}
	}

	status := StatusCompletedNoOutcome
	switch {
	case interrupted:
		status = StatusInterrupted
	case w.outcomeSuccess != nil && *w.outcomeSuccess:
		status = StatusSuccess
	case w.outcomeSuccess != nil && !*w.outcomeSuccess:
		status = StatusFailed
	}

	var duration *float64
	if w.outcomeDurationSeconds != nil {
		duration = w.outcomeDurationSeconds
	} else if endMs != nil {
		d := float64(*endMs-w.busyTimeMs) / 1000.0
		duration = &d
	}

	source := DetectSource(w.envNames, nil)
	var agent, provider string
	if source != SourceIDE {
		if attr := DetectAgent(w.envNames, ambientEnvNames); attr != nil {
			agent = attr.Agent
			provider = attr.Provider
		}
	}

	buildID := w.buildID
	if buildID == "" {
		buildID = fmt.Sprintf("%d-%d", daemonPID, w.busyTimeMs)
	}

	b := Build{
		BuildID:          buildID,
		DaemonPID:        daemonPID,
		DaemonIdentity:   uid,
		CommandLine:      nil,
		WorkingDirectory: w.currentDir,
		ProjectPath:      w.currentDir,
		StartTimeMs:      w.busyTimeMs,
		EndTimeMs:        endMs,
		DurationSeconds:  duration,
		InferredSource:   source,
		FinalStatus:      status,
		Agent:            agent,
		AgentProvider:    provider,
	}
	if len(w.logLines) > 0 {
		b.LogSnippet = strings.Join(w.logLines, "\n")
	}
	if len(rss) > 0 {
		peak := rss[0]
		sum := int64(0)
		for _, v := range rss {
			sum += v
			if v > peak {
				peak = v
			}
		}
		avg := sum / int64(len(rss))
		b.PeakMemoryMB = &peak
		b.AvgMemoryMB = &avg
	}
	if len(cpu) > 0 {
		peak := cpu[0]
		for _, v := range cpu {
			if v > peak {
				peak = v
			}
		}
		b.PeakCPUPercent = &peak
	}
	return b
}
