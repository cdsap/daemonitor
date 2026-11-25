package api

import (
	"net/http"
	"strconv"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/platform"
)

func (s *Server) handleJVMFlags(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	pid, err := strconv.ParseInt(r.URL.Query().Get("pid"), 10, 32)
	start, startErr := strconv.ParseInt(r.URL.Query().Get("start_time_ms"), 10, 64)
	if err != nil || startErr != nil || pid <= 0 || start <= 0 {
		http.Error(w, "positive pid and start_time_ms required", http.StatusBadRequest)
		return
	}
	host := platform.LocalHostID()
	if s.Collector != nil && s.Collector.HostID != "" {
		host = s.Collector.HostID
	}
	m, err := s.Store.JVMFlags(host, int32(pid), start)
	if err != nil {
		http.Error(w, "metadata read failed", http.StatusInternalServerError)
		return
	}
	if m == nil {
		unavailable := heap.UnavailableFlags(int32(pid), start, "not_observed")
		unavailable.CollectedAtMs = 0
		m = &unavailable
	}
	writeJSON(w, m)
}
