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

func TestBuildsPIDQueryUsesCoreResolvedDaemonIdentity(t *testing.T) {
	gradleHome := t.TempDir()
	logDir := filepath.Join(gradleHome, "daemon", "8.9")
	if err := os.MkdirAll(logDir, 0o700); err != nil {
		t.Fatal(err)
	}
	logPath := filepath.Join(logDir, "daemon-42.out.log")
	if err := os.WriteFile(logPath, []byte("2026-06-24T14:42:12.402-0700 [INFO] [x] DefaultDaemonContext[uid=current-uid, daemonOpts=-Xmx1g]\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	srv, err := api.NewServer(filepath.Join(os.TempDir(), fmt.Sprintf("dmn-builds-%d.sock", os.Getpid())), filepath.Join(t.TempDir(), "core.sqlite"), time.Hour, time.Hour, gradleHome)
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Store.Close()
	defer os.Remove(srv.SocketPath)
	for _, b := range []builds.Build{
		{BuildID: "old", DaemonPID: 42, DaemonIdentity: "old-uid", StartTimeMs: 1, InferredSource: builds.SourceUnknown, FinalStatus: builds.StatusSuccess},
		{BuildID: "current", DaemonPID: 42, DaemonIdentity: "current-uid", StartTimeMs: 2, InferredSource: builds.SourceUnknown, FinalStatus: builds.StatusSuccess},
	} {
		if err := srv.Store.InsertBuild(b); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := srv.Logs.Poll(nil); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	errCh := make(chan error, 1)
	go func() { errCh <- srv.Run(ctx) }()

	client := &http.Client{Transport: &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", srv.SocketPath)
	}}, Timeout: 3 * time.Second}
	deadline := time.Now().Add(3 * time.Second)
	for {
		resp, requestErr := client.Get("http://daemonitor/v1/builds?pid=42")
		if requestErr == nil {
			defer resp.Body.Close()
			var payload struct {
				Builds []builds.Build `json:"builds"`
			}
			if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
				t.Fatal(err)
			}
			if len(payload.Builds) != 1 || payload.Builds[0].BuildID != "current" {
				t.Fatalf("scoped API builds=%v", payload.Builds)
			}
			cancel()
			<-errCh
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("server never became ready: %v", requestErr)
		}
		time.Sleep(20 * time.Millisecond)
	}
}
