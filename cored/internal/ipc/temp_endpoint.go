package ipc

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// TempEndpoint returns a short-lived local IPC endpoint for tests.
// On Windows it is a unique named pipe; elsewhere a Unix socket under TempDir.
func TempEndpoint(t testing.TB, name string) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		return fmt.Sprintf(`\\.\pipe\daemonitor-test-%s-%d`, name, os.Getpid())
	}
	dir, err := os.MkdirTemp("", "dmn-")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, name+".sock")
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	return path
}
