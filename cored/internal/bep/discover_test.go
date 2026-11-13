package bep_test

import (
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/bep"
)

func TestLooksLikeBazelClient(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{"/opt/homebrew/bin/bazel build //:hi", true},
		{"bazelisk test //...", true},
		{"env FOO=1 /usr/bin/bazelisk build //:x", true},
		{"java -jar /x/A-server.jar --product_name=Bazel --workspace_directory=/ws", false},
		{"gradle build", false},
		{"", false},
	}
	for _, tc := range cases {
		if got := bep.LooksLikeBazelClient(tc.in); got != tc.want {
			t.Fatalf("LooksLikeBazelClient(%q)=%v want %v", tc.in, got, tc.want)
		}
	}
}

func TestParseSinksFromArgv(t *testing.T) {
	cwd := "/tmp/ws"
	cmdline := `bazel build //:hi --build_event_json_file=bep.json --build_event_binary_file="/tmp/out/bep.bin"`
	sinks := bep.ParseSinksFromArgv(cmdline, cwd, 42)
	if len(sinks) != 2 {
		t.Fatalf("sinks=%v", sinks)
	}
	wantJSON := filepath.Join(cwd, "bep.json")
	if sinks[0].Path != wantJSON || sinks[0].Format != bep.FormatJSON || sinks[0].ClientPID != 42 {
		t.Fatalf("json sink=%+v want path %s", sinks[0], wantJSON)
	}
	if sinks[1].Path != "/tmp/out/bep.bin" || sinks[1].Format != bep.FormatBinary {
		t.Fatalf("binary sink=%+v", sinks[1])
	}
}

func TestParseSinksSkipsUnresolvedRelative(t *testing.T) {
	sinks := bep.ParseSinksFromArgv("bazel build --build_event_json_file=rel.json", "", 1)
	if len(sinks) != 0 {
		t.Fatalf("expected skip without cwd, got %v", sinks)
	}
}
