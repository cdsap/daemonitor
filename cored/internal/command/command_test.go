package command

import (
	"strings"
	"testing"
	"time"
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
	opts, err := ParseArgs([]string{"--json", "--no-autostart", "--no-color", "ps"})
	if err != nil {
		t.Fatal(err)
	}
	if !opts.JSON || !opts.NoAutostart || !opts.NoColor {
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
