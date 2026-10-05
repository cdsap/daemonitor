package api

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/poll"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

// Version is overridden by release builds with Go's -ldflags -X option.
var Version = "0.1.0"

// Server exposes a tiny HTTP API over a Unix domain socket.
type Server struct {
	SocketPath string
	DBPath     string
	Collector  *poll.Collector
	Logs       *logs.Watcher
	Store      *store.Store
	Agg        *builds.Aggregator
	Retention  time.Duration

	mu         sync.RWMutex
	last       model.Snapshot
	prevActive map[int64]struct{}
	interval   time.Duration
}

func NewServer(socketPath, dbPath string, interval, retention time.Duration, gradleUserHome string) (*Server, error) {
	st, err := store.Open(dbPath)
	if err != nil {
		return nil, err
	}
	s := &Server{
		SocketPath: socketPath,
		DBPath:     dbPath,
		Collector:  poll.NewCollector(),
		Logs:       logs.NewWatcher(gradleUserHome),
		Store:      st,
		Retention:  retention,
		interval:   interval,
		last:       model.Snapshot{Processes: []model.Process{}},
		prevActive: map[int64]struct{}{},
	}
	s.Agg = builds.NewAggregator(
		func(pid, startMs, endMs int64) []builds.Sample {
			return st.SamplesAsBuildSamples(pid, startMs, endMs)
		},
		nil,
		builds.DefaultLogSnippetLimit,
	)
	return s, nil
}

func (s *Server) Run(ctx context.Context) error {
	defer s.Store.Close()

	_ = os.Remove(s.SocketPath)

	ln, err := net.Listen("unix", s.SocketPath)
	if err != nil {
		return err
	}
	defer ln.Close()
	defer os.Remove(s.SocketPath)

	_ = os.Chmod(s.SocketPath, 0o600)

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", s.handleHealth)
	mux.HandleFunc("/v1/processes", s.handleProcesses)
	mux.HandleFunc("/v1/processes/history", s.handleHistory)
	mux.HandleFunc("/v1/daemon-logs", s.handleDaemonLogs)
	mux.HandleFunc("/v1/daemon-logs/", s.handleDaemonLogTail)
	mux.HandleFunc("/v1/builds", s.handleBuilds)

	srv := &http.Server{Handler: mux}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
	}()

	// Serve immediately so health stays responsive while the first poll/log scan runs.
	go func() {
		s.refresh(ctx)
		s.loop(ctx)
	}()

	err = srv.Serve(ln)
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}

func (s *Server) loop(ctx context.Context) {
	t := time.NewTicker(s.interval)
	defer t.Stop()
	purgeEvery := time.NewTicker(30 * time.Second)
	defer purgeEvery.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.refresh(ctx)
		case <-purgeEvery.C:
			_, _ = s.Store.PurgeOlderThan(s.Retention)
		}
	}
}

func (s *Server) refresh(ctx context.Context) {
	snap, err := s.Collector.Snapshot(ctx)
	if err != nil {
		return
	}
	_ = s.Store.InsertSnapshot(snap)
	active := make(map[int64]struct{})
	for _, p := range snap.Processes {
		if p.Type == "GRADLE_DAEMON" {
			active[int64(p.PID)] = struct{}{}
		}
	}

	newLines, _ := s.Logs.Poll(active)
	for pid, lines := range newLines {
		for _, line := range lines {
			var evPtr *logs.Event
			if ev, ok := logs.ParseLine(line); ok {
				evPtr = &ev
			}
			for _, b := range s.Agg.OnLogLine(pid, line, evPtr) {
				_ = s.Store.InsertBuild(b)
			}
		}
	}

	s.mu.Lock()
	for pid := range s.prevActive {
		if _, ok := active[pid]; !ok {
			if b := s.Agg.OnDaemonGone(pid); b != nil {
				_ = s.Store.InsertBuild(*b)
			}
		}
	}
	s.prevActive = active
	s.last = snap
	s.mu.Unlock()
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	n, _ := s.Store.Count()
	writeJSON(w, model.Health{
		Status:      "ok",
		Version:     Version,
		Socket:      s.SocketPath,
		SampleCount: n,
		DBPath:      s.DBPath,
	})
}

func (s *Server) handleProcesses(w http.ResponseWriter, _ *http.Request) {
	s.mu.RLock()
	snap := s.last
	s.mu.RUnlock()
	writeJSON(w, snap)
}

func (s *Server) handleHistory(w http.ResponseWriter, r *http.Request) {
	sinceMs := time.Now().Add(-15 * time.Minute).UnixMilli()
	if raw := r.URL.Query().Get("since_ms"); raw != "" {
		if v, err := strconv.ParseInt(raw, 10, 64); err == nil {
			sinceMs = v
		}
	}
	limit := 500
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if v, err := strconv.Atoi(raw); err == nil {
			limit = v
		}
	}
	rows, err := s.Store.History(sinceMs, limit)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, model.History{
		SinceMs:   sinceMs,
		Count:     len(rows),
		Processes: rows,
	})
}

func (s *Server) handleDaemonLogs(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/daemon-logs" {
		http.NotFound(w, r)
		return
	}
	writeJSON(w, map[string]any{
		"logs": s.Logs.List(),
	})
}

func (s *Server) handleDaemonLogTail(w http.ResponseWriter, r *http.Request) {
	// /v1/daemon-logs/{pid}/tail
	path := strings.TrimPrefix(r.URL.Path, "/v1/daemon-logs/")
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) != 2 || parts[1] != "tail" {
		http.NotFound(w, r)
		return
	}
	pid, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		http.Error(w, "invalid pid", http.StatusBadRequest)
		return
	}
	tail, ok := s.Logs.TailFor(pid)
	if !ok {
		http.Error(w, "daemon log not found", http.StatusNotFound)
		return
	}
	writeJSON(w, tail)
}

func (s *Server) handleBuilds(w http.ResponseWriter, r *http.Request) {
	limit := 100
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if v, err := strconv.Atoi(raw); err == nil {
			limit = v
		}
	}
	var filters store.BuildFilters
	var rows []builds.Build
	var err error
	query := r.URL.Query()
	pidRaw, startRaw := query.Get("pid"), query.Get("start_time_ms")
	if startRaw != "" {
		pid, pidErr := strconv.ParseInt(pidRaw, 10, 64)
		startMs, startErr := strconv.ParseInt(startRaw, 10, 64)
		if pidErr != nil || startErr != nil || pid <= 0 || startMs <= 0 {
			http.Error(w, "pid and start_time_ms are required for daemon-scoped builds", http.StatusBadRequest)
			return
		}
		filters.PID = pid
		filters.StartTimeMs = startMs
		rows, err = s.Store.ListBuildsForDaemonSince(pid, startMs, limit)
	} else if pidRaw != "" {
		pid, parseErr := strconv.ParseInt(pidRaw, 10, 64)
		if parseErr != nil || pid <= 0 {
			http.Error(w, "invalid pid", http.StatusBadRequest)
			return
		}
		// Resolve identity in the core. Clients must not be able to turn this
		// into a PID-only query, which could mix history after PID reuse.
		if s.Logs != nil {
			filters.DaemonIdentity = s.Logs.DaemonIdentity(pid)
		}
		filters.PID = pid
		if filters.DaemonIdentity == "" {
			rows = []builds.Build{}
		}
	} else if query.Has("daemon_pid") || query.Has("daemon_identity") {
		pidRaw, identity := query.Get("daemon_pid"), query.Get("daemon_identity")
		if pidRaw == "" || strings.TrimSpace(identity) == "" {
			http.Error(w, "invalid daemon identifier", http.StatusBadRequest)
			return
		}
		pid, parseErr := strconv.ParseInt(pidRaw, 10, 64)
		if parseErr != nil || pid <= 0 {
			http.Error(w, "invalid daemon identifier", http.StatusBadRequest)
			return
		}
		filters.PID = pid
		filters.DaemonIdentity = strings.TrimSpace(identity)
	}
	filters.Project = query.Get("project")
	filters.Status = query.Get("status")
	if rawSince := query.Get("since_ms"); rawSince != "" {
		sinceMs, parseErr := strconv.ParseInt(rawSince, 10, 64)
		if parseErr != nil || sinceMs < 0 {
			http.Error(w, "invalid since_ms", http.StatusBadRequest)
			return
		}
		filters.StartTimeMs = sinceMs
	}
	if rows == nil {
		rows, err = s.Store.ListBuildsFiltered(filters, limit)
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	summary, err := s.Store.SummarizeBuilds(filters)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	includeLogs := query.Get("include_logs") == "true"
	response := make([]buildResponse, 0, len(rows))
	for _, row := range rows {
		response = append(response, newBuildResponse(row, includeLogs))
	}
	writeJSON(w, map[string]any{
		"count":   len(response),
		"summary": summary,
		"builds":  response,
	})
}

type buildResponse struct {
	BuildID         string   `json:"build_id"`
	DaemonPID       int64    `json:"daemon_pid"`
	StartTimeMs     int64    `json:"start_time_ms"`
	FinalStatus     string   `json:"final_status"`
	InferredSource  string   `json:"inferred_source"`
	Agent           string   `json:"agent"`
	AgentProvider   string   `json:"agent_provider"`
	ProjectPath     string   `json:"project_path"`
	DurationSeconds *float64 `json:"duration_seconds"`
	LogSnippet      string   `json:"log_snippet,omitempty"`
}

func newBuildResponse(b builds.Build, includeLogs bool) buildResponse {
	response := buildResponse{
		BuildID: b.BuildID, DaemonPID: b.DaemonPID, StartTimeMs: b.StartTimeMs,
		FinalStatus: string(b.FinalStatus), InferredSource: string(b.InferredSource),
		Agent: b.Agent, AgentProvider: b.AgentProvider, ProjectPath: b.ProjectPath,
		DurationSeconds: b.DurationSeconds,
	}
	if includeLogs {
		response.LogSnippet = b.LogSnippet
	}
	return response
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}
