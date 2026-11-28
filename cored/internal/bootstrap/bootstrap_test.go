package bootstrap

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/api"
	"github.com/cdsap/daemonitor/cored/internal/ipctest"
)

func TestConnectAutostartDisabled(t *testing.T) {
	sock := ipctest.TempEndpoint(t, "missing")
	_, err := Connect(context.Background(), Options{
		Socket:    sock,
		Autostart: false,
	})
	if err == nil {
		t.Fatal("expected error")
	}
	msg := err.Error()
	if !strings.Contains(msg, sock) || !strings.Contains(msg, "Recovery") {
		t.Fatalf("error should include socket and recovery: %s", msg)
	}
}

func TestFindCoredBinaryEmpty(t *testing.T) {
	t.Setenv("PATH", t.TempDir())
	_, _ = FindCoredBinary()
}

func TestCoredArgsPropagatesGradleUserHome(t *testing.T) {
	t.Setenv("GRADLE_USER_HOME", filepath.Join(t.TempDir(), "custom-gradle"))
	args := coredArgs("/tmp/core.sock", "/tmp/watcher.db")
	want := []string{
		"-socket", "/tmp/core.sock",
		"-db", "/tmp/watcher.db",
		"-retention", defaultRetention.String(),
		"-gradle-user-home", os.Getenv("GRADLE_USER_HOME"),
	}
	if strings.Join(args, "\x00") != strings.Join(want, "\x00") {
		t.Fatalf("args=%v want %v", args, want)
	}
}

func TestCoredArgsIncludesDefaultRetention(t *testing.T) {
	t.Setenv("GRADLE_USER_HOME", "")
	args := coredArgs("/tmp/core.sock", "/tmp/watcher.db")
	found := false
	for i := 0; i+1 < len(args); i++ {
		if args[i] == "-retention" {
			found = true
			if args[i+1] != defaultRetention.String() {
				t.Fatalf("retention=%q want %q", args[i+1], defaultRetention.String())
			}
		}
	}
	if !found {
		t.Fatalf("missing -retention in %v", args)
	}
}

func TestConnectToRunningServer(t *testing.T) {
	sock := ipctest.TempEndpoint(t, "boot")
	db := filepath.Join(t.TempDir(), "watcher.db")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	srv, err := api.NewServer(sock, db, 200*time.Millisecond, time.Hour, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	errCh := make(chan error, 1)
	go func() { errCh <- srv.Run(ctx) }()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		res, err := Connect(context.Background(), Options{Socket: sock, Autostart: false, Wait: time.Second})
		if err == nil {
			h, herr := res.Client.Health(context.Background())
			if herr != nil {
				t.Fatal(herr)
			}
			if h.Status != "ok" {
				t.Fatalf("health=%+v", h)
			}
			cancel()
			return
		}
		select {
		case runErr := <-errCh:
			t.Fatalf("server exited early: %v", runErr)
		case <-time.After(50 * time.Millisecond):
		}
	}
	t.Fatal("server never became healthy")
}

func TestConnectWrapsErrorsAndUsesCustomStderr(t *testing.T) {
	sock := ipc.TempEndpoint(t, "wrap")
	startErr := errors.New("boom")
	var stderr bytes.Buffer
	_, err := Connect(context.Background(), Options{
		Socket:     sock,
		Autostart:  true,
		FindBinary: func() (string, error) { return "/fake/daemonitor-cored", nil },
		StartCore:  func(string, string, string) error { return startErr },
		Stderr:     &stderr,
	})
	if !errors.Is(err, startErr) {
		t.Fatalf("expected wrapped start error, got %v", err)
	}
	if !strings.Contains(stderr.String(), "Starting daemonitor-cored") {
		t.Fatalf("expected custom stderr output, got %q", stderr.String())
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = Connect(ctx, Options{
		Socket:     sock,
		Autostart:  true,
		Wait:       time.Second,
		FindBinary: func() (string, error) { return "/fake/daemonitor-cored", nil },
		StartCore:  func(string, string, string) error { return nil },
		Stderr:     &stderr,
	})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected wrapped context.Canceled, got %v", err)
	}
}
