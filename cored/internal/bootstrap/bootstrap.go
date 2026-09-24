// Package bootstrap discovers and optionally starts daemonitor-cored.
package bootstrap

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

const healthWait = 5 * time.Second

// Options control core discovery / autostart.
type Options struct {
	Socket    string
	Autostart bool
	DBPath    string
}

// Error is a connection failure with socket path and recovery hint.
type Error struct {
	Socket string
	Err    error
	Hint   string
}

func (e *Error) Error() string {
	msg := fmt.Sprintf("cannot connect to daemonitor-cored at %s: %v", e.Socket, e.Err)
	if e.Hint != "" {
		msg = msg + "\n" + e.Hint
	}
	return msg
}

func (e *Error) Unwrap() error { return e.Err }

// EnsureCore verifies the core is healthy, optionally starting a sibling binary.
func EnsureCore(opts Options) error {
	socket := opts.Socket
	if socket == "" {
		socket = store.DefaultSocketPath()
	}
	c := client.New(socket)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, err := c.Health(ctx); err == nil {
		return nil
	} else if !opts.Autostart {
		return &Error{
			Socket: socket,
			Err:    err,
			Hint:   "Start daemonitor-cored, check --socket, or omit --no-autostart so the CLI can launch a sibling binary.",
		}
	}

	binary, findErr := FindCoredBinary()
	if findErr != nil {
		return &Error{
			Socket: socket,
			Err:    findErr,
			Hint:   "Install or place daemonitor-cored next to daemonitor-cli, or start it manually.",
		}
	}
	dbPath := opts.DBPath
	if dbPath == "" {
		dbPath = store.DefaultWatcherDBPath()
	}
	if err := startCored(binary, socket, dbPath); err != nil {
		return &Error{
			Socket: socket,
			Err:    err,
			Hint:   "Failed to start daemonitor-cored; start it manually and retry.",
		}
	}
	if err := waitHealthy(socket, healthWait); err != nil {
		return &Error{
			Socket: socket,
			Err:    err,
			Hint:   "daemonitor-cored started but did not become healthy; check the socket path and core logs.",
		}
	}
	return nil
}

// FindCoredBinary locates a sibling or PATH-installed daemonitor-cored.
func FindCoredBinary() (string, error) {
	name := "daemonitor-cored"
	if runtime.GOOS == "windows" {
		name = "daemonitor-cored.exe"
	}
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		for _, cand := range []string{
			filepath.Join(dir, name),
			filepath.Join(dir, "..", "libexec", name),
			filepath.Join(dir, "..", "resources", name),
		} {
			if isExecutable(cand) {
				return filepath.Clean(cand), nil
			}
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		return p, nil
	}
	return "", fmt.Errorf("daemonitor-cored binary not found")
}

func startCored(binary, socket, dbPath string) error {
	if err := os.MkdirAll(filepath.Dir(socket), 0o755); err != nil {
		return err
	}
	cmd := exec.Command(binary, "-socket", socket, "-db", dbPath)
	cmd.Stdout = nil
	cmd.Stderr = nil
	cmd.Stdin = nil
	return cmd.Start()
}

func waitHealthy(socket string, timeout time.Duration) error {
	c := client.New(socket)
	deadline := time.Now().Add(timeout)
	var last error
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		_, err := c.Health(ctx)
		cancel()
		if err == nil {
			return nil
		}
		last = err
		time.Sleep(50 * time.Millisecond)
	}
	if last == nil {
		last = fmt.Errorf("health check timed out")
	}
	return last
}

func isExecutable(path string) bool {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		return false
	}
	if runtime.GOOS == "windows" {
		return true
	}
	return info.Mode()&0o111 != 0
}
