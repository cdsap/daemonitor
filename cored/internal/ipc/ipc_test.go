package ipc_test

import (
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/ipc"
)

func TestWindowsPipePathForUserSID(t *testing.T) {
	got := ipc.WindowsPipePathForUserSID(`S-1-5-21-1-2-3-1001`)
	want := `\\.\pipe\daemonitor-core-S-1-5-21-1-2-3-1001`
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	if ipc.WindowsPipePathForUserSID("") != ipc.DefaultWindowsPipePath {
		t.Fatal("empty SID should fall back to legacy name")
	}
	if !ipc.IsNamedPipe(got) {
		t.Fatalf("expected named pipe path %q", got)
	}
}
