package store

import (
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/ipc"
)

func TestDefaultSocketPathSelection(t *testing.T) {
	got := defaultSocketPath("windows", `C:\Users\x\AppData\Local\Daemonitor`)
	if !ipc.IsNamedPipe(got) {
		t.Fatalf("windows=%q want named pipe", got)
	}
	// On non-Windows hosts DefaultWindowsEndpoint returns the legacy constant;
	// on Windows it is SID-suffixed. Either form is a valid pipe path.
	_ = ipc.DefaultWindowsPipePath
	unix := defaultSocketPath("darwin", "/Users/x/Library/Application Support/Daemonitor")
	if filepath.Base(unix) != "daemonitor-core.sock" {
		t.Fatalf("darwin=%q", unix)
	}
	if filepath.Dir(unix) != "/Users/x/Library/Application Support/Daemonitor" {
		t.Fatalf("darwin dir=%q", filepath.Dir(unix))
	}
	linux := defaultSocketPath("linux", "/home/x/.local/share/Daemonitor")
	if filepath.Base(linux) != "daemonitor-core.sock" {
		t.Fatalf("linux=%q", linux)
	}
}
