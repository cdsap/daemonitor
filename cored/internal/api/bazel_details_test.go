package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestBuildsEndpointIncludeMetricsOptInAndBounds(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "api-details.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	wall := int64(4062)
	executed := int64(6)
	if err := st.InsertBuild(builds.Build{
		BuildID: "bazel-metrics-1", DaemonPID: 42, StartTimeMs: 2000,
		Tool: builds.ToolBazel, InferredSource: builds.SourceBEP, FinalStatus: builds.StatusSuccess,
		BazelDetails: &builds.BazelBuildDetails{
			SchemaVersion: 1, BazelVersion: "8.4.2", SourceFormat: "json",
			CollectionState: builds.CollectionStateSealed, UpdatedAtMs: 6062,
			Metrics: builds.BazelBuildMetrics{
				Timing:  &builds.BazelTimingMetrics{WallTimeMs: &wall},
				Actions: &builds.BazelActionSummary{Executed: &executed},
			},
		},
	}); err != nil {
		t.Fatal(err)
	}
	if err := st.InsertBuild(builds.Build{
		BuildID: "gradle-1", DaemonPID: 99, StartTimeMs: 1000,
		Tool: builds.ToolGradle, InferredSource: builds.SourceTerminal, FinalStatus: builds.StatusSuccess,
	}); err != nil {
		t.Fatal(err)
	}

	srv := &Server{Store: st}

	// Default response omits bazel_details.
	rec := httptest.NewRecorder()
	srv.handleBuilds(rec, httptest.NewRequest(http.MethodGet, "/v1/builds", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	var defaultPayload struct {
		Builds []builds.Build `json:"builds"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &defaultPayload); err != nil {
		t.Fatal(err)
	}
	if len(defaultPayload.Builds) != 2 || defaultPayload.Builds[0].BazelDetails != nil {
		t.Fatalf("default /v1/builds should omit bazel_details: %+v", defaultPayload.Builds)
	}

	// Opt-in include_metrics=true returns bazel_details for Bazel rows only.
	rec = httptest.NewRecorder()
	srv.handleBuilds(rec, httptest.NewRequest(http.MethodGet, "/v1/builds?include_metrics=true", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	var optInPayload struct {
		Builds []builds.Build `json:"builds"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &optInPayload); err != nil {
		t.Fatal(err)
	}
	if len(optInPayload.Builds) != 2 {
		t.Fatalf("builds=%d want 2", len(optInPayload.Builds))
	}
	if optInPayload.Builds[0].BazelDetails == nil || optInPayload.Builds[0].BazelDetails.Metrics.Actions == nil || *optInPayload.Builds[0].BazelDetails.Metrics.Actions.Executed != 6 {
		t.Fatalf("missing bazel_details: %+v", optInPayload.Builds[0].BazelDetails)
	}
	if optInPayload.Builds[1].BazelDetails != nil {
		t.Fatalf("gradle row should not have bazel_details: %+v", optInPayload.Builds[1])
	}

	// Explicit limit > 20 with include_metrics=true returns HTTP 400.
	rec = httptest.NewRecorder()
	srv.handleBuilds(rec, httptest.NewRequest(http.MethodGet, "/v1/builds?include_metrics=true&limit=25", nil))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status=%d want 400", rec.Code)
	}
}
