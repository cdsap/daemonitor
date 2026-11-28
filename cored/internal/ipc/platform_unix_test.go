//go:build !windows

package ipc_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/ipc"
)

func TestUnixListenRestrictsPermissions(t *testing.T) {
	for _, existing := range []bool{false, true} {
		t.Run(map[bool]string{false: "new", true: "existing"}[existing], func(t *testing.T) {
			// Short paths also fit macOS's Unix socket path limit.
			root, err := os.MkdirTemp("", "ipc-")
			if err != nil {
				t.Fatal(err)
			}
			defer os.RemoveAll(root)
			dir := filepath.Join(root, "data")
			endpoint := filepath.Join(dir, "core.sock")
			if existing {
				if err := os.Mkdir(dir, 0o755); err != nil {
					t.Fatal(err)
				}
				if err := os.Chmod(dir, 0o755); err != nil {
					t.Fatal(err)
				}
			}
			ln, err := ipc.Default.Listen(endpoint)
			if err != nil {
				t.Fatal(err)
			}
			defer ln.Close()
			for path, want := range map[string]os.FileMode{dir: 0o700, endpoint: 0o600} {
				info, err := os.Stat(path)
				if err != nil {
					t.Fatal(err)
				}
				if got := info.Mode().Perm(); got != want {
					t.Errorf("%s permissions=%o want %o", path, got, want)
				}
			}
		})
	}
}

func TestUnixListenRejectsSymlinkParentBeforeRemovingEndpoint(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "real")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "link")
	if err := os.Symlink(dir, link); err != nil {
		t.Fatal(err)
	}
	endpoint := filepath.Join(link, "core.sock")
	if err := os.WriteFile(endpoint, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	ln, err := ipc.Default.Listen(endpoint)
	if err == nil {
		ln.Close()
		t.Fatal("Listen accepted a symlink parent")
	}
	if data, err := os.ReadFile(endpoint); err != nil || string(data) != "keep" {
		t.Fatalf("endpoint changed before parent validation: %q, %v", data, err)
	}
}

func TestUnixListenRejectsDirectoryOwnedByAnotherUser(t *testing.T) {
	if os.Getuid() == 0 {
		t.Skip("filesystem root is owned by the current user")
	}
	// Reject the root-owned directory before chmod or any endpoint operation.
	ln, err := ipc.Default.Listen("/daemonitor-permission-test.sock")
	if err == nil {
		ln.Close()
		t.Fatal("Listen accepted a directory owned by another user")
	}
	if !strings.Contains(err.Error(), "owned by the current user") {
		t.Fatalf("expected ownership rejection, got %v", err)
	}
}
