package tui_test

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/creack/pty"
)

// TestPTYSmokeStartAndQuit builds daemonitor-cli, starts it under a PTY, and
// verifies that pressing q exits without requiring Enter.
func TestPTYSmokeStartAndQuit(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping PTY smoke in short mode")
	}

	bin := filepath.Join(t.TempDir(), "daemonitor-cli")
	build := exec.Command("go", "build", "-o", bin, "./cmd/daemonitor-cli")
	build.Dir = filepath.Join("..", "..") // cored/
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}

	sock := filepath.Join(t.TempDir(), "missing.sock")
	cmd := exec.Command(bin, "top", "-socket", sock, "-poll-interval", "200ms", "-no-autostart")
	cmd.Env = append(os.Environ(), "TERM=xterm-256color")

	ptmx, err := pty.Start(cmd)
	if err != nil {
		t.Fatalf("pty start: %v", err)
	}
	defer func() { _ = ptmx.Close() }()
	_ = pty.Setsize(ptmx, &pty.Winsize{Rows: 24, Cols: 100})

	// Give the TUI a moment to enter alt screen, then quit with a single key.
	time.Sleep(300 * time.Millisecond)
	if _, err := ptmx.Write([]byte("q")); err != nil {
		t.Fatalf("write q: %v", err)
	}

	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("cli exited with error: %v", err)
		}
	case <-time.After(5 * time.Second):
		_ = cmd.Process.Kill()
		t.Fatal("timed out waiting for q to exit")
	}
}
