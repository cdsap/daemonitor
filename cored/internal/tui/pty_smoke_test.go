package tui_test

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
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
	if runtime.GOOS == "windows" {
		t.Skip("PTY smoke is unsupported on Windows")
	}

	bin := filepath.Join(t.TempDir(), "daemonitor-cli")
	build := exec.Command("go", "build", "-o", bin, "./cmd/daemonitor-cli")
	build.Dir = filepath.Join("..", "..") // cored/
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}

	coreBin := filepath.Join(t.TempDir(), "daemonitor-cored")
	coreBuild := exec.Command("go", "build", "-o", coreBin, "./cmd/daemonitor-cored")
	coreBuild.Dir = filepath.Join("..", "..") // cored/
	if out, err := coreBuild.CombinedOutput(); err != nil {
		t.Fatalf("core build: %v\n%s", err, out)
	}

	tempDir := t.TempDir()
	sock := filepath.Join(tempDir, "daemonitor.sock")
	db := filepath.Join(tempDir, "daemonitor.sqlite")
	core := exec.Command(coreBin, "-socket", sock, "-db", db, "-interval", "200ms")
	if err := core.Start(); err != nil {
		t.Fatalf("core start: %v", err)
	}
	defer func() {
		_ = core.Process.Kill()
		_ = core.Wait()
	}()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := os.Stat(sock); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("core socket was not created")
		}
		time.Sleep(50 * time.Millisecond)
	}

	cmd := exec.Command(bin, "top", "-socket", sock, "-poll-interval", "200ms")
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
