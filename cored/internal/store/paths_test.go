package store

import (
	"path/filepath"
	"testing"
)

func TestDefaultSocketPathSelection(t *testing.T) {
	if got := defaultSocketPath("windows", `C:\Users\x\AppData\Local\Daemonitor`); got != `\\.\pipe\daemonitor-core` {
		t.Fatalf("windows=%q", got)
	}
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
