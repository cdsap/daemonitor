package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/api"
	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/ipc"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestServerHealthAndProcessesOverUnixSocket(t *testing.T) {
	// Keep the endpoint short: macOS sun_path is ~104 bytes; Windows uses a named pipe.
	socket := ipc.TempEndpoint(t, "core")
	db := filepath.Join(t.TempDir(), "core.sqlite")

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
				return ipc.Default.Dial(ctx, socket)
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

	capResp, err := client.Get("http://daemonitor/v1/capabilities")
	if err != nil {
		t.Fatal(err)
	}
	defer capResp.Body.Close()
	var caps model.Capabilities
	if err := json.NewDecoder(capResp.Body).Decode(&caps); err != nil {
		t.Fatal(err)
	}
	if caps.SchemaVersion != 1 || len(caps.Metrics) == 0 {
		t.Fatalf("capabilities=%+v", caps)
	}
	for _, m := range caps.Metrics {
		if m.Availability == "" {
			t.Fatalf("metric %s missing availability", m.MetricName)
		}
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

func TestObservationSessionAPIRejectsMalformedAndUnknownRequests(t *testing.T) {
	socket := ipc.TempEndpoint(t, "sessions-errors")
	srv, err := api.NewServer(socket, filepath.Join(t.TempDir(), "core.sqlite"), time.Hour, time.Hour, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	errCh := make(chan error, 1)
	go func() { errCh <- srv.Run(ctx) }()
	client := &http.Client{Transport: &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return ipc.Default.Dial(ctx, socket)
	}}, Timeout: 3 * time.Second}
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
		time.Sleep(20 * time.Millisecond)
	}
	resp, err := client.Post("http://daemonitor/v1/observation-sessions/open", "application/json", bytes.NewBufferString("not-json"))
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("malformed open status=%d", resp.StatusCode)
	}
	resp, err = client.Post("http://daemonitor/v1/observation-sessions/close", "application/json", bytes.NewBufferString("{\"name\":\"missing\"}"))
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown close status=%d", resp.StatusCode)
	}
	cancel()
	select {
	case <-errCh:
	case <-time.After(3 * time.Second):
		t.Fatal("server did not shut down")
	}
}

func TestBuildsEndpointFiltersByDaemonAndRejectsInvalidIdentifiers(t *testing.T) {
	socket := ipc.TempEndpoint(t, "builds")
	db := filepath.Join(t.TempDir(), "core.sqlite")

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
			return ipc.Default.Dial(ctx, socket)
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
	t.Run("oversized query is bounded", func(t *testing.T) {
		payload := getBuilds(t, client, "?limit=999999")
		if payload.Count > 100 {
			t.Fatalf("unbounded payload count=%d", payload.Count)
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

func TestBuildsPIDQueryUsesCoreResolvedDaemonIdentity(t *testing.T) {
	gradleHome := t.TempDir()
	logDir := filepath.Join(gradleHome, "daemon", "8.9")
	if err := os.MkdirAll(logDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(logDir, "daemon-42.out.log"), []byte("2026-06-24T14:42:12.402-0700 [INFO] [x] DefaultDaemonContext[uid=current-uid, daemonOpts=-Xmx1g]\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	socket := ipc.TempEndpoint(t, "pid-builds")
	srv, err := api.NewServer(socket, filepath.Join(t.TempDir(), "core.sqlite"), time.Hour, time.Hour, gradleHome)
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Store.Close()
	for _, b := range []builds.Build{
		{BuildID: "old", DaemonPID: 42, DaemonIdentity: "old-uid", StartTimeMs: 1, FinalStatus: builds.StatusSuccess},
		{BuildID: "current", DaemonPID: 42, DaemonIdentity: "current-uid", StartTimeMs: 2, FinalStatus: builds.StatusSuccess},
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
		return ipc.Default.Dial(ctx, socket)
	}}, Timeout: 3 * time.Second}
	deadline := time.Now().Add(3 * time.Second)
	for {
		payload, requestErr := func() (buildsPayload, error) {
			resp, err := client.Get("http://daemonitor/v1/builds?pid=42")
			if err != nil {
				return buildsPayload{}, err
			}
			defer resp.Body.Close()
			var p buildsPayload
			err = json.NewDecoder(resp.Body).Decode(&p)
			return p, err
		}()
		if requestErr == nil {
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

func TestEventsEndpointReturnsLifecycleJSON(t *testing.T) {
	socket := ipc.TempEndpoint(t, "events")
	db := filepath.Join(t.TempDir(), "core.sqlite")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	srv, err := api.NewServer(socket, db, time.Hour, time.Hour, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.ObserveProcesses("api-host", 1000, []model.Process{{
		PID: 99, Type: "GRADLE_DAEMON", CommandLine: "GradleDaemon", StartTimeMs: 500,
	}}); err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.ObserveProcesses("api-host", 2000, nil); err != nil {
		t.Fatal(err)
	}

	errCh := make(chan error, 1)
	go func() { errCh <- srv.Run(ctx) }()

	client := &http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return ipc.Default.Dial(ctx, socket)
			},
		},
		Timeout: 3 * time.Second,
	}

	deadline := time.Now().Add(5 * time.Second)
	var payload model.Events
	for {
		resp, reqErr := client.Get("http://daemonitor/v1/events?since_ms=0&limit=50")
		if reqErr == nil {
			defer resp.Body.Close()
			if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
				t.Fatal(err)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("server never became ready: %v", reqErr)
		}
		select {
		case runErr := <-errCh:
			t.Fatalf("server exited early: %v", runErr)
		case <-time.After(50 * time.Millisecond):
		}
	}

	if payload.SchemaVersion != 1 || payload.Count < 2 || len(payload.Events) < 2 {
		t.Fatalf("events=%+v", payload)
	}
	types := map[string]int{}
	for _, ev := range payload.Events {
		types[ev.EventType]++
		if ev.SchemaVersion != 1 || ev.ProcessInstanceID == "" || ev.Source == "" {
			t.Fatalf("event=%+v", ev)
		}
	}
	if types["process.first_observed"] < 1 || types["process.disappearance_detected"] < 1 {
		t.Fatalf("missing lifecycle types: %v", types)
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

func TestBEPWatchIngestsBazelBuildsAndFiltersByTool(t *testing.T) {
	bepPath := filepath.Join(t.TempDir(), "bep.ndjson")
	body := `{"started":{"uuid":"bep-api-1","startTimeMillis":"1700000000000","command":"build","workingDirectory":"/work/app","workspaceDirectory":"/work/app","serverPid":"4242"}}` + "\n" +
		`{"finished":{"overallSuccess":true,"exitCode":{"name":"SUCCESS","code":0},"finishTimeMillis":"1700000003000"}}` + "\n"
	if err := os.WriteFile(bepPath, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("DAEMONITOR_BEP_WATCH", bepPath)

	socket := ipc.TempEndpoint(t, "bep")
	db := filepath.Join(t.TempDir(), "core.sqlite")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	srv, err := api.NewServer(socket, db, 50*time.Millisecond, time.Hour, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.InsertBuild(builds.Build{
		BuildID: "gradle-1", DaemonPID: 9, StartTimeMs: 1, InferredSource: builds.SourceTerminal,
		FinalStatus: builds.StatusSuccess,
	}); err != nil {
		t.Fatal(err)
	}
	errCh := make(chan error, 1)
	go func() { errCh <- srv.Run(ctx) }()
	client := &http.Client{
		Transport: &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return ipc.Default.Dial(ctx, socket)
		}},
		Timeout: 3 * time.Second,
	}
	deadline := time.Now().Add(10 * time.Second)
	var payload buildsPayload
	for {
		resp, err := client.Get("http://daemonitor/v1/builds?tool=bazel")
		if err == nil {
			_ = json.NewDecoder(resp.Body).Decode(&payload)
			_ = resp.Body.Close()
			if payload.Count == 1 {
				break
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("bazel build never ingested: payload=%+v err=%v", payload, err)
		}
		time.Sleep(50 * time.Millisecond)
	}
	got := payload.Builds[0]
	if got.BuildID != "bep-api-1" || got.Tool != builds.ToolBazel || got.InferredSource != builds.SourceBEP || got.FinalStatus != builds.StatusSuccess {
		t.Fatalf("build=%+v", got)
	}
	gradle := getBuilds(t, client, "?tool=GRADLE")
	if gradle.Count != 1 || gradle.Builds[0].BuildID != "gradle-1" || gradle.Builds[0].Tool != builds.ToolGradle {
		t.Fatalf("gradle payload=%+v", gradle)
	}
	if all := getBuilds(t, client, ""); all.Count != 2 {
		t.Fatalf("all payload=%+v", all)
	}
	cancel()
	select {
	case <-errCh:
	case <-time.After(3 * time.Second):
		t.Fatal("server did not shut down")
	}
}
