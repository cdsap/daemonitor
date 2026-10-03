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

	cancel()
	select {
	case <-errCh:
	case <-time.After(3 * time.Second):
		t.Fatal("server did not shut down")
	}
}

func TestBuildsEndpointFiltersByDaemonAndRejectsInvalidIdentifiers(t *testing.T) {
	socket := filepath.Join(os.TempDir(), fmt.Sprintf("dmn-builds-%d.sock", os.Getpid()))
	db := filepath.Join(t.TempDir(), "core.sqlite")
	_ = os.Remove(socket)
	defer os.Remove(socket)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	srv, err := api.NewServer(socket, db, time.Hour, time.Hour, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for _, b := range []builds.Build{
		{BuildID: "other-daemon", DaemonPID: 7, DaemonIdentity: "uid-a", StartTimeMs: 500, FinalStatus: builds.StatusSuccess},
		{BuildID: "old", DaemonPID: 42, DaemonIdentity: "uid-a", StartTimeMs: 100, FinalStatus: builds.StatusSuccess},
		{BuildID: "new", DaemonPID: 42, DaemonIdentity: "uid-a", StartTimeMs: 300, FinalStatus: builds.StatusSuccess},
		{BuildID: "other-identity", DaemonPID: 42, DaemonIdentity: "uid-b", StartTimeMs: 400, FinalStatus: builds.StatusSuccess},
	} {
		if err := srv.Store.InsertBuild(b); err != nil {
			t.Fatal(err)
		}
	}

	errCh := make(chan error, 1)
	go func() { errCh <- srv.Run(ctx) }()
	client := &http.Client{
		Transport: &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", socket)
		}},
		Timeout: 3 * time.Second,
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		resp, err := client.Get("http://daemonitor/v1/health")
		if err == nil {
			_ = resp.Body.Close()
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("server never became ready: %v", err)
		}
		select {
		case runErr := <-errCh:
			t.Fatalf("server exited early: %v", runErr)
		case <-time.After(20 * time.Millisecond):
		}
	}

	t.Run("global query remains newest first", func(t *testing.T) {
		payload := getBuilds(t, client, "?limit=2")
		if payload.Count != 2 || len(payload.Builds) != 2 || payload.Builds[0].BuildID != "other-daemon" {
			t.Fatalf("payload=%+v", payload)
		}
	})
	t.Run("daemon query filters and limits", func(t *testing.T) {
		payload := getBuilds(t, client, "?daemon_pid=42&daemon_identity=uid-a&limit=1")
		if payload.Count != 1 || len(payload.Builds) != 1 || payload.Builds[0].BuildID != "new" {
			t.Fatalf("payload=%+v", payload)
		}
	})
	t.Run("empty daemon query", func(t *testing.T) {
		payload := getBuilds(t, client, "?daemon_pid=99&daemon_identity=missing")
		if payload.Count != 0 || payload.Builds == nil {
			t.Fatalf("payload=%+v", payload)
		}
	})
	for _, query := range []string{
		"?daemon_pid=not-a-pid&daemon_identity=uid-a",
		"?daemon_pid=42",
		"?daemon_pid=42&daemon_identity=%20%20",
	} {
		resp, err := client.Get("http://daemonitor/v1/builds" + query)
		if err != nil {
			t.Fatal(err)
		}
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("query %q status=%d", query, resp.StatusCode)
		}
	}

	cancel()
	select {
	case <-errCh:
	case <-time.After(3 * time.Second):
		t.Fatal("server did not shut down")
	}
}

type buildsPayload struct {
	Count  int            `json:"count"`
	Builds []builds.Build `json:"builds"`
}

func getBuilds(t *testing.T, client *http.Client, query string) buildsPayload {
	t.Helper()
	resp, err := client.Get("http://daemonitor/v1/builds" + query)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status=%d", resp.StatusCode)
	}
	var payload buildsPayload
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		t.Fatal(err)
	}
	return payload
}
