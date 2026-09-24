package client_test

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
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

func TestClientDaemonLogTail(t *testing.T) {
	socket := filepath.Join(os.TempDir(), fmt.Sprintf("dmn-cli-logtail-%d.sock", os.Getpid()))
	_ = os.Remove(socket)
	ln, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	defer os.Remove(socket)

	var (
		mu    sync.Mutex
		paths []string
	)
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/daemon-logs/", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		paths = append(paths, r.URL.Path)
		mu.Unlock()
		switch r.URL.Path {
		case "/v1/daemon-logs/42/tail":
			_, _ = w.Write([]byte(`{"pid":42,"gradle_version":"8.10.2","path":"/logs/daemon-42.out.log","lines":["a","b"],"events":[]}`))
		case "/v1/daemon-logs/43/tail":
			http.Error(w, "daemon log not found", http.StatusNotFound)
		default:
			http.Error(w, "boom", http.StatusInternalServerError)
		}
	})
	srv := &http.Server{Handler: mux}
	go func() { _ = srv.Serve(ln) }()
	defer srv.Close()

	c := client.New(socket)
	ctx := context.Background()

	tail, err := c.DaemonLogTail(ctx, 42)
	if err != nil {
		t.Fatal(err)
	}
	if tail == nil || tail.GradleVersion != "8.10.2" || tail.Path != "/logs/daemon-42.out.log" || len(tail.Lines) != 2 {
		t.Fatalf("unexpected tail: %#v", tail)
	}

	tail, err = c.DaemonLogTail(ctx, 43)
	if err != nil || tail != nil {
		t.Fatalf("404 should mean no log: tail=%#v err=%v", tail, err)
	}

	_, err = c.DaemonLogTail(ctx, 44)
	var httpErr *client.HTTPError
	if !errors.As(err, &httpErr) || httpErr.StatusCode != http.StatusInternalServerError {
		t.Fatalf("expected HTTP 500 error, got %v", err)
	}

	want := []string{"/v1/daemon-logs/42/tail", "/v1/daemon-logs/43/tail", "/v1/daemon-logs/44/tail"}
	mu.Lock()
	defer mu.Unlock()
	if strings.Join(paths, ",") != strings.Join(want, ",") {
		t.Fatalf("paths=%v", paths)
	}
}
