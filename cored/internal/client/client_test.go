package client_test

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/api"
	"github.com/cdsap/daemonitor/cored/internal/client"
)

type recordingTransport struct {
	request *http.Request
}

func (t *recordingTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	t.request = r
	return &http.Response{
		StatusCode: http.StatusOK,
		Body:       io.NopCloser(strings.NewReader(`{"count":0,"builds":[]}`)),
		Header:     make(http.Header),
		Request:    r,
	}, nil
}

func TestBuildsForDaemonRequestsPIDAndStartTime(t *testing.T) {
	transport := &recordingTransport{}
	c := &client.Client{HTTP: &http.Client{Transport: transport}}
	if _, err := c.BuildsForDaemon(context.Background(), 9, 200, 20); err != nil {
		t.Fatal(err)
	}
	if got := transport.request.URL.RawQuery; got != "pid=9&start_time_ms=200&limit=20" {
		t.Fatalf("query=%q", got)
	}
}

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
