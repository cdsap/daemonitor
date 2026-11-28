package sessions

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

var (
	ErrBuildSessionNotFound      = errors.New("build observation session not found")
	ErrBuildSessionAlreadyClosed = errors.New("build observation session already closed")
	ErrBuildSessionDuplicateOpen = errors.New("build observation session already open")
)

const (
	AssociationAmbiguous = "ambiguous"
)

// BuildSessionOpenRequest starts an explicit or passive build observation session.
type BuildSessionOpenRequest struct {
	SessionID         string `json:"session_id,omitempty"`
	Ecosystem         string `json:"ecosystem,omitempty"` // xcode | gradle | generic
	Mode              string `json:"mode,omitempty"`      // explicit | passive
	RootProjectPath   string `json:"root_project_path"`
	CommandLine       string `json:"command_line,omitempty"`
	BuildID           string `json:"build_id,omitempty"`
	CIJobID           string `json:"ci_job_id,omitempty"`
	HostID            string `json:"host_id,omitempty"`
	AnchorPID         int32  `json:"anchor_pid,omitempty"`
	AnchorStartTimeMs int64  `json:"anchor_start_time_ms,omitempty"`
	StartedAtMs       int64  `json:"started_at_ms,omitempty"`
}

// BuildSessionCloseRequest closes an active build observation session.
type BuildSessionCloseRequest struct {
	EndedAtMs int64  `json:"ended_at_ms,omitempty"`
	Outcome   string `json:"outcome,omitempty"`
}

// BuildSessionProcessSummary is one attributed process incarnation inside a build session.
type BuildSessionProcessSummary struct {
	PID                int32    `json:"pid"`
	StartTimeMs        int64    `json:"start_time_ms,omitempty"`
	Type               string   `json:"type,omitempty"`
	Name               string   `json:"name,omitempty"`
	Association        string   `json:"association"` // confirmed | inferred | ambiguous
	FirstRSSMB         *int64   `json:"first_rss_mb"`
	PeakRSSMB          *int64   `json:"peak_rss_mb"`
	LastRSSMB          *int64   `json:"last_rss_mb"`
	DeltaRSSMB         *int64   `json:"delta_rss_mb"`
	PeakFootprintBytes *int64   `json:"peak_footprint_bytes,omitempty"`
	CPUSeconds         *float64 `json:"cpu_seconds,omitempty"`
	DiskReadBytes      *int64   `json:"disk_read_bytes,omitempty"`
	DiskWriteBytes     *int64   `json:"disk_write_bytes,omitempty"`
	SampleCount        int      `json:"sample_count"`
	Notes              string   `json:"notes,omitempty"`
}

// BuildSessionRoleSummary aggregates observed processes by role/process_type inside one session.
type BuildSessionRoleSummary struct {
	Role               string   `json:"role"`
	ObservedProcesses  int      `json:"observed_processes"`
	MaxParallelism     int      `json:"max_parallelism"`
	PeakRSSMB          int64    `json:"peak_rss_mb"`
	PeakFootprintBytes *int64   `json:"peak_footprint_bytes,omitempty"`
	TotalCPUSeconds    *float64 `json:"total_cpu_seconds,omitempty"`
	DiskReadBytes      *int64   `json:"disk_read_bytes,omitempty"`
	DiskWriteBytes     *int64   `json:"disk_write_bytes,omitempty"`
}

// BuildSessionSummary is the persisted rollup returned for a build observation session.
type BuildSessionSummary struct {
	SchemaVersion           string                       `json:"schema_version"`
	SessionID               string                       `json:"session_id"`
	Ecosystem               string                       `json:"ecosystem,omitempty"`
	Mode                    string                       `json:"mode,omitempty"`
	RootProjectPath         string                       `json:"root_project_path"`
	CommandLine             string                       `json:"command_line,omitempty"`
	BuildID                 string                       `json:"build_id,omitempty"`
	CIJobID                 string                       `json:"ci_job_id,omitempty"`
	StartedAtMs             int64                        `json:"started_at_ms"`
	EndedAtMs               int64                        `json:"ended_at_ms"`
	Outcome                 string                       `json:"outcome,omitempty"`
	ConcurrentOverlap       bool                         `json:"concurrent_overlap,omitempty"`
	ObservedProcessCount    int                          `json:"observed_process_count,omitempty"`
	MaxParallelism          int                          `json:"max_parallelism,omitempty"`
	Processes               []BuildSessionProcessSummary `json:"processes"`
	Roles                   []BuildSessionRoleSummary    `json:"roles,omitempty"`
	TotalPeakRSSMB          int64                        `json:"total_peak_rss_mb"`
	TotalPeakFootprintBytes *int64                       `json:"total_peak_footprint_bytes,omitempty"`
	TotalCPUSeconds         *float64                     `json:"total_cpu_seconds,omitempty"`
	TotalDiskReadBytes      *int64                       `json:"total_disk_read_bytes,omitempty"`
	TotalDiskWriteBytes     *int64                       `json:"total_disk_write_bytes,omitempty"`
}

// BuildSession represents an active or completed build observation session.
type BuildSession struct {
	BuildSessionOpenRequest
	Closed    bool
	EndedAtMs int64
	Outcome   string
	Summary   *BuildSessionSummary
}

// DetailedSampleSource returns full incarnation-tagged samples inside a time window.
type DetailedSampleSource interface {
	SamplesInWindowDetailed(startMs, endMs int64) ([]model.Process, error)
}

// BuildSessionPersistence persists opened/closed build observation sessions and attributed links.
type BuildSessionPersistence interface {
	SaveBuildSessionOpen(s BuildSession) error
	SaveBuildSessionSummary(s BuildSession, summary BuildSessionSummary) error
	ListBuildSessions(ecosystem string, limit int) ([]BuildSessionSummary, error)
	GetBuildSession(sessionID string) (*BuildSessionSummary, error)
}

// BuildSessionRegistry manages live and persisted build observation sessions.
type BuildSessionRegistry struct {
	mu       sync.Mutex
	sessions map[string]*BuildSession
	detailed DetailedSampleSource
	persist  BuildSessionPersistence
}

// NewBuildSessionRegistry creates a build session registry backed by detailed
// incarnation sample queries and optional durable SQLite persistence.
func NewBuildSessionRegistry(detailed DetailedSampleSource, persist BuildSessionPersistence) *BuildSessionRegistry {
	return &BuildSessionRegistry{
		sessions: make(map[string]*BuildSession),
		detailed: detailed,
		persist:  persist,
	}
}

func (r *BuildSessionRegistry) Open(req BuildSessionOpenRequest) (*BuildSession, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	id := req.SessionID
	if id == "" {
		id = newBuildSessionID()
	}
	if existing, ok := r.sessions[id]; ok {
		if !existing.Closed {
			return existing, ErrBuildSessionDuplicateOpen
		}
		return nil, ErrBuildSessionDuplicateOpen
	}
	if req.StartedAtMs <= 0 {
		req.StartedAtMs = time.Now().UnixMilli()
	}
	if req.Mode == "" {
		req.Mode = "explicit"
	}
	req.SessionID = id
	s := &BuildSession{BuildSessionOpenRequest: req}
	r.sessions[id] = s
	if r.persist != nil {
		_ = r.persist.SaveBuildSessionOpen(*s)
	}
	out := *s
	return &out, nil
}

func (r *BuildSessionRegistry) Close(id string, req BuildSessionCloseRequest) (*BuildSessionSummary, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	s, ok := r.sessions[id]
	if !ok {
		return nil, ErrBuildSessionNotFound
	}
	if s.Closed {
		if s.Summary != nil {
			cp := *s.Summary
			return &cp, ErrBuildSessionAlreadyClosed
		}
		return nil, ErrBuildSessionAlreadyClosed
	}
	ended := req.EndedAtMs
	if ended <= 0 {
		ended = time.Now().UnixMilli()
	}
	if ended < s.StartedAtMs {
		ended = s.StartedAtMs
	}

	overlap := r.hasOverlappingSessionLocked(s, ended)
	summary := r.buildDetailedSummary(s, ended, req, overlap)
	s.Closed = true
	s.EndedAtMs = ended
	s.Outcome = req.Outcome
	s.Summary = summary
	if r.persist != nil {
		_ = r.persist.SaveBuildSessionSummary(*s, *summary)
	}
	cp := *summary
	return &cp, nil
}

// ActiveCount returns how many build sessions are currently open.
func (r *BuildSessionRegistry) ActiveCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	count := 0
	for _, s := range r.sessions {
		if !s.Closed {
			count++
		}
	}
	return count
}

// HasActiveAnchor reports whether an open build session already owns pid.
func (r *BuildSessionRegistry) HasActiveAnchor(pid int32) bool {
	if pid <= 0 {
		return false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, s := range r.sessions {
		if !s.Closed && s.AnchorPID == pid {
			return true
		}
	}
	return false
}

// List returns recent build sessions (persisted + any currently open in memory).
func (r *BuildSessionRegistry) List(ecosystem string, limit int) ([]BuildSessionSummary, error) {
	if limit <= 0 || limit > 500 {
		limit = 50
	}
	if r.persist != nil {
		return r.persist.ListBuildSessions(ecosystem, limit)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]BuildSessionSummary, 0, len(r.sessions))
	for _, s := range r.sessions {
		if ecosystem != "" && !strings.EqualFold(s.Ecosystem, ecosystem) {
			continue
		}
		if s.Summary != nil {
			out = append(out, *s.Summary)
		} else {
			out = append(out, openBuildSessionSummary(s))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAtMs > out[j].StartedAtMs })
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

// Get returns a single build session summary from memory or durable storage.
func (r *BuildSessionRegistry) Get(sessionID string) (*BuildSessionSummary, error) {
	r.mu.Lock()
	if s, ok := r.sessions[sessionID]; ok {
		if s.Summary != nil {
			cp := *s.Summary
			r.mu.Unlock()
			return &cp, nil
		}
		openSummary := openBuildSessionSummary(s)
		r.mu.Unlock()
		return &openSummary, nil
	}
	r.mu.Unlock()
	if r.persist != nil {
		return r.persist.GetBuildSession(sessionID)
	}
	return nil, nil
}

func openBuildSessionSummary(s *BuildSession) BuildSessionSummary {
	return BuildSessionSummary{
		SchemaVersion:   "daemonitor.observation.v1",
		SessionID:       s.SessionID,
		Ecosystem:       s.Ecosystem,
		Mode:            s.Mode,
		RootProjectPath: s.RootProjectPath,
		CommandLine:     s.CommandLine,
		StartedAtMs:     s.StartedAtMs,
		Outcome:         "running",
		Processes:       []BuildSessionProcessSummary{},
	}
}

func (r *BuildSessionRegistry) hasOverlappingSessionLocked(target *BuildSession, endedAtMs int64) bool {
	for id, other := range r.sessions {
		if id == target.SessionID {
			continue
		}
		if target.Ecosystem != "" && other.Ecosystem != "" && !strings.EqualFold(target.Ecosystem, other.Ecosystem) {
			continue
		}
		otherEnd := other.EndedAtMs
		if !other.Closed {
			otherEnd = endedAtMs
		}
		if other.StartedAtMs <= endedAtMs && otherEnd >= target.StartedAtMs {
			return true
		}
	}
	return false
}

type procIncarnation struct {
	pid     int32
	startMs int64
}

func (r *BuildSessionRegistry) buildDetailedSummary(s *BuildSession, endedAtMs int64, req BuildSessionCloseRequest, sessionOverlap bool) *BuildSessionSummary {
	var rawSamples []model.Process
	if r.detailed != nil {
		var err error
		rawSamples, err = r.detailed.SamplesInWindowDetailed(s.StartedAtMs, endedAtMs)
		if err != nil {
			rawSamples = nil
		}
	}

	anchorPID := s.AnchorPID
	anchorStart := s.AnchorStartTimeMs

	// Build parent map across all observed samples so descendant walks work.
	parentByPID := make(map[int32]int32, len(rawSamples))
	xcodeBuilds := make(map[procIncarnation]struct{})
	for _, sample := range rawSamples {
		if sample.ParentPID > 0 && sample.ParentPID != sample.PID {
			parentByPID[sample.PID] = sample.ParentPID
		}
		if sample.Type == "XCODE_BUILD" {
			xcodeBuilds[procIncarnation{pid: sample.PID, startMs: sample.StartTimeMs}] = struct{}{}
		}
	}
	concurrentOverlap := sessionOverlap
	if strings.EqualFold(s.Ecosystem, "xcode") && len(xcodeBuilds) > 1 {
		concurrentOverlap = true
	}

	descendsFromAnchor := func(pid int32) bool {
		if anchorPID <= 0 {
			return false
		}
		cur := pid
		for depth := 0; depth < 8; depth++ {
			ppid, ok := parentByPID[cur]
			if !ok || ppid <= 1 || ppid == cur {
				return false
			}
			if ppid == anchorPID {
				return true
			}
			cur = ppid
		}
		return false
	}

	derivedDataPath := extractFlagValue(s.CommandLine, "-derivedDataPath")

	matchesProject := func(p model.Process) bool {
		if derivedDataPath != "" {
			return strings.Contains(p.CommandLine, derivedDataPath)
		}
		if concurrentOverlap {
			// When two concurrent builds share the same project directory without
			// distinct -derivedDataPath markers, project path alone is ambiguous.
			return false
		}
		root := strings.TrimSpace(s.RootProjectPath)
		if root == "" {
			return false
		}
		if p.ProjectPath != nil && strings.Contains(*p.ProjectPath, root) {
			return true
		}
		if p.WorkingDirectory != nil && strings.Contains(*p.WorkingDirectory, root) {
			return true
		}
		return strings.Contains(p.CommandLine, root)
	}

	// Collect other active same-ecosystem sessions' derivedDataPaths so compiler jobs
	// belonging exclusively to a sibling build are not merged into this session.
	otherDerivedData := r.otherDerivedDataPathsLocked(s, endedAtMs, derivedDataPath)

	// Filter relevant samples for this session's ecosystem and group by incarnation.
	byIncarnation := make(map[procIncarnation][]model.Process)
	incarnationOrder := make([]procIncarnation, 0, 16)
	filteredSamples := make([]model.Process, 0, len(rawSamples))
	for _, sample := range rawSamples {
		isAnchor := anchorPID > 0 && sample.PID == anchorPID && (anchorStart == 0 || sample.StartTimeMs == 0 || sample.StartTimeMs == anchorStart)
		// Never merge a different xcodebuild anchor invocation into this session.
		if strings.EqualFold(s.Ecosystem, "xcode") && sample.Type == "XCODE_BUILD" && anchorPID > 0 && !isAnchor {
			continue
		}
		if !isAnchor && matchesAnyPath(sample.CommandLine, otherDerivedData) {
			continue
		}
		isDesc := descendsFromAnchor(sample.PID)
		if !isAnchor && !isDesc && !matchesEcosystem(s.Ecosystem, sample.Type) {
			continue
		}
		filteredSamples = append(filteredSamples, sample)
		key := procIncarnation{pid: sample.PID, startMs: sample.StartTimeMs}
		if _, exists := byIncarnation[key]; !exists {
			incarnationOrder = append(incarnationOrder, key)
		}
		byIncarnation[key] = append(byIncarnation[key], sample)
	}

	procs := make([]BuildSessionProcessSummary, 0, len(incarnationOrder))
	roleMap := make(map[string]*BuildSessionRoleSummary)
	var totalCPU float64
	hasTotalCPU := false
	var totalDiskR, totalDiskW int64
	hasDiskR, hasDiskW := false, false

	for _, key := range incarnationOrder {
		samples := byIncarnation[key]
		firstSample := samples[0]
		lastSample := samples[len(samples)-1]

		isAnchor := anchorPID > 0 && key.pid == anchorPID && (anchorStart == 0 || key.startMs == 0 || key.startMs == anchorStart)
		confirmed := isAnchor || descendsFromAnchor(key.pid) || matchesProject(firstSample)
		assoc := AssociationInferred
		notes := ""
		switch {
		case confirmed:
			assoc = AssociationConfirmed
		case concurrentOverlap:
			assoc = AssociationAmbiguous
			notes = "concurrent same-ecosystem build overlapped this session window"
		}

		firstRSS := firstSample.RSSMemoryMB
		lastRSS := lastSample.RSSMemoryMB
		peakRSS := firstRSS
		var peakFP *int64
		for _, pt := range samples {
			if pt.RSSMemoryMB > peakRSS {
				peakRSS = pt.RSSMemoryMB
			}
			if pt.PhysicalFootprintBytes != nil && (peakFP == nil || *pt.PhysicalFootprintBytes > *peakFP) {
				v := *pt.PhysicalFootprintBytes
				peakFP = &v
			}
		}
		deltaRSS := lastRSS - firstRSS

		cpuSec := computeIncarnationCPUSeconds(samples, s.StartedAtMs)
		diskR := computeIncarnationCounterDelta(samples, s.StartedAtMs, func(p model.Process) *int64 { return p.DiskReadBytes })
		diskW := computeIncarnationCounterDelta(samples, s.StartedAtMs, func(p model.Process) *int64 { return p.DiskWriteBytes })

		row := BuildSessionProcessSummary{
			PID:                key.pid,
			StartTimeMs:        key.startMs,
			Type:               firstSample.Type,
			Name:               firstSample.Name,
			Association:        assoc,
			FirstRSSMB:         &firstRSS,
			PeakRSSMB:          &peakRSS,
			LastRSSMB:          &lastRSS,
			DeltaRSSMB:         &deltaRSS,
			PeakFootprintBytes: peakFP,
			CPUSeconds:         cpuSec,
			DiskReadBytes:      diskR,
			DiskWriteBytes:     diskW,
			SampleCount:        len(samples),
			Notes:              notes,
		}
		procs = append(procs, row)

		rs, ok := roleMap[firstSample.Type]
		if !ok {
			rs = &BuildSessionRoleSummary{Role: firstSample.Type}
			roleMap[firstSample.Type] = rs
		}
		rs.ObservedProcesses++
		if cpuSec != nil {
			totalCPU += *cpuSec
			hasTotalCPU = true
			if rs.TotalCPUSeconds == nil {
				v := *cpuSec
				rs.TotalCPUSeconds = &v
			} else {
				*rs.TotalCPUSeconds += *cpuSec
			}
		}
		if diskR != nil {
			totalDiskR += *diskR
			hasDiskR = true
			if rs.DiskReadBytes == nil {
				v := *diskR
				rs.DiskReadBytes = &v
			} else {
				*rs.DiskReadBytes += *diskR
			}
		}
		if diskW != nil {
			totalDiskW += *diskW
			hasDiskW = true
			if rs.DiskWriteBytes == nil {
				v := *diskW
				rs.DiskWriteBytes = &v
			} else {
				*rs.DiskWriteBytes += *diskW
			}
		}
	}

	// Compute true simultaneous concurrency & peak RSS / footprint per sample timestamp.
	type tickAgg struct {
		count     int
		rssMB     int64
		fpBytes   int64
		hasFP     bool
		roleCount map[string]int
		roleRSS   map[string]int64
		roleFP    map[string]int64
		roleHasFP map[string]bool
	}
	byTick := make(map[int64]*tickAgg)
	for _, pt := range filteredSamples {
		t, ok := byTick[pt.SampledAtMs]
		if !ok {
			t = &tickAgg{
				roleCount: make(map[string]int),
				roleRSS:   make(map[string]int64),
				roleFP:    make(map[string]int64),
				roleHasFP: make(map[string]bool),
			}
			byTick[pt.SampledAtMs] = t
		}
		t.count++
		t.rssMB += pt.RSSMemoryMB
		t.roleCount[pt.Type]++
		t.roleRSS[pt.Type] += pt.RSSMemoryMB
		if pt.PhysicalFootprintBytes != nil {
			t.fpBytes += *pt.PhysicalFootprintBytes
			t.hasFP = true
			t.roleFP[pt.Type] += *pt.PhysicalFootprintBytes
			t.roleHasFP[pt.Type] = true
		}
	}

	var totalPeakRSS int64
	var totalPeakFP *int64
	maxParallel := 0
	for _, t := range byTick {
		if t.count > maxParallel {
			maxParallel = t.count
		}
		if t.rssMB > totalPeakRSS {
			totalPeakRSS = t.rssMB
		}
		if t.hasFP && (totalPeakFP == nil || t.fpBytes > *totalPeakFP) {
			v := t.fpBytes
			totalPeakFP = &v
		}
		for role, cnt := range t.roleCount {
			if rs := roleMap[role]; rs != nil {
				if cnt > rs.MaxParallelism {
					rs.MaxParallelism = cnt
				}
				if t.roleRSS[role] > rs.PeakRSSMB {
					rs.PeakRSSMB = t.roleRSS[role]
				}
				if t.roleHasFP[role] && (rs.PeakFootprintBytes == nil || t.roleFP[role] > *rs.PeakFootprintBytes) {
					v := t.roleFP[role]
					rs.PeakFootprintBytes = &v
				}
			}
		}
	}

	roles := make([]BuildSessionRoleSummary, 0, len(roleMap))
	for _, rs := range roleMap {
		roles = append(roles, *rs)
	}
	sort.Slice(roles, func(i, j int) bool { return roles[i].Role < roles[j].Role })

	var totalCPUPtr *float64
	if hasTotalCPU {
		totalCPUPtr = &totalCPU
	}
	var totalDiskRPtr, totalDiskWPtr *int64
	if hasDiskR {
		totalDiskRPtr = &totalDiskR
	}
	if hasDiskW {
		totalDiskWPtr = &totalDiskW
	}

	return &BuildSessionSummary{
		SchemaVersion:           "daemonitor.observation.v1",
		SessionID:               s.SessionID,
		Ecosystem:               s.Ecosystem,
		Mode:                    s.Mode,
		RootProjectPath:         s.RootProjectPath,
		CommandLine:             s.CommandLine,
		BuildID:                 s.BuildID,
		CIJobID:                 s.CIJobID,
		StartedAtMs:             s.StartedAtMs,
		EndedAtMs:               endedAtMs,
		Outcome:                 req.Outcome,
		ConcurrentOverlap:       concurrentOverlap,
		ObservedProcessCount:    len(procs),
		MaxParallelism:          maxParallel,
		Processes:               procs,
		Roles:                   roles,
		TotalPeakRSSMB:          totalPeakRSS,
		TotalPeakFootprintBytes: totalPeakFP,
		TotalCPUSeconds:         totalCPUPtr,
		TotalDiskReadBytes:      totalDiskRPtr,
		TotalDiskWriteBytes:     totalDiskWPtr,
	}
}

func (r *BuildSessionRegistry) otherDerivedDataPathsLocked(target *BuildSession, endedAtMs int64, selfDD string) []string {
	var out []string
	for id, other := range r.sessions {
		if id == target.SessionID {
			continue
		}
		otherEnd := other.EndedAtMs
		if !other.Closed {
			otherEnd = endedAtMs
		}
		if other.StartedAtMs > endedAtMs || otherEnd < target.StartedAtMs {
			continue
		}
		dd := extractFlagValue(other.CommandLine, "-derivedDataPath")
		if dd != "" && dd != selfDD {
			out = append(out, dd)
		}
	}
	return out
}

func extractFlagValue(cmdLine, flagName string) string {
	tokens := strings.Fields(cmdLine)
	for i := 0; i < len(tokens)-1; i++ {
		if tokens[i] == flagName {
			val := strings.Trim(tokens[i+1], `"'`)
			if val != "" && !strings.HasPrefix(val, "-") {
				return val
			}
		}
	}
	return ""
}

func matchesAnyPath(cmdLine string, paths []string) bool {
	for _, p := range paths {
		if p != "" && strings.Contains(cmdLine, p) {
			return true
		}
	}
	return false
}

func matchesEcosystem(ecosystem, processType string) bool {
	switch strings.ToLower(strings.TrimSpace(ecosystem)) {
	case "xcode":
		return strings.HasPrefix(processType, "XCODE_")
	case "gradle":
		return !strings.HasPrefix(processType, "XCODE_")
	default:
		return true
	}
}

// computeIncarnationCPUSeconds uses cumulative CPU counters so even 1-sample
// ephemeral compiler jobs contribute CPU time, while long-lived services use
// strictly in-session deltas.
func computeIncarnationCPUSeconds(samples []model.Process, sessionStartMs int64) *float64 {
	var first, last *float64
	startMs := int64(0)
	for _, s := range samples {
		if startMs == 0 && s.StartTimeMs > 0 {
			startMs = s.StartTimeMs
		}
		if s.CPUTotalSecondsTotal == nil {
			continue
		}
		v := *s.CPUTotalSecondsTotal
		if first == nil {
			first = &v
		}
		last = &v
	}
	if last == nil {
		return nil
	}
	// Spawned during the session: cumulative total reflects work in this session.
	if startMs > 0 && startMs >= sessionStartMs-1000 {
		v := *last
		return &v
	}
	if first != nil && len(samples) >= 2 && *last >= *first {
		d := *last - *first
		return &d
	}
	v := *last
	return &v
}

func computeIncarnationCounterDelta(samples []model.Process, sessionStartMs int64, get func(model.Process) *int64) *int64 {
	var first, last *int64
	startMs := int64(0)
	for _, s := range samples {
		if startMs == 0 && s.StartTimeMs > 0 {
			startMs = s.StartTimeMs
		}
		ptr := get(s)
		if ptr == nil {
			continue
		}
		v := *ptr
		if first == nil {
			first = &v
		}
		last = &v
	}
	if last == nil {
		return nil
	}
	if startMs > 0 && startMs >= sessionStartMs-1000 {
		v := *last
		return &v
	}
	if first != nil && len(samples) >= 2 && *last >= *first {
		d := *last - *first
		return &d
	}
	return nil
}

func newBuildSessionID() string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return "obs-" + hex.EncodeToString(b[:])
}
