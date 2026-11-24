package api

import (
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestProfileBuildPersistsThroughBuildAPI(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "builds.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := &Server{Store: st}
	end := int64(2000)
	duration := 1.0
	s.persistBazelBuild(builds.Build{BuildID: "plain-build", DaemonPID: 42, StartTimeMs: 900, EndTimeMs: &end, DurationSeconds: &duration, Tool: builds.ToolBazel, InferredSource: builds.SourceProfile, FinalStatus: builds.StatusCompletedNoOutcome, BazelProfile: &builds.BazelProfile{BazelVersion: "8.0", PhaseSeconds: map[string]float64{"Execution": 1}}})
	recorder := httptest.NewRecorder()
	s.handleBuilds(recorder, httptest.NewRequest("GET", "/v1/builds?pid=42&start_time_ms=1000", nil))
	if recorder.Code != 200 {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	var response struct {
		Builds []builds.Build `json:"builds"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if len(response.Builds) != 1 {
		t.Fatal(response)
	}
	b := response.Builds[0]
	if b.DaemonPID != 42 || b.InferredSource != builds.SourceProfile || b.FinalStatus != builds.StatusCompletedNoOutcome || b.BazelProfile == nil || b.BazelProfile.PhaseSeconds["Execution"] != 1 {
		t.Fatalf("build=%+v", b)
	}
}
