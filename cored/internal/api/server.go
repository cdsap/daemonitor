package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/bazelprofile"
	"github.com/cdsap/daemonitor/cored/internal/bep"
	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/ipc"
	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/observe"
	"github.com/cdsap/daemonitor/cored/internal/platform"
	"github.com/cdsap/daemonitor/cored/internal/poll"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

// Version is overridden by release builds with Go's -ldflags -X option.
var Version = "1.3.2"

// Server exposes a tiny HTTP API over local IPC (Unix socket or Windows named pipe).
type Server struct {
	SocketPath string
	DBPath     string
	Collector  *poll.Collector
	Logs       *logs.Watcher
	BazelLogs  *logs.BazelWatcher
	BEP        *bep.Watcher
	Profiles   *bazelprofile.Watcher
	Store      *store.Store
	Agg        *builds.Aggregator
	Retention  time.Duration

	mu           sync.RWMutex
	last         model.Snapshot
	lastHost     platform.HostMetrics
	prevActive   map[int64]struct{}
	interval     time.Duration
	hostInterval time.Duration
	lastHostAt   time.Time
}

const (
	defaultQueryLimit      = 100
	maxQueryLimit          = 500
	maxIncludeMetricsLimit = 20
)

func NewServer(socketPath, dbPath string, interval, retention time.Duration, gradleUserHome string) (*Server, error) {
	if interval <= 0 {
		interval = observe.DefaultCadences().ProcessOS
	}
	st, err := store.Open(dbPath)
	if err != nil {
		return nil, err
	}
	collector := poll.NewCollector()
	if interval > 0 {
		collector.PollInterval = interval
		collector.Cadences.ProcessOS = interval
	}
	bepWatcher := bep.NewWatcher()
	collector.BazelClientObserved = bepWatcher.ObserveProcess
	// Local/dev assist: absolute JSON BEP paths already emitted by the user,
	// separated by the OS path-list separator (":" on Unix, ";" on Windows).
	// Daemonitor never enables BEP itself. Example:
	// DAEMONITOR_BEP_WATCH=/tmp/bep.json:/tmp/other.json
	if raw := strings.TrimSpace(os.Getenv("DAEMONITOR_BEP_WATCH")); raw != "" {
		sinks := make([]bep.Sink, 0)
		for _, p := range filepath.SplitList(raw) {
			p = strings.TrimSpace(p)
			if p == "" || !filepath.IsAbs(p) {
				continue
			}
			sinks = append(sinks, bep.Sink{Path: filepath.Clean(p), Format: bep.FormatJSON})
		}
		if len(sinks) > 0 {
			bepWatcher.ExtraPaths = func() []bep.Sink { return sinks }
		}
	}
	s := &Server{
		SocketPath:   socketPath,
		DBPath:       dbPath,
		Collector:    collector,
		Logs:         logs.NewWatcher(gradleUserHome),
		BazelLogs:    logs.NewBazelWatcher(),
		BEP:          bepWatcher,
		Profiles:     bazelprofile.NewWatcher(),
		Store:        st,
		Retention:    retention,
		interval:     interval,
		hostInterval: collector.Cadences.Host,
		last:         model.Snapshot{Processes: []model.Process{}},
		prevActive:   map[int64]struct{}{},
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
	defer s.Collector.Close()
	ctx, cancelRun := context.WithCancel(ctx)
	defer cancelRun()

	ln, err := ipc.Default.Listen(s.SocketPath)
	if err != nil {
		return err
	}
	defer ln.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", s.handleHealth)
	mux.HandleFunc("/v1/capabilities", s.handleCapabilities)
	mux.HandleFunc("/v1/self", s.handleSelf)
	mux.HandleFunc("/v1/host", s.handleHost)
	mux.HandleFunc("/v1/processes", s.handleProcesses)
	mux.HandleFunc("/v1/processes/history", s.handleHistory)
	mux.HandleFunc("/v1/processes/jvm-flags", s.handleJVMFlags)
	mux.HandleFunc("/v1/events", s.handleEvents)
	mux.HandleFunc("/v1/daemon-logs", s.handleDaemonLogs)
	mux.HandleFunc("/v1/daemon-logs/", s.handleDaemonLogTail)
	mux.HandleFunc("/v1/builds", s.handleBuilds)
	mux.HandleFunc("/v1/observation-sessions/open", s.handleObservationSessionOpen)
	mux.HandleFunc("/v1/observation-sessions/close", s.handleObservationSessionClose)

	srv := &http.Server{Handler: mux}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
	}()

	// Join the scan loop before closing workers and storage.
	scanDone := make(chan struct{})
	defer func() { cancelRun(); <-scanDone }()
	// Serve immediately so health stays responsive while the first poll/log scan runs.
	go func() {
		defer close(scanDone)
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
	hostEvery := s.hostInterval
	if hostEvery <= 0 {
		hostEvery = observe.DefaultCadences().Host
	}
	hostTick := time.NewTicker(hostEvery)
	defer hostTick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.refresh(ctx)
		case <-hostTick.C:
			s.refreshHost(ctx)
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
	writeStart := time.Now()
	hostID := ""
	if s.Collector != nil {
		hostID = s.Collector.HostID
	}
	if hostID == "" {
		hostID = platform.LocalHostID()
	}
	_ = s.Store.InsertSnapshotForHost(hostID, snap)
	_ = s.Store.ObserveProcesses(hostID, snap.SampledAtMs, snap.Processes)
	if s.Collector != nil && s.Collector.Observe != nil {
		size := int64(-1)
		if info, err := os.Stat(s.DBPath); err == nil {
			size = info.Size()
		}
		s.Collector.Observe.ObserveStorageWrite(time.Since(writeStart), size)
	}
	active := make(map[int64]struct{})
	for _, p := range snap.Processes {
		if p.Type == "GRADLE_DAEMON" {
			active[int64(p.PID)] = struct{}{}
		}
	}

	newLines, _ := s.Logs.Poll(active)
	if s.BazelLogs != nil {
		s.BazelLogs.Poll(snap.Processes)
	}
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

	if s.Profiles != nil {
		for _, b := range s.Profiles.Poll(ctx, snap.Processes) {
			s.persistBazelBuildWithContext(b, snap.Processes, hostID)
		}
	}
	if s.BEP != nil {
		if closed, err := s.BEP.Poll(ctx); err == nil {
			for _, b := range closed {
				s.persistBazelBuildWithContext(b, snap.Processes, hostID)
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

func (s *Server) persistBazelBuild(b builds.Build) {
	s.mu.RLock()
	processes := s.last.Processes
	s.mu.RUnlock()
	hostID := ""
	if s.Collector != nil {
		hostID = s.Collector.HostID
	}
	s.persistBazelBuildWithContext(b, processes, hostID)
}

func (s *Server) persistBazelBuildWithContext(b builds.Build, processes []model.Process, hostID string) {
	if b.CommandLine != nil {
		redacted := poll.RedactCommandLine(*b.CommandLine)
		b.CommandLine = &redacted
	}
	if b.LogSnippet != "" {
		b.LogSnippet = poll.RedactLogLine(b.LogSnippet)
	}
	if b.EndTimeMs != nil && *b.EndTimeMs >= b.StartTimeMs && s.Store != nil && b.DaemonPID > 0 {
		resolvedHost, serverStartMs := hostID, int64(0)
		for _, p := range processes {
			if p.Type == "BAZEL_SERVER" && int64(p.PID) == b.DaemonPID && p.StartTimeMs > 0 && p.StartTimeMs <= *b.EndTimeMs {
				serverStartMs = p.StartTimeMs
				break
			}
		}
		if serverStartMs == 0 {
			if h, start, ok := s.Store.ResolveBazelServerIncarnation(hostID, b.DaemonPID, b.StartTimeMs, *b.EndTimeMs); ok {
				resolvedHost, serverStartMs = h, start
			}
		}
		if serverStartMs > 0 {
			if b.DaemonIdentity == "" {
				b.DaemonIdentity = store.ProcessInstanceID(resolvedHost, int32(b.DaemonPID), serverStartMs)
			}
			samples, summaries, err := s.Store.BazelBuildWindowSamples(resolvedHost, b.DaemonPID, serverStartMs, b.StartTimeMs, *b.EndTimeMs)
			if err == nil && len(samples) > 0 {
				b.PeakMemoryMB, b.AvgMemoryMB, b.PeakCPUPercent = builds.SummarizeSamples(samples)
				b.WindowSummaries = summaries
			}
		}
	}
	_ = s.Store.InsertBuild(b)
}

func (s *Server) handleObservationSessionOpen(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method must be POST", http.StatusMethodNotAllowed)
		return
	}
	var req sessions.OpenRequest
	if err := decodeJSONBody(r, &req); err != nil {
		http.Error(w, "malformed session body: "+err.Error(), http.StatusBadRequest)
		return
	}
	if strings.TrimSpace(req.Name) == "" {
		http.Error(w, "name is required", http.StatusBadRequest)
		return
	}
	if req.Association != sessions.AssociationConfirmed && req.Association != sessions.AssociationInferred {
		http.Error(w, "association must be confirmed or inferred", http.StatusBadRequest)
		return
	}
	if req.HostID == "" {
		req.HostID = platform.LocalHostID()
	}
	if len(req.Processes) == 0 {
		s.mu.RLock()
		for _, process := range s.last.Processes {
			if process.StartTimeMs > 0 {
				req.Processes = append(req.Processes, sessions.ProcessRef{
					HostID: req.HostID, PID: int64(process.PID), StartTimeMs: process.StartTimeMs, ProcessType: process.Type,
				})
			}
		}
		s.mu.RUnlock()
	}
	for i := range req.Processes {
		if req.Processes[i].HostID == "" {
			req.Processes[i].HostID = req.HostID
		}
		if req.Processes[i].PID <= 0 || req.Processes[i].StartTimeMs <= 0 || req.Processes[i].HostID == "" {
			http.Error(w, "each process requires host_id, positive pid, and positive start_time_ms", http.StatusBadRequest)
			return
		}
	}
	response, existing, err := s.Store.OpenObservationSession(req, time.Now().UnixMilli())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if existing {
		writeJSON(w, response)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		return
	}
}

func (s *Server) handleObservationSessionClose(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method must be POST", http.StatusMethodNotAllowed)
		return
	}
	var req sessions.CloseRequest
	if err := decodeJSONBody(r, &req); err != nil {
		http.Error(w, "malformed session body: "+err.Error(), http.StatusBadRequest)
		return
	}
	if strings.TrimSpace(req.Name) == "" {
		http.Error(w, "name is required", http.StatusBadRequest)
		return
	}
	response, err := s.Store.CloseObservationSession(req.Name, time.Now().UnixMilli())
	if err == sql.ErrNoRows {
		http.Error(w, "observation session not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, response)
}

func decodeJSONBody(r *http.Request, target any) error {
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return fmt.Errorf("request must contain one JSON object")
	}
	return nil
}

func (s *Server) refreshHost(ctx context.Context) {
	host := s.Collector.HostMetrics(ctx)
	s.mu.Lock()
	s.lastHost = host
	s.lastHostAt = time.Now()
	s.mu.Unlock()
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	n, _ := s.Store.Count()
	s.mu.RLock()
	sampledAt := s.last.SampledAtMs
	s.mu.RUnlock()
	writeJSON(w, model.Health{
		ProcessSampleReady: sampledAt > 0,
		ProcessSampledAtMs: sampledAt,
		Status:             "ok",
		Version:            Version,
		Socket:             s.SocketPath,
		SampleCount:        n,
		DBPath:             s.DBPath,
		RetentionMs:        s.Retention.Milliseconds(),
		PID:                os.Getpid(),
	})
}

func (s *Server) handleCapabilities(w http.ResponseWriter, _ *http.Request) {
	caps := s.Collector.Capabilities()
	metrics := make([]model.CapabilityMetric, 0, len(caps))
	for _, c := range caps {
		metrics = append(metrics, model.CapabilityMetric{
			MetricName:   c.MetricName,
			Availability: string(c.Availability),
			Unit:         c.Unit,
			Source:       c.Source,
			Platform:     c.Platform,
			Description:  c.Description,
		})
	}
	writeJSON(w, model.Capabilities{
		SchemaVersion: 1,
		Platform:      poll.Platform(),
		SampledAtMs:   time.Now().UnixMilli(),
		Metrics:       metrics,
	})
}

func (s *Server) handleSelf(w http.ResponseWriter, _ *http.Request) {
	var snap observe.Snapshot
	if s.Collector != nil && s.Collector.Observe != nil {
		snap = s.Collector.Observe.Snapshot()
	}
	cadences := observe.DefaultCadences()
	probeConcurrency := observe.DefaultJVMProbeConcurrency
	if s.Collector != nil {
		cadences = s.Collector.Cadences
		if s.Collector.ProbeConcurrency >= 2 {
			probeConcurrency = s.Collector.ProbeConcurrency
		}
	}
	budget := heap.DefaultTimeout
	if s.Collector != nil && s.Collector.Heap != nil && s.Collector.Heap.Timeout > 0 {
		budget = s.Collector.Heap.Timeout
	}
	writeJSON(w, model.SelfMetrics{
		JVMProbeBudgetMs: budget.Milliseconds(),
		SchemaVersion:    1,
		Platform:         poll.Platform(),
		SampledAtMs:      time.Now().UnixMilli(),
		Metrics:          snap,
		Cadences: model.CollectorCadences{
			ProcessOSMs:       cadences.ProcessOS.Milliseconds(),
			HostMs:            cadences.Host.Milliseconds(),
			JVMHeapGCMs:       cadences.JVMHeapGC.Milliseconds(),
			JVMJITCodeCacheMs: cadences.JVMJITCodeCache.Milliseconds(),
			LinuxPSSMs:        cadences.LinuxPSS.Milliseconds(),
			ContainerJobMs:    cadences.ContainerJob.Milliseconds(),
		},
		ProbeConcurrency: probeConcurrency,
	})
}

func (s *Server) handleHost(w http.ResponseWriter, _ *http.Request) {
	s.mu.RLock()
	host := s.lastHost
	at := s.lastHostAt
	s.mu.RUnlock()
	if at.IsZero() {
		host = s.Collector.HostMetrics(context.Background())
	}
	writeJSON(w, host)
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
	limit := defaultQueryLimit
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if v, err := strconv.Atoi(raw); err == nil {
			limit = boundedLimit(v)
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

func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	sinceMs := int64(0)
	if raw := r.URL.Query().Get("since_ms"); raw != "" {
		if v, err := strconv.ParseInt(raw, 10, 64); err == nil {
			sinceMs = v
		}
	}
	limit := defaultQueryLimit
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if v, err := strconv.Atoi(raw); err == nil {
			limit = boundedLimit(v)
		}
	}
	rows, err := s.Store.ListEvents(sinceMs, limit)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, model.Events{
		SchemaVersion: 1,
		SinceMs:       sinceMs,
		Count:         len(rows),
		Events:        rows,
	})
}

func (s *Server) handleDaemonLogs(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/daemon-logs" {
		http.NotFound(w, r)
		return
	}
	all := s.Logs.List()
	if s.BazelLogs != nil {
		all = append(all, s.BazelLogs.List()...)
	}
	writeJSON(w, map[string]any{
		"logs": all,
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
	var tail logs.Tail
	var ok bool
	knownBazel := false
	if s.BazelLogs != nil {
		_, knownBazel = s.BazelLogs.TailFor(pid, "")
		if knownBazel {
			tail, ok = s.BazelLogs.TailFor(pid, r.URL.Query().Get("source"))
		}
	}
	if !knownBazel {
		tail, ok = s.Logs.TailFor(pid)
	}
	if !ok {
		http.Error(w, "daemon log not found", http.StatusNotFound)
		return
	}
	writeJSON(w, tail)
}

func (s *Server) handleBuilds(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	includeMetrics := query.Get("include_metrics") == "true"
	limit := defaultQueryLimit
	if includeMetrics {
		limit = maxIncludeMetricsLimit
	}
	if raw := query.Get("limit"); raw != "" {
		if v, err := strconv.Atoi(raw); err == nil {
			if includeMetrics && v > maxIncludeMetricsLimit {
				http.Error(w, fmt.Sprintf("include_metrics requires limit <= %d", maxIncludeMetricsLimit), http.StatusBadRequest)
				return
			}
			limit = boundedLimit(v)
		}
	}
	var filters store.BuildFilters
	var rows []builds.Build
	var err error
	pidRaw, startRaw := query.Get("pid"), query.Get("start_time_ms")
	if startRaw != "" {
		pid, pidErr := strconv.ParseInt(pidRaw, 10, 64)
		startMs, startErr := strconv.ParseInt(startRaw, 10, 64)
		if pidErr != nil || startErr != nil || pid <= 0 || startMs <= 0 {
			http.Error(w, "pid and start_time_ms are required for daemon-scoped builds", http.StatusBadRequest)
			return
		}
		filters.PID = pid
		filters.DaemonStartTimeMs = startMs
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
	filters.Tool = query.Get("tool")
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
	var detailsByID map[string]*builds.BazelBuildDetails
	if includeMetrics && len(rows) > 0 {
		ids := make([]string, 0, len(rows))
		for _, row := range rows {
			if row.Tool == builds.ToolBazel {
				ids = append(ids, row.BuildID)
			}
		}
		if len(ids) > 0 {
			detailsByID, err = s.Store.GetBazelBuildDetailsBatch(ids)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
		}
	}
	response := make([]buildResponse, 0, len(rows))
	for _, row := range rows {
		if includeMetrics && detailsByID != nil {
			row.BazelDetails = detailsByID[row.BuildID]
		}
		response = append(response, newBuildResponse(row, includeLogs, includeMetrics))
	}
	writeJSON(w, map[string]any{
		"count":   len(response),
		"summary": summary,
		"builds":  response,
	})
}

type buildResponse struct {
	BuildID         string                    `json:"build_id"`
	DaemonPID       int64                     `json:"daemon_pid"`
	StartTimeMs     int64                     `json:"start_time_ms"`
	EndTimeMs       *int64                    `json:"end_time_ms,omitempty"`
	FinalStatus     string                    `json:"final_status"`
	InferredSource  string                    `json:"inferred_source"`
	Agent           string                    `json:"agent"`
	AgentProvider   string                    `json:"agent_provider"`
	ProjectPath     string                    `json:"project_path"`
	DurationSeconds *float64                  `json:"duration_seconds"`
	PeakMemoryMB    *int64                    `json:"peak_memory_mb,omitempty"`
	AvgMemoryMB     *int64                    `json:"avg_memory_mb,omitempty"`
	PeakCPUPercent  *float64                  `json:"peak_cpu_percent,omitempty"`
	LogSnippet      string                    `json:"log_snippet,omitempty"`
	Tool            string                    `json:"tool,omitempty"`
	BazelProfile    *builds.BazelProfile      `json:"bazel_profile,omitempty"`
	BazelDetails    *builds.BazelBuildDetails `json:"bazel_details,omitempty"`
	WindowSummaries []sessions.RSSSummary     `json:"window_summaries,omitempty"`
}

func newBuildResponse(b builds.Build, includeLogs, includeMetrics bool) buildResponse {
	tool := string(b.Tool)
	if tool == "" {
		tool = string(builds.ToolGradle)
	}
	response := buildResponse{
		BuildID: b.BuildID, DaemonPID: b.DaemonPID, StartTimeMs: b.StartTimeMs, EndTimeMs: b.EndTimeMs,
		FinalStatus: string(b.FinalStatus), InferredSource: string(b.InferredSource),
		Agent: b.Agent, AgentProvider: b.AgentProvider, ProjectPath: b.ProjectPath,
		DurationSeconds: b.DurationSeconds, PeakMemoryMB: b.PeakMemoryMB, AvgMemoryMB: b.AvgMemoryMB,
		PeakCPUPercent: b.PeakCPUPercent, Tool: tool, BazelProfile: b.BazelProfile,
		WindowSummaries: b.WindowSummaries,
	}
	if includeLogs {
		response.LogSnippet = b.LogSnippet
	}
	if includeMetrics {
		response.BazelDetails = b.BazelDetails
	}
	return response
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}

func boundedLimit(value int) int {
	if value <= 0 || value > maxQueryLimit {
		return defaultQueryLimit
	}
	return value
}
