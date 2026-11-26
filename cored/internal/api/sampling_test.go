package api

import (
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestSamplingReadinessAndEffectiveSettings(t *testing.T) {
	s, err := NewServer("unused", filepath.Join(t.TempDir(), "sampling.db"), 350*time.Millisecond, time.Hour, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Store.Close()
	defer s.Collector.Close()
	health := func() model.Health {
		w := httptest.NewRecorder()
		s.handleHealth(w, httptest.NewRequest("GET", "/v1/health", nil))
		var h model.Health
		if err := json.Unmarshal(w.Body.Bytes(), &h); err != nil {
			t.Fatal(err)
		}
		return h
	}
	if h := health(); h.Status != "ok" || h.ProcessSampleReady || h.ProcessSampledAtMs != 0 {
		t.Fatalf("startup health=%+v", h)
	}
	s.last = model.Snapshot{SampledAtMs: 123, Processes: []model.Process{}}
	if h := health(); !h.ProcessSampleReady || h.ProcessSampledAtMs != 123 {
		t.Fatalf("valid empty sample health=%+v", h)
	}
	s.Collector.Cadences.JVMHeapGC = 3 * time.Second
	s.Collector.Cadences.JVMJITCodeCache = 7 * time.Second
	s.Collector.Heap.Timeout = 100 * time.Millisecond
	w := httptest.NewRecorder()
	s.handleSelf(w, httptest.NewRequest("GET", "/v1/self", nil))
	var settings model.SelfMetrics
	if err := json.Unmarshal(w.Body.Bytes(), &settings); err != nil {
		t.Fatal(err)
	}
	if settings.Cadences.ProcessOSMs != 350 || settings.Cadences.JVMHeapGCMs != 3000 || settings.Cadences.JVMJITCodeCacheMs != 7000 || settings.JVMProbeBudgetMs != 100 || settings.ProbeConcurrency != 2 {
		t.Fatalf("effective settings=%+v", settings)
	}
}
