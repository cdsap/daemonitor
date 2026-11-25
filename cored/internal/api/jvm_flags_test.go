package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/poll"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestJVMFlagsReadContract(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "flags.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	m := heap.UnavailableFlags(42, 1000, "attach_disabled")
	if err := st.ObserveProcesses("test", 2000, []model.Process{{PID: 42, StartTimeMs: 1000, JVMFlags: &m}}); err != nil {
		t.Fatal(err)
	}
	s := &Server{Store: st, Collector: &poll.Collector{HostID: "test"}}
	for _, tc := range []struct {
		query, reason string
		status        int
	}{
		{"?pid=42&start_time_ms=1000", "attach_disabled", 200},
		{"?pid=42&start_time_ms=2000", "not_observed", 200},
		{"?pid=42", "", 400}, {"?pid=-1&start_time_ms=1000", "", 400},
		{"?pid=2147483648&start_time_ms=1000", "", 400},
	} {
		r := httptest.NewRecorder()
		s.handleJVMFlags(r, httptest.NewRequest(http.MethodGet, "/v1/processes/jvm-flags"+tc.query, nil))
		if r.Code != tc.status {
			t.Fatalf("%s: %d %s", tc.query, r.Code, r.Body.String())
		}
		if tc.status != 200 {
			continue
		}
		var got model.JVMFlagsMetadata
		if err := json.Unmarshal(r.Body.Bytes(), &got); err != nil {
			t.Fatal(err)
		}
		if got.Reason != tc.reason || got.SchemaVersion != 1 || got.Availability != "unavailable" || got.Source != "jcmd.VM.flags-all" || got.Flags == nil {
			t.Fatalf("%+v", got)
		}
	}
	available := m
	available.Availability = "available"
	available.Reason = ""
	available.Incomplete = false
	available.Flags = map[string]model.JVMFlag{"UseG1GC": {Type: "bool", Value: "true"}}
	if err := st.ObserveProcesses("test", 3000, []model.Process{{PID: 42, StartTimeMs: 1000, JVMFlags: &available}}); err != nil {
		t.Fatal(err)
	}
	result := httptest.NewRecorder()
	s.handleJVMFlags(result, httptest.NewRequest(http.MethodGet, "/v1/processes/jvm-flags?pid=42&start_time_ms=1000", nil))
	var exported model.JVMFlagsMetadata
	if err := json.Unmarshal(result.Body.Bytes(), &exported); err != nil {
		t.Fatal(err)
	}
	if result.Code != 200 || exported.Flags["UseG1GC"].Value != "true" || exported.Availability != "available" || exported.Reason != "" {
		t.Fatalf("export=%+v", exported)
	}
	r := httptest.NewRecorder()
	s.handleJVMFlags(r, httptest.NewRequest(http.MethodPost, "/v1/processes/jvm-flags?pid=42&start_time_ms=1000", nil))
	if r.Code != 405 {
		t.Fatalf("status=%d", r.Code)
	}
}
