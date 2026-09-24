package client_test

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/api"
	"github.com/cdsap/daemonitor/cored/internal/client"
)

func TestClientProcessesAgainstLiveServer(t *testing.T) {
	// Keep the socket path short: macOS sun_path is ~104 bytes.
	socket := filepath.Join(os.TempDir(), fmt.Sprintf("dmn-cli-%d.sock", os.Getpid()))
	db := filepath.Join(t.TempDir(), "watcher.db")
	_ = os.Remove(socket)
	defer os.Remove(socket)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	srv, err := api.NewServer(socket, db, 200*time.Millisecond, time.Hour, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	errCh := make(chan error, 1)
	go func() { errCh <- srv.Run(ctx) }()

	c := client.New(socket)
	deadline := time.Now().Add(5 * time.Second)
	var last error
	for time.Now().Before(deadline) {
		snap, err := c.Processes(context.Background())
		if err == nil {
			if snap.Processes == nil {
				t.Fatal("expected non-nil processes slice")
			}
			h, err := c.Health(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			if h.Status != "ok" {
				t.Fatalf("health=%+v", h)
			}
			cancel()
			return
		}
		last = err
		select {
		case runErr := <-errCh:
			t.Fatalf("server exited early: %v", runErr)
		case <-time.After(50 * time.Millisecond):
		}
	}
	t.Fatalf("never connected: %v", last)
}
