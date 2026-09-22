package logs

import (
	"regexp"
	"strconv"
	"strings"
	"time"
)

// EventKind identifies a parsed daemon-log build event (Kotlin BuildEvent).
type EventKind string

const (
	KindDaemonContext EventKind = "daemon_context"
	KindBusyMark      EventKind = "busy_mark"
	KindIdleMark      EventKind = "idle_mark"
	KindBuildStart    EventKind = "build_start"
	KindBuildEnvNames EventKind = "build_env_names"
	KindOutcome       EventKind = "outcome"
)

// Event is a JSON-friendly form of Kotlin BuildEvent (DaemonLogParser / U3).
type Event struct {
	Kind            EventKind `json:"kind"`
	TimestampMs     int64     `json:"timestamp_ms,omitempty"`
	UID             string    `json:"uid,omitempty"`
	DaemonOpts      string    `json:"daemon_opts,omitempty"`
	BuildID         string    `json:"build_id,omitempty"`
	CurrentDir      string    `json:"current_dir,omitempty"`
	EnvNames        []string  `json:"env_names,omitempty"`
	Success         *bool     `json:"success,omitempty"`
	DurationSeconds *float64  `json:"duration_seconds,omitempty"`
}

var (
	prefixedRE    = regexp.MustCompile(`^(\S+) \[(\w+)\] \[([^\]]+)\] (.*)$`)
	outcomeRE     = regexp.MustCompile(`^BUILD (SUCCESSFUL|FAILED) in (.+?)\s*$`)
	buildIDDirRE  = regexp.MustCompile(`Build\{id=([^,]+), currentDir=([^}]+)\}`)
	startingRE    = regexp.MustCompile(`^Starting (\d+(?:st|nd|rd|th)|build in new) (?:build in )?daemon`)
	envListRE     = regexp.MustCompile(`Configuring env variables: \[([^\]]*)\]`)
	contextUIDRE  = regexp.MustCompile(`uid=([^,]+)`)
	contextOptsRE = regexp.MustCompile(`daemonOpts=([^\]]*)`)
	msDurRE  = regexp.MustCompile(`^(\d+)ms$`)
	minDurRE = regexp.MustCompile(`(\d+)m(?:\s|$)`)
	secDurRE = regexp.MustCompile(`(\d+(?:\.\d+)?)s`)
)

// ParseLines maps redacted daemon-log lines to build events (order preserved; skips noise).
func ParseLines(lines []string) []Event {
	out := make([]Event, 0)
	for _, line := range lines {
		if ev, ok := ParseLine(line); ok {
			out = append(out, ev)
		}
	}
	return out
}

// ParseLine mirrors Kotlin DaemonLogParser.parseLine.
func ParseLine(line string) (Event, bool) {
	if m := outcomeRE.FindStringSubmatch(line); m != nil {
		success := m[1] == "SUCCESSFUL"
		dur := ParseDuration(m[2])
		return Event{
			Kind:            KindOutcome,
			Success:         &success,
			DurationSeconds: &dur,
		}, true
	}

	pre := prefixedRE.FindStringSubmatch(line)
	if pre == nil {
		return Event{}, false
	}
	ts, ok := parseTimestamp(pre[1])
	if !ok {
		return Event{}, false
	}
	msg := pre[4]

	switch {
	case strings.HasPrefix(msg, "Marking the daemon as busy"):
		return Event{Kind: KindBusyMark, TimestampMs: ts}, true
	case strings.HasPrefix(msg, "Marking the daemon as idle"):
		return Event{Kind: KindIdleMark, TimestampMs: ts}, true
	case strings.Contains(msg, "about to start building Build{"):
		ev := Event{Kind: KindBuildStart, TimestampMs: ts}
		if m := buildIDDirRE.FindStringSubmatch(msg); m != nil {
			ev.BuildID = strings.TrimSpace(m[1])
			ev.CurrentDir = strings.TrimSpace(m[2])
		}
		return ev, true
	case startingRE.MatchString(msg):
		return Event{Kind: KindBuildStart, TimestampMs: ts}, true
	case strings.Contains(msg, "Configuring env variables: ["):
		names := []string{}
		if m := envListRE.FindStringSubmatch(msg); m != nil {
			for _, part := range strings.Split(m[1], ",") {
				part = strings.TrimSpace(part)
				if part != "" {
					names = append(names, part)
				}
			}
		}
		return Event{Kind: KindBuildEnvNames, TimestampMs: ts, EnvNames: names}, true
	case strings.Contains(msg, "DefaultDaemonContext["):
		ev := Event{Kind: KindDaemonContext, TimestampMs: ts}
		if m := contextUIDRE.FindStringSubmatch(msg); m != nil {
			ev.UID = m[1]
		}
		if m := contextOptsRE.FindStringSubmatch(msg); m != nil {
			ev.DaemonOpts = m[1]
		}
		return ev, true
	default:
		return Event{}, false
	}
}

// ParseDuration mirrors Kotlin DaemonLogParser.parseDuration (seconds).
func ParseDuration(text string) float64 {
	t := strings.TrimSpace(text)
	if m := msDurRE.FindStringSubmatch(t); m != nil {
		ms, _ := strconv.ParseInt(m[1], 10, 64)
		return float64(ms) / 1000.0
	}
	seconds := 0.0
	if m := minDurRE.FindStringSubmatch(t); m != nil {
		mins, _ := strconv.ParseFloat(m[1], 64)
		seconds += mins * 60
	}
	if m := secDurRE.FindStringSubmatch(t); m != nil {
		secs, _ := strconv.ParseFloat(m[1], 64)
		seconds += secs
	}
	return seconds
}

func parseTimestamp(raw string) (int64, bool) {
	for _, layout := range []string{
		"2006-01-02T15:04:05.999999999Z0700",
		"2006-01-02T15:04:05Z0700",
	} {
		if t, err := time.Parse(layout, raw); err == nil {
			return t.UnixMilli(), true
		}
	}
	return 0, false
}
