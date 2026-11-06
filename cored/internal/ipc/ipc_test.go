package ipc

import (
	"path/filepath"
	"testing"
)

func TestIsNamedPipe(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{`\\.\pipe\daemonitor-core`, true},
		{`\\.\PIPE\Daemonitor-Core`, true},
		{`//./pipe/daemonitor-core`, true},
		{`/tmp/daemonitor-core.sock`, false},
		{`C:\Users\x\AppData\Local\Daemonitor\daemonitor-core.sock`, false},
		{"", false},
	}
	for _, tc := range cases {
		if got := IsNamedPipe(tc.in); got != tc.want {
			t.Fatalf("IsNamedPipe(%q)=%v want %v", tc.in, got, tc.want)
		}
	}
}

func TestNeedsEndpointDir(t *testing.T) {
	if NeedsEndpointDir(`\\.\pipe\daemonitor-core`) {
		t.Fatal("named pipe must not need a filesystem parent dir")
	}
	if !NeedsEndpointDir(`/tmp/daemonitor-core.sock`) {
		t.Fatal("unix socket path needs a parent dir")
	}
	if NeedsEndpointDir("") {
		t.Fatal("empty endpoint needs no dir")
	}
}

func TestScheme(t *testing.T) {
	if Scheme(`\\.\pipe\daemonitor-core`) != "npipe" {
		t.Fatal(Scheme(`\\.\pipe\daemonitor-core`))
	}
	if Scheme(filepath.Join("tmp", "daemonitor-core.sock")) != "unix" {
		t.Fatal(Scheme("sock"))
	}
}

func TestPipeSDDLForUserSID(t *testing.T) {
	got := pipeSDDLForUserSID("S-1-5-21-1-2-3-1001")
	want := "D:P(A;;GA;;;S-1-5-21-1-2-3-1001)"
	if got != want {
		t.Fatalf("sddl=%q want %q", got, want)
	}
}

func TestDefaultImplementsTransport(t *testing.T) {
	var _ Transport = Default
}
