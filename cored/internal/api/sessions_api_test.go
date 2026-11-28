package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/api"
	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/ipctest"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
)

func TestBuildSessionsEndpointsPersistSummaryBuildsAndFilterHistory(t *testing.T) {
	sock := ipctest.TempEndpoint(t, "dmn-sess")
	db := filepath.Join(t.TempDir(), "watcher.db")

	srv, err := api.NewServer(sock, db, 2*time.Second, time.Hour, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = srv.Run(ctx) }()

	c := client.New(sock)
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := c.Health(ctx); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("server never became healthy")
		}
		time.Sleep(25 * time.Millisecond)
	}

	opened, err := c.OpenSession(ctx, sessions.BuildSessionOpenRequest{
		Ecosystem:         "xcode",
		Mode:              "explicit",
		RootProjectPath:   "/work/MyApp.xcworkspace",
		CommandLine:       "/usr/bin/xcodebuild -workspace MyApp.xcworkspace -authenticationKeyID SECRET build",
		AnchorPID:         4242,
		AnchorStartTimeMs: 1000,
		StartedAtMs:       2000,
	})
	if err != nil {
		t.Fatalf("OpenSession: %v", err)
	}
	if opened.SessionID == "" {
		t.Fatal("expected generated session ID")
	}

	fp := int64(90 << 20)
	cpu := 1.75
	if err := srv.Store.InsertSnapshot(model.Snapshot{
		SampledAtMs: 2500,
		Processes: []model.Process{
			{PID: 4242, StartTimeMs: 1000, Type: "XCODE_BUILD", Name: "xcodebuild", RSSMemoryMB: 150, PhysicalFootprintBytes: &fp, CPUTotalSecondsTotal: &cpu, Status: "R"},
			{PID: 4243, ParentPID: 4242, StartTimeMs: 2100, Type: "XCODE_COMPILER", Name: "swift-frontend", RSSMemoryMB: 250, PhysicalFootprintBytes: &fp, CPUTotalSecondsTotal: &cpu, Status: "R"},
		},
	}); err != nil {
		t.Fatal(err)
	}

	summary, err := c.CloseSession(ctx, opened.SessionID, sessions.BuildSessionCloseRequest{
		EndedAtMs: 3000,
		Outcome:   "success",
	})
	if err != nil {
		t.Fatalf("CloseSession: %v", err)
	}
	if summary.Outcome != "success" || summary.ObservedProcessCount != 2 || summary.TotalPeakRSSMB != 400 {
		t.Fatalf("unexpected summary: %+v", summary)
	}
	if summary.CommandLine == "" || strings.Contains(summary.CommandLine, "SECRET") {
		t.Fatalf("command line should be redacted, got %q", summary.CommandLine)
	}

	listed, err := c.ListSessions(ctx, "xcode", 10)
	if err != nil || len(listed) != 1 || listed[0].SessionID != opened.SessionID {
		t.Fatalf("ListSessions=%+v err=%v", listed, err)
	}

	got, err := c.GetSession(ctx, opened.SessionID)
	if err != nil || got.SessionID != opened.SessionID {
		t.Fatalf("GetSession=%+v err=%v", got, err)
	}

	// Verify completed Xcode session surfaces in /v1/builds?tool=xcode.
	buildsPayload, err := c.BuildsFiltered(ctx, client.BuildQuery{Limit: 10, Tool: "xcode"})
	if err != nil {
		t.Fatalf("Builds: %v", err)
	}
	foundBuild := false
	for _, b := range buildsPayload.Builds {
		if b.BuildID == opened.SessionID && b.FinalStatus == "SUCCESS" && b.Tool == "XCODE" && b.ProjectPath == "/work/MyApp.xcworkspace" {
			foundBuild = true
		}
	}
	if !foundBuild {
		t.Fatalf("expected closed Xcode session in /v1/builds?tool=xcode, got %+v", buildsPayload.Builds)
	}

	// Verify /v1/processes/history?type=XCODE_COMPILER filtering in SQLite.
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "http://daemonitor/v1/processes/history?since_ms=0&type=XCODE_COMPILER&limit=1000", nil)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		t.Fatalf("history filtered request: %v", err)
	}
	defer resp.Body.Close()
	var hist model.History
	if err := json.NewDecoder(resp.Body).Decode(&hist); err != nil {
		t.Fatal(err)
	}
	if hist.Count != 1 || hist.Processes[0].Type != "XCODE_COMPILER" {
		t.Fatalf("filtered history=%+v", hist)
	}
}
