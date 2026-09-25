package bootstrap

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/client"
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
	Stderr     *os.File
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
		return Result{}, connectionError(socket, "core is not running (autostart disabled)")
	}

	binary, err := find()
	if err != nil || binary == "" {
		return Result{}, connectionError(socket, "daemonitor-cored binary not found beside CLI or on PATH")
	}

	if err := os.MkdirAll(filepath.Dir(socket), 0o755); err != nil {
		return Result{}, connectionError(socket, fmt.Sprintf("cannot create socket directory: %v", err))
	}

	fmt.Fprintf(stderr, "Starting daemonitor-cored (%s)…\n", binary)
	if err := start(binary, socket, dbPath); err != nil {
		return Result{}, connectionError(socket, fmt.Sprintf("failed to start daemonitor-cored: %v", err))
	}

	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		if _, err := c.Health(ctx); err == nil {
			return Result{Client: c, Socket: socket, Started: true}, nil
		}
		select {
		case <-ctx.Done():
			return Result{}, connectionError(socket, ctx.Err().Error())
		case <-time.After(50 * time.Millisecond):
		}
	}
	return Result{}, connectionError(socket, "started daemonitor-cored but health check timed out")
}

func connectionError(socket, detail string) error {
	return fmt.Errorf(
		"cannot connect to daemonitor-cored at %s: %s\n"+
			"Recovery: start daemonitor-cored manually, or reinstall so daemonitor-cored sits next to daemonitor-cli",
		socket, detail,
	)
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
		sep := string(os.PathListSeparator)
		for _, dir := range strings.Split(pathEnv, sep) {
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

func coredArgs(socket, db string) []string {
	args := []string{"-socket", socket, "-db", db}
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
