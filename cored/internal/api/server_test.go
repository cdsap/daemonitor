package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/api"
	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestServerHealthAndProcessesOverUnixSocket(t *testing.T) {
	// Keep the socket path short: macOS sun_path is ~104 bytes and t.TempDir() paths are often longer.
	socket := filepath.Join(os.TempDir(), fmt.Sprintf("dmn-core-%d.sock", os.Getpid()))
	db := filepath.Join(t.TempDir(), "core.sqlite")
	_ = os.Remove(socket)
	defer os.Remove(socket)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	srv, err := api.NewServer(socket, db, 200*time.Millisecond, time.Hour, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for _, b := range []builds.Build{
		{BuildID: "selected", DaemonPID: 42, FinalStatus: builds.StatusSuccess},
		{BuildID: "other", DaemonPID: 7, FinalStatus: builds.StatusSuccess},
	} {
		if err := srv.Store.InsertBuild(b); err != nil {
			t.Fatal(err)
		}
	}

	errCh := make(chan error, 1)
	go func() {
		errCh <- srv.Run(ctx)
	}()

	client := &http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", socket)
			},
		},
		Timeout: 3 * time.Second,
	}

	deadline := time.Now().Add(5 * time.Second)
	var health model.Health
	for {
		resp, err := client.Get("http://daemonitor/v1/health")
		if err == nil {
			defer resp.Body.Close()
			if err := json.NewDecoder(resp.Body).Decode(&health); err != nil {
				t.Fatal(err)
			}
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

	if health.Status != "ok" {
		t.Fatalf("health=%+v", health)
	}

	resp, err := client.Get("http://daemonitor/v1/processes")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var snap model.Snapshot
	if err := json.NewDecoder(resp.Body).Decode(&snap); err != nil {
		t.Fatal(err)
	}
	if snap.Processes == nil {
		t.Fatal("expected processes array (possibly empty), got null")
	}

	resp, err = client.Get("http://daemonitor/v1/builds?pid=42")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var buildsPayload struct {
		Count  int `json:"count"`
		Builds []struct {
			DaemonPID int64 `json:"daemon_pid"`
		} `json:"builds"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&buildsPayload); err != nil {
		t.Fatal(err)
	}
	if buildsPayload.Count != 1 || len(buildsPayload.Builds) != 1 || buildsPayload.Builds[0].DaemonPID != 42 {
		t.Fatalf("filtered builds=%+v", buildsPayload)
	}

	cancel()
	select {
	case <-errCh:
	case <-time.After(3 * time.Second):
		t.Fatal("server did not shut down")
	}
}
