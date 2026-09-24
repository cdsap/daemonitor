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

const Version = "0.1.0"

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
	rows, err := s.Store.ListBuilds(limit)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]any{
		"count":  len(rows),
		"builds": rows,
	})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}
