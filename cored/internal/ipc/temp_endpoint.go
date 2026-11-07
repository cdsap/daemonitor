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
	path := filepath.Join(os.TempDir(), fmt.Sprintf("dmn-%s-%d.sock", name, os.Getpid()))
	_ = os.Remove(path)
	t.Cleanup(func() { _ = os.Remove(path) })
	return path
}
