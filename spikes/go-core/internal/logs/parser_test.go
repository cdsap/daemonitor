package logs_test

import (
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/logs"
)

// Fixtures authored from a real daemon-*.out.log (Gradle 8.10.2) — mirrors DaemonLogParserTest.kt.
const (
	busy       = "2026-06-24T14:42:12.464-0700 [INFO] [org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as busy, address: [c6fa port:62608]"
	buildStart = "2026-06-24T14:42:12.465-0700 [INFO] [org.gradle.launcher.daemon.server.exec.StartBuildOrRespondWithBusy] Daemon is about to start building Build{id=cb67ca7c-3264-4db5-9e32-cfe689c685c0, currentDir=/Users/ivillar/personal/gradle_watcher}. Dispatching build started information..."
	envLine    = "2026-06-24T14:42:12.466-0700 [DEBUG] [org.gradle.launcher.daemon.server.exec.EstablishBuildEnvironment] Configuring env variables: [PATH, CLAUDECODE, AI_AGENT, TERM_PROGRAM, HOME]"
	outcome    = "BUILD SUCCESSFUL in 38s"
	idle       = "2026-06-24T14:42:50.327-0700 [INFO] [org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as idle, address: [c6fa port:62608]"
	context    = "2026-06-24T14:42:12.402-0700 [INFO] [org.gradle.launcher.daemon.server.Daemon] start() called on daemon - DefaultDaemonContext[uid=78415476-2316-475f-82b3-69a16a06e3d0,javaHome=/x,javaVersion=21,daemonOpts=-Xmx512m,-Dfile.encoding=UTF-8]"
)

func TestParseOneFullBuildInOrder(t *testing.T) {
	events := logs.ParseLines([]string{context, busy, buildStart, envLine, outcome, idle})
	if len(events) < 6 {
		t.Fatalf("events=%d %+v", len(events), events)
	}
	if events[0].Kind != logs.KindDaemonContext {
		t.Fatalf("first=%s", events[0].Kind)
	}
	var start *logs.Event
	var sawBusy, sawOutcome bool
	for i := range events {
		ev := &events[i]
		switch ev.Kind {
		case logs.KindBusyMark:
			sawBusy = true
		case logs.KindBuildStart:
			start = ev
		case logs.KindOutcome:
			sawOutcome = true
			if ev.Success == nil || !*ev.Success {
				t.Fatal("expected successful outcome")
			}
		}
	}
	if !sawBusy || start == nil || !sawOutcome {
		t.Fatalf("missing markers: busy=%v start=%v outcome=%v", sawBusy, start != nil, sawOutcome)
	}
	if start.CurrentDir != "/Users/ivillar/personal/gradle_watcher" {
		t.Fatalf("currentDir=%q", start.CurrentDir)
	}
	if start.BuildID != "cb67ca7c-3264-4db5-9e32-cfe689c685c0" {
		t.Fatalf("buildId=%q", start.BuildID)
	}
	if events[len(events)-1].Kind != logs.KindIdleMark {
		t.Fatalf("last=%s", events[len(events)-1].Kind)
	}
}

func TestBareOutcomeLine(t *testing.T) {
	ev, ok := logs.ParseLine("BUILD FAILED in 1m 2s")
	if !ok || ev.Kind != logs.KindOutcome {
		t.Fatalf("got %+v ok=%v", ev, ok)
	}
	if ev.Success == nil || *ev.Success {
		t.Fatal("expected failure")
	}
	if ev.DurationSeconds == nil || *ev.DurationSeconds != 62.0 {
		t.Fatalf("duration=%v", ev.DurationSeconds)
	}
}

func TestEnvVarNames(t *testing.T) {
	ev, ok := logs.ParseLine(envLine)
	if !ok || ev.Kind != logs.KindBuildEnvNames {
		t.Fatalf("got %+v", ev)
	}
	joined := strings.Join(ev.EnvNames, ",")
	if !strings.Contains(joined, "CLAUDECODE") || !strings.Contains(joined, "TERM_PROGRAM") {
		t.Fatalf("env=%v", ev.EnvNames)
	}
}

func TestDaemonContextUID(t *testing.T) {
	ev, ok := logs.ParseLine(context)
	if !ok || ev.Kind != logs.KindDaemonContext {
		t.Fatalf("got %+v", ev)
	}
	if ev.UID != "78415476-2316-475f-82b3-69a16a06e3d0" {
		t.Fatalf("uid=%q", ev.UID)
	}
}

func TestParseDurationShapes(t *testing.T) {
	cases := []struct {
		in   string
		want float64
	}{
		{"280ms", 0.28},
		{"7s", 7},
		{"1m 2s", 62},
		{"2m", 120},
	}
	for _, tc := range cases {
		got := logs.ParseDuration(tc.in)
		if got != tc.want {
			t.Fatalf("%s: got %v want %v", tc.in, got, tc.want)
		}
	}
}

func TestNonMatchingIgnored(t *testing.T) {
	if _, ok := logs.ParseLine("2026-06-24T14:42:12.470-0700 [DEBUG] [Foo] something unrelated"); ok {
		t.Fatal("expected ignore")
	}
	if _, ok := logs.ParseLine("garbage"); ok {
		t.Fatal("expected ignore")
	}
}
