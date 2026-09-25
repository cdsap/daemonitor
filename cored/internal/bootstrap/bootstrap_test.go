package bootstrap

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/api"
)

func TestConnectAutostartDisabled(t *testing.T) {
	sock := filepath.Join(os.TempDir(), fmt.Sprintf("dmn-missing-%d.sock", os.Getpid()))
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
	want := []string{"-socket", "/tmp/core.sock", "-db", "/tmp/watcher.db", "-gradle-user-home", os.Getenv("GRADLE_USER_HOME")}
	if strings.Join(args, "\x00") != strings.Join(want, "\x00") {
		t.Fatalf("args=%v want %v", args, want)
	}
}

func TestConnectToRunningServer(t *testing.T) {
	sock := filepath.Join(os.TempDir(), fmt.Sprintf("dmn-boot-%d.sock", os.Getpid()))
	db := filepath.Join(t.TempDir(), "watcher.db")
	_ = os.Remove(sock)
	defer os.Remove(sock)

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
