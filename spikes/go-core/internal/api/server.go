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

	"github.com/cdsap/daemonitor/spikes/go-core/internal/logs"
	"github.com/cdsap/daemonitor/spikes/go-core/internal/model"
	"github.com/cdsap/daemonitor/spikes/go-core/internal/poll"
	"github.com/cdsap/daemonitor/spikes/go-core/internal/store"
)

const Version = "0.0.5-spike"

// Server exposes a tiny HTTP API over a Unix domain socket.
type Server struct {
	SocketPath string
	DBPath     string
	Collector  *poll.Collector
	Logs       *logs.Watcher
	Store      *store.Store
	Retention  time.Duration

	mu       sync.RWMutex
	last     model.Snapshot
	interval time.Duration
}

func NewServer(socketPath, dbPath string, interval, retention time.Duration, gradleUserHome string) (*Server, error) {
	st, err := store.Open(dbPath)
	if err != nil {
		return nil, err
	}
	return &Server{
		SocketPath: socketPath,
		DBPath:     dbPath,
		Collector:  poll.NewCollector(),
		Logs:       logs.NewWatcher(gradleUserHome),
		Store:      st,
		Retention:  retention,
		interval:   interval,
		last:       model.Snapshot{Processes: []model.Process{}},
	}, nil
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
	// Discover all logs for listing; only tail active Gradle daemons (large ~/.gradle/daemon trees).
	_ = s.Logs.Poll(active)
	s.mu.Lock()
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

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}
