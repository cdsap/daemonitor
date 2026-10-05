package command

import (
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestParseArgsDefaults(t *testing.T) {
	opts, err := ParseArgs(nil)
	if err != nil {
		t.Fatal(err)
	}
	if opts.PollInterval != 2*time.Second {
		t.Fatalf("interval=%v", opts.PollInterval)
	}
	if opts.Socket == "" {
		t.Fatal("expected default socket")
	}
}

func TestParseArgsFlags(t *testing.T) {
	opts, err := ParseArgs([]string{"--json", "--no-autostart", "--no-color", "--details", "ps"})
	if err != nil {
		t.Fatal(err)
	}
	if !opts.JSON || !opts.NoAutostart || !opts.NoColor || !opts.Details {
		t.Fatalf("flags not set: %+v", opts)
	}
	if len(opts.Args) != 1 || opts.Args[0] != "ps" {
		t.Fatalf("args=%v", opts.Args)
	}
}

func TestParseArgsFlagsAfterCommand(t *testing.T) {
	opts, err := ParseArgs([]string{"ps", "--json", "--socket", "/tmp/x.sock", "--no-autostart"})
	if err != nil {
		t.Fatal(err)
	}
	if !opts.JSON || !opts.NoAutostart || opts.Socket != "/tmp/x.sock" {
		t.Fatalf("flags after command: %+v", opts)
	}
	if len(opts.Args) != 1 || opts.Args[0] != "ps" {
		t.Fatalf("args=%v", opts.Args)
	}
}

func TestUsageMentionsCommands(t *testing.T) {
	u := Usage()
	for _, needle := range []string{"top", "ps", "health", "--json", "--no-autostart"} {
		if !strings.Contains(u, needle) {
			t.Fatalf("usage missing %q", needle)
		}
	}
}

func TestParseArgsQueryAndWatchOptions(t *testing.T) {
	opts, err := ParseArgs([]string{"builds", "--limit", "25", "--pid", "42", "--project", "demo", "--status", "FAILED", "--format", "jsonl"})
	if err != nil {
		t.Fatal(err)
	}
	if opts.Limit != 25 || opts.PID != 42 || opts.Project != "demo" || opts.Status != "FAILED" || opts.Output != "jsonl" {
		t.Fatalf("unexpected query options: %+v", opts)
	}

	opts, err = ParseArgs([]string{"ps", "--watch", "--until", "1m", "--poll-interval", "5s"})
	if err != nil {
		t.Fatal(err)
	}
	if !opts.Watch || opts.Until != time.Minute || opts.PollInterval != 5*time.Second {
		t.Fatalf("unexpected watch options: %+v", opts)
	}
	opts, err = ParseArgs([]string{"ps", "--watch", "--fail-on-rss", "8192"})
	if err != nil || opts.FailOnRSS != 8192 {
		t.Fatalf("unexpected RSS threshold: %+v, err=%v", opts, err)
	}
	opts, err = ParseArgs([]string{"builds", "--include-logs"})
	if err != nil || !opts.IncludeLogs {
		t.Fatalf("unexpected log option: %+v, err=%v", opts, err)
	}
}

func TestParseArgsRejectsInvalidOutputAndNegativeValues(t *testing.T) {
	for _, argv := range [][]string{
		{"ps", "--format", "xml"},
		{"history", "--limit", "-1"},
		{"ps", "--until", "-1s"},
	} {
		if _, err := ParseArgs(argv); err == nil {
			t.Fatalf("expected ParseArgs(%v) to fail", argv)
		}
	}
}

func TestFiltersApplyToProcesses(t *testing.T) {
	project := "/work/daemonitor"
	processes := filterProcesses([]model.Process{
		{PID: 1, ProjectPath: &project},
		{PID: 2, ProjectPath: strPtr("/work/other")},
	}, Options{Project: "DAEMONITOR"})
	if len(processes) != 1 || processes[0].PID != 1 {
		t.Fatalf("process filter=%+v", processes)
	}

}

func strPtr(s string) *string { return &s }
