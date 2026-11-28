package bootstrap

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/ipc"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

const defaultWaitHealthy = 5 * time.Second

// Options control core discovery and optional autostart.
type Options struct {
	Socket     string
	DBPath     string
	Autostart  bool
	Wait       time.Duration
	FindBinary func() (string, error)
	StartCore  func(binary, socket, db string) error
	Stderr     io.Writer
}

// Result is a connected client plus whether this process started the core.
type Result struct {
	Client  *client.Client
	Socket  string
	Started bool
}

// Connect resolves the socket, optionally starts daemonitor-cored, and waits for health.
func Connect(ctx context.Context, opts Options) (Result, error) {
	socket := opts.Socket
	if socket == "" {
		socket = store.DefaultSocketPath()
	}
	dbPath := opts.DBPath
	if dbPath == "" {
		dbPath = store.DefaultWatcherDBPath()
	}
	wait := opts.Wait
	if wait <= 0 {
		wait = defaultWaitHealthy
	}
	stderr := opts.Stderr
	if stderr == nil {
		stderr = os.Stderr
	}
	find := opts.FindBinary
	if find == nil {
		find = FindCoredBinary
	}
	start := opts.StartCore
	if start == nil {
		start = StartCored
	}

	c := client.New(socket)
	if _, err := c.Health(ctx); err == nil {
		return Result{Client: c, Socket: socket}, nil
	}

	if !opts.Autostart {
		return Result{}, connectionError(socket, "core is not running (autostart disabled)", nil)
	}

	binary, err := find()
	if err != nil || binary == "" {
		return Result{}, connectionError(socket, "daemonitor-cored binary not found beside CLI or on PATH", err)
	}

	if ipc.NeedsEndpointDir(socket) {
		if err := os.MkdirAll(filepath.Dir(socket), 0o755); err != nil {
			return Result{}, connectionError(socket, "cannot create socket directory", err)
		}
	}

	fmt.Fprintf(stderr, "Starting daemonitor-cored (%s)…\n", binary)
	if err := start(binary, socket, dbPath); err != nil {
		return Result{}, connectionError(socket, "failed to start daemonitor-cored", err)
	}

	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		if _, err := c.Health(ctx); err == nil {
			return Result{Client: c, Socket: socket, Started: true}, nil
		}
		select {
		case <-ctx.Done():
			return Result{}, connectionError(socket, "", ctx.Err())
		case <-time.After(50 * time.Millisecond):
		}
	}
	return Result{}, connectionError(socket, "started daemonitor-cored but health check timed out", nil)
}

func connectionError(socket, detail string, cause error) error {
	const recovery = "Recovery: start daemonitor-cored manually, or reinstall so daemonitor-cored sits next to daemonitor-cli"
	switch {
	case cause != nil && detail != "":
		return fmt.Errorf("cannot connect to daemonitor-cored at %s: %s: %w\n%s", socket, detail, cause, recovery)
	case cause != nil:
		return fmt.Errorf("cannot connect to daemonitor-cored at %s: %w\n%s", socket, cause, recovery)
	default:
		return fmt.Errorf("cannot connect to daemonitor-cored at %s: %s\n%s", socket, detail, recovery)
	}
}

// FindCoredBinary locates a packaged or PATH-installed daemonitor-cored.
// Search order: sibling of this executable, then PATH.
func FindCoredBinary() (string, error) {
	name := coredBinaryName()
	var candidates []string

	if exe, err := os.Executable(); err == nil {
		exe, _ = filepath.EvalSymlinks(exe)
		dir := filepath.Dir(exe)
		candidates = append(candidates, filepath.Join(dir, name))
		// …/libexec/bin/cli → sibling cored in same bin dir (already covered)
		// also try parent/bin when nested (e.g. Homebrew libexec)
		candidates = append(candidates, filepath.Join(filepath.Dir(dir), "bin", name))
	}

	if pathEnv := os.Getenv("PATH"); pathEnv != "" {
		for _, dir := range filepath.SplitList(pathEnv) {
			if dir == "" {
				continue
			}
			candidates = append(candidates, filepath.Join(dir, name))
		}
	}

	for _, path := range candidates {
		if info, err := os.Stat(path); err == nil && !info.IsDir() {
			return path, nil
		}
	}
	return "", fmt.Errorf("%s not found", name)
}

const defaultRetention = 15 * 24 * time.Hour

func coredArgs(socket, db string) []string {
	args := []string{"-socket", socket, "-db", db, "-retention", defaultRetention.String()}
	if gradleHome := os.Getenv("GRADLE_USER_HOME"); gradleHome != "" {
		args = append(args, "-gradle-user-home", gradleHome)
	}
	return args
}

func coredBinaryName() string {
	if runtime.GOOS == "windows" {
		return "daemonitor-cored.exe"
	}
	return "daemonitor-cored"
}
