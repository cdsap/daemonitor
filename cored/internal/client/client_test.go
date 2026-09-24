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

func TestClientProcessesOverUnixSocket(t *testing.T) {
	socket := filepath.Join(os.TempDir(), fmt.Sprintf("dmn-cli-client-%d.sock", os.Getpid()))
	db := filepath.Join(t.TempDir(), "core.sqlite")
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
	for {
		_, err := c.Health(context.Background())
		if err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("server never became ready: %v", err)
		}
		select {
		case runErr := <-errCh:
			t.Fatalf("server exited early: %v", runErr)
		case <-time.After(50 * time.Millisecond):
		}
	}

	snap, err := c.Processes(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if snap.Processes == nil {
		t.Fatal("expected non-nil processes slice")
	}
}
