package bep

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"strconv"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/ptr"
)

// partial holds fields accumulated across one BEP invocation (before and after finished).
type partial struct {
	BuildID          string
	BazelVersion     string
	DaemonPID        int64
	WorkingDirectory string
	ProjectPath      string
	StartTimeMs      int64
	Command          string
	EndTimeMs        int64
	Status           builds.FinalStatus
	Finished         bool
	EnvNames         []string
	ambientEnvNames  map[string]struct{}

	sourceFormat          string
	structuredCommandLine string
	commandLinePriority   int
	memory                *builds.BazelMemoryMetrics
	timing                *builds.BazelTimingMetrics
	actions               *builds.BazelActionSummary
	workers               []builds.BazelWorkerMetric
	workerPools           []builds.BazelWorkerPoolMetric
	tests                 []builds.BazelTestSummary
	testIndex             map[string]int
	diagnostics           *builds.BazelDiagnostics
	progressErrors        string
	lastMessage           bool
	truncated             bool
}

// ParseJSONStream reads NDJSON BuildEvent lines and returns one build per finished
// invocation, retaining post-finished events such as buildMetrics through lastMessage/EOF.
// Incomplete streams (started without finished) yield nothing — honest empty.
func ParseJSONStream(r io.Reader) ([]builds.Build, error) {
	state := &partial{sourceFormat: string(FormatJSON)}
	var closed []builds.Build
	sc := bufio.NewScanner(r)
	// BEP lines can be large (command env dumps).
	sc.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	for sc.Scan() {
		line := bytes.TrimSpace(sc.Bytes())
		if len(line) == 0 {
			continue
		}
		if b, ok := applyJSONEvent(state, line); ok {
			closed = coalesceBuilds(closed, b)
		}
		if state.lastMessage {
			*state = partial{ambientEnvNames: state.ambientEnvNames, sourceFormat: string(FormatJSON)}
		}
	}
	return closed, sc.Err()
}

// ConsumeJSONBytes appends newly available NDJSON bytes into state and returns
// any builds that finished or received post-finished metric enrichment.
// leftover is incomplete trailing data for the next poll.
func ConsumeJSONBytes(state *partial, pending []byte, chunk []byte) (closed []builds.Build, leftover []byte, err error) {
	if state == nil {
		state = &partial{}
	}
	state.sourceFormat = string(FormatJSON)
	buf := append(append([]byte{}, pending...), chunk...)
	for {
		nl := bytes.IndexByte(buf, '\n')
		if nl < 0 {
			// Do not retain consumed events (and their env values) behind a
			// trailing fragment's backing array between polls.
			return closed, bytes.Clone(buf), nil
		}
		line := bytes.TrimSpace(buf[:nl])
		buf = buf[nl+1:]
		if len(line) == 0 {
			continue
		}
		if b, ok := applyJSONEvent(state, line); ok {
			closed = coalesceBuilds(closed, b)
		}
		if state.lastMessage {
			*state = partial{ambientEnvNames: state.ambientEnvNames, sourceFormat: string(FormatJSON)}
		}
	}
}

func applyJSONEvent(state *partial, line []byte) (builds.Build, bool) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(line, &raw); err != nil {
		return builds.Build{}, false
	}
	if state.sourceFormat == "" {
		state.sourceFormat = string(FormatJSON)
	}
	state.EnvNames = append(state.EnvNames, clientEnvNames(raw)...)

	var lastMsg bool
	if lmRaw, ok := raw["lastMessage"]; ok {
		_ = json.Unmarshal(lmRaw, &lastMsg)
	}

	if startedRaw, ok := raw["started"]; ok {
		var started struct {
			UUID               string `json:"uuid"`
			StartTimeMillis    any    `json:"startTimeMillis"`
			StartTime          string `json:"startTime"`
			BuildToolVersion   string `json:"buildToolVersion"`
			WorkingDirectory   string `json:"workingDirectory"`
			WorkspaceDirectory string `json:"workspaceDirectory"`
			ServerPid          any    `json:"serverPid"`
			Command            string `json:"command"`
		}
		if err := json.Unmarshal(startedRaw, &started); err != nil {
			return builds.Build{}, false
		}
		*state = partial{
			ambientEnvNames:  state.ambientEnvNames,
			sourceFormat:     string(FormatJSON),
			BuildID:          started.UUID,
			BazelVersion:     started.BuildToolVersion,
			WorkingDirectory: started.WorkingDirectory,
			ProjectPath:      started.WorkspaceDirectory,
			Command:          started.Command,
			StartTimeMs:      jsonTimeMillis(started.StartTimeMillis, started.StartTime),
			DaemonPID:        anyToInt64(started.ServerPid),
		}
		return builds.Build{}, false
	}

	enriched := false
	if sclRaw, ok := raw["structuredCommandLine"]; ok {
		applyJSONStructuredCommandLine(state, sclRaw)
	}
	if tsRaw, ok := raw["testSummary"]; ok {
		if applyJSONTestSummary(state, raw["id"], tsRaw) {
			enriched = true
		}
	}
	if bmRaw, ok := raw["buildMetrics"]; ok {
		if applyJSONBuildMetrics(state, bmRaw) {
			enriched = true
		}
	}
	if abRaw, ok := raw["aborted"]; ok {
		if applyJSONAborted(state, abRaw) {
			enriched = true
		}
	}
	if progRaw, ok := raw["progress"]; ok {
		var prog struct {
			Stderr string `json:"stderr"`
		}
		if json.Unmarshal(progRaw, &prog) == nil && prog.Stderr != "" {
			state.recordProgressError(prog.Stderr)
		}
	}

	if finishedRaw, ok := raw["finished"]; ok {
		var finished struct {
			FinishTimeMillis any       `json:"finishTimeMillis"`
			FinishTime       string    `json:"finishTime"`
			ExitCode         *exitCode `json:"exitCode"`
			OverallSuccess   *bool     `json:"overallSuccess"`
		}
		if err := json.Unmarshal(finishedRaw, &finished); err != nil {
			return builds.Build{}, false
		}
		if state.BuildID == "" {
			return builds.Build{}, false
		}
		state.EndTimeMs = jsonTimeMillis(finished.FinishTimeMillis, finished.FinishTime)
		state.Status = statusFromExit(finished.ExitCode, finished.OverallSuccess)
		state.Finished = true
		if lastMsg {
			state.lastMessage = true
		}
		return state.toBuild(), true
	}

	if lastMsg {
		state.lastMessage = true
	}
	if state.Finished && state.BuildID != "" && (enriched || lastMsg) {
		return state.toBuild(), true
	}
	return builds.Build{}, false
}

func applyJSONStructuredCommandLine(state *partial, data json.RawMessage) {
	var scl struct {
		CommandLineLabel string `json:"commandLineLabel"`
		Sections         []struct {
			SectionLabel string `json:"sectionLabel"`
			ChunkList    *struct {
				Chunk []string `json:"chunk"`
			} `json:"chunkList"`
		} `json:"sections"`
	}
	if err := json.Unmarshal(data, &scl); err != nil {
		return
	}
	var cmdChunks, residualChunks []string
	for _, sec := range scl.Sections {
		if sec.ChunkList == nil {
			continue
		}
		switch sec.SectionLabel {
		case "command":
			cmdChunks = append(cmdChunks, sec.ChunkList.Chunk...)
		case "residual":
			residualChunks = append(residualChunks, sec.ChunkList.Chunk...)
		}
	}
	state.recordCommandLine(scl.CommandLineLabel, cmdChunks, residualChunks)
}

func applyJSONTestSummary(state *partial, idRaw, data json.RawMessage) bool {
	var ts struct {
		TotalRunCount          any    `json:"totalRunCount"`
		OverallStatus          any    `json:"overallStatus"`
		TotalNumCached         any    `json:"totalNumCached"`
		TotalRunDurationMillis any    `json:"totalRunDurationMillis"`
		TotalRunDuration       string `json:"totalRunDuration"`
		RunCount               any    `json:"runCount"`
		ShardCount             any    `json:"shardCount"`
		AttemptCount           any    `json:"attemptCount"`
	}
	if err := json.Unmarshal(data, &ts); err != nil {
		return false
	}
	var id struct {
		TestSummary *struct {
			Label         string `json:"label"`
			Configuration *struct {
				ID string `json:"id"`
			} `json:"configuration"`
		} `json:"testSummary"`
	}
	if len(idRaw) > 0 {
		_ = json.Unmarshal(idRaw, &id)
	}
	label, configID := "", ""
	if id.TestSummary != nil {
		label = id.TestSummary.Label
		if id.TestSummary.Configuration != nil {
			configID = id.TestSummary.Configuration.ID
		}
	}
	status := "UNKNOWN"
	switch v := ts.OverallStatus.(type) {
	case string:
		if v != "" {
			status = strings.ToUpper(v)
		}
	case float64:
		status = testStatusName(uint64(v))
	}
	durMs := parseJSONDurationMillis(ts.TotalRunDuration)
	if durMs == nil && ts.TotalRunDurationMillis != nil {
		v := anyToInt64(ts.TotalRunDurationMillis)
		if v >= 0 {
			durMs = &v
		}
	}
	state.recordTestSummary(builds.BazelTestSummary{
		Label:              label,
		ConfigID:           configID,
		OverallStatus:      status,
		TotalRunCount:      anyToInt64(ts.TotalRunCount),
		RunCount:           anyToInt64(ts.RunCount),
		ShardCount:         anyToInt64(ts.ShardCount),
		AttemptCount:       anyToInt64(ts.AttemptCount),
		TotalNumCached:     anyToInt64(ts.TotalNumCached),
		TotalRunDurationMs: durMs,
	})
	return true
}

func applyJSONBuildMetrics(state *partial, data json.RawMessage) bool {
	var bm struct {
		ActionSummary *struct {
			ActionsCreated                    any `json:"actionsCreated"`
			ActionsExecuted                   any `json:"actionsExecuted"`
			ActionsCreatedNotIncludingAspects any `json:"actionsCreatedNotIncludingAspects"`
			RemoteCacheHits                   any `json:"remoteCacheHits"`
			RunnerCount                       []struct {
				Name     string `json:"name"`
				Count    any    `json:"count"`
				ExecKind string `json:"execKind"`
			} `json:"runnerCount"`
			ActionCacheStatistics *struct {
				Hits   any `json:"hits"`
				Misses any `json:"misses"`
			} `json:"actionCacheStatistics"`
		} `json:"actionSummary"`
		MemoryMetrics *struct {
			UsedHeapSizePostBuild          any `json:"usedHeapSizePostBuild"`
			PeakPostGcHeapSize             any `json:"peakPostGcHeapSize"`
			PeakPostGcTenuredSpaceHeapSize any `json:"peakPostGcTenuredSpaceHeapSize"`
			GarbageMetrics                 []struct {
				Type             string `json:"type"`
				GarbageCollected any    `json:"garbageCollected"`
			} `json:"garbageMetrics"`
		} `json:"memoryMetrics"`
		TimingMetrics *struct {
			CPUTimeInMs               any `json:"cpuTimeInMs"`
			WallTimeInMs              any `json:"wallTimeInMs"`
			AnalysisPhaseTimeInMs     any `json:"analysisPhaseTimeInMs"`
			ExecutionPhaseTimeInMs    any `json:"executionPhaseTimeInMs"`
			ActionsExecutionStartInMs any `json:"actionsExecutionStartInMs"`
		} `json:"timingMetrics"`
		WorkerMetrics []struct {
			ProcessID       any    `json:"processId"`
			Mnemonic        string `json:"mnemonic"`
			IsMultiplex     bool   `json:"isMultiplex"`
			IsSandbox       bool   `json:"isSandbox"`
			IsMeasurable    bool   `json:"isMeasurable"`
			ActionsExecuted any    `json:"actionsExecuted"`
			WorkerIDs       []any  `json:"workerIds"`
			WorkerStats     []struct {
				WorkerMemoryInKb any `json:"workerMemoryInKb"`
			} `json:"workerStats"`
		} `json:"workerMetrics"`
		WorkerPoolMetrics *struct {
			WorkerPoolStats []struct {
				Mnemonic       string `json:"mnemonic"`
				CreatedCount   any    `json:"createdCount"`
				DestroyedCount any    `json:"destroyedCount"`
				EvictedCount   any    `json:"evictedCount"`
				AliveCount     any    `json:"aliveCount"`
			} `json:"workerPoolStats"`
		} `json:"workerPoolMetrics"`
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	if err := dec.Decode(&bm); err != nil {
		return false
	}

	if bm.ActionSummary != nil {
		as := &builds.BazelActionSummary{
			Created:                    optionalNonNegInt64(bm.ActionSummary.ActionsCreated),
			Executed:                   optionalNonNegInt64(bm.ActionSummary.ActionsExecuted),
			CreatedNotIncludingAspects: optionalNonNegInt64(bm.ActionSummary.ActionsCreatedNotIncludingAspects),
			RemoteCacheHits:            optionalNonNegInt64(bm.ActionSummary.RemoteCacheHits),
		}
		if bm.ActionSummary.ActionCacheStatistics != nil {
			hits := anyToInt64(bm.ActionSummary.ActionCacheStatistics.Hits)
			misses := anyToInt64(bm.ActionSummary.ActionCacheStatistics.Misses)
			if hits >= 0 {
				as.LocalCacheHits = &hits
			}
			if misses >= 0 {
				as.LocalCacheMisses = &misses
			}
		}
		for i, rc := range bm.ActionSummary.RunnerCount {
			if i >= maxBEPListEntries {
				state.truncated = true
				break
			}
			as.Runners = append(as.Runners, builds.BazelRunnerCount{
				Name:     sanitizeBEPSingleLine(rc.Name, 128),
				ExecKind: sanitizeBEPSingleLine(rc.ExecKind, 64),
				Count:    anyToInt64(rc.Count),
			})
		}
		state.actions = as
	}

	if bm.MemoryMetrics != nil {
		mm := &builds.BazelMemoryMetrics{
			UsedHeapPostBuildBytes: optionalNonNegInt64(bm.MemoryMetrics.UsedHeapSizePostBuild),
			PeakPostGCHeapBytes:    optionalNonNegInt64(bm.MemoryMetrics.PeakPostGcHeapSize),
			PeakPostGCTenuredBytes: optionalNonNegInt64(bm.MemoryMetrics.PeakPostGcTenuredSpaceHeapSize),
		}
		for i, gm := range bm.MemoryMetrics.GarbageMetrics {
			if i >= maxBEPListEntries {
				state.truncated = true
				break
			}
			collected := anyToInt64(gm.GarbageCollected)
			if collected < 0 {
				continue
			}
			mm.Garbage = append(mm.Garbage, builds.BazelGarbageMetric{
				Type:                  sanitizeBEPSingleLine(gm.Type, 128),
				GarbageCollectedBytes: collected,
			})
		}
		state.memory = mm
	}

	if bm.TimingMetrics != nil {
		state.timing = &builds.BazelTimingMetrics{
			CPUTimeMs:               optionalNonNegInt64(bm.TimingMetrics.CPUTimeInMs),
			WallTimeMs:              optionalNonNegInt64(bm.TimingMetrics.WallTimeInMs),
			AnalysisPhaseMs:         optionalNonNegInt64(bm.TimingMetrics.AnalysisPhaseTimeInMs),
			ExecutionPhaseMs:        optionalNonNegInt64(bm.TimingMetrics.ExecutionPhaseTimeInMs),
			ActionsExecutionStartMs: optionalNonNegInt64(bm.TimingMetrics.ActionsExecutionStartInMs),
		}
	}

	if len(bm.WorkerMetrics) > 0 {
		workers := make([]builds.BazelWorkerMetric, 0, min(len(bm.WorkerMetrics), maxBEPListEntries))
		for i, wm := range bm.WorkerMetrics {
			if i >= maxBEPListEntries {
				state.truncated = true
				break
			}
			entry := builds.BazelWorkerMetric{
				ProcessID:       anyToInt64(wm.ProcessID),
				Mnemonic:        sanitizeBEPSingleLine(wm.Mnemonic, 128),
				IsMultiplex:     wm.IsMultiplex,
				IsSandbox:       wm.IsSandbox,
				IsMeasurable:    wm.IsMeasurable,
				ActionsExecuted: anyToInt64(wm.ActionsExecuted),
				WorkerIDCount:   len(wm.WorkerIDs),
			}
			for _, ws := range wm.WorkerStats {
				if ws.WorkerMemoryInKb == nil {
					continue
				}
				kb := anyToInt64(ws.WorkerMemoryInKb)
				if kb < 0 {
					continue
				}
				entry.LastMemoryKB = ptr.Int64(kb)
				if entry.PeakMemoryKB == nil || kb > *entry.PeakMemoryKB {
					entry.PeakMemoryKB = ptr.Int64(kb)
				}
			}
			workers = append(workers, entry)
		}
		state.workers = workers
	}

	if bm.WorkerPoolMetrics != nil && len(bm.WorkerPoolMetrics.WorkerPoolStats) > 0 {
		pools := make([]builds.BazelWorkerPoolMetric, 0, min(len(bm.WorkerPoolMetrics.WorkerPoolStats), maxBEPListEntries))
		for i, ps := range bm.WorkerPoolMetrics.WorkerPoolStats {
			if i >= maxBEPListEntries {
				state.truncated = true
				break
			}
			pools = append(pools, builds.BazelWorkerPoolMetric{
				Mnemonic:       sanitizeBEPSingleLine(ps.Mnemonic, 128),
				CreatedCount:   anyToInt64(ps.CreatedCount),
				DestroyedCount: anyToInt64(ps.DestroyedCount),
				EvictedCount:   anyToInt64(ps.EvictedCount),
				AliveCount:     anyToInt64(ps.AliveCount),
			})
		}
		state.workerPools = pools
	}
	return true
}

func applyJSONAborted(state *partial, data json.RawMessage) bool {
	var ab struct {
		Reason      any    `json:"reason"`
		Description string `json:"description"`
	}
	if err := json.Unmarshal(data, &ab); err != nil {
		return false
	}
	reason := ""
	switch v := ab.Reason.(type) {
	case string:
		reason = strings.ToUpper(v)
	case float64:
		reason = abortedReasonName(uint64(v))
	}
	state.recordAborted(reason, ab.Description)
	return true
}

func optionalNonNegInt64(v any) *int64 {
	if v == nil {
		return nil
	}
	n := anyToInt64(v)
	if n < 0 {
		return nil
	}
	return &n
}

type exitCode struct {
	Name string `json:"name"`
	Code *int   `json:"code"`
}

func statusFromExit(exit *exitCode, overall *bool) builds.FinalStatus {
	if exit != nil {
		if exit.Code != nil {
			if *exit.Code == 0 {
				return builds.StatusSuccess
			}
			name := strings.ToUpper(exit.Name)
			if strings.Contains(name, "INTERRUPT") {
				return builds.StatusInterrupted
			}
			return builds.StatusFailed
		}
		switch strings.ToUpper(exit.Name) {
		case "SUCCESS":
			return builds.StatusSuccess
		case "INTERRUPTED":
			return builds.StatusInterrupted
		default:
			if exit.Name != "" {
				return builds.StatusFailed
			}
		}
	}
	if overall != nil {
		if *overall {
			return builds.StatusSuccess
		}
		return builds.StatusFailed
	}
	return builds.StatusCompletedNoOutcome
}

func (p *partial) toBuild() builds.Build {
	b := builds.Build{
		BuildID:          p.BuildID,
		DaemonPID:        p.DaemonPID,
		WorkingDirectory: p.WorkingDirectory,
		ProjectPath:      p.ProjectPath,
		StartTimeMs:      p.StartTimeMs,
		InferredSource:   builds.SourceBEP,
		FinalStatus:      p.Status,
		Tool:             builds.ToolBazel,
	}
	if attr := builds.DetectAgent(p.EnvNames, p.ambientEnvNames); attr != nil {
		b.Agent, b.AgentProvider = attr.Agent, attr.Provider
	}
	if p.structuredCommandLine != "" {
		cmd := p.structuredCommandLine
		b.CommandLine = &cmd
	} else if p.Command != "" {
		cmd := sanitizeBEPCommand("bazel " + p.Command)
		b.CommandLine = &cmd
	}
	if p.EndTimeMs > 0 {
		end := p.EndTimeMs
		b.EndTimeMs = &end
		if p.StartTimeMs > 0 && p.EndTimeMs >= p.StartTimeMs {
			dur := float64(p.EndTimeMs-p.StartTimeMs) / 1000.0
			b.DurationSeconds = &dur
		}
	}
	if p.Status == builds.StatusFailed || p.Status == builds.StatusInterrupted {
		if p.diagnostics != nil && p.diagnostics.Summary != "" {
			b.LogSnippet = p.diagnostics.Summary
		} else if p.progressErrors != "" {
			b.LogSnippet = p.progressErrors
		}
	}
	format := p.sourceFormat
	if format == "" {
		format = string(FormatJSON)
	}
	b.BazelDetails = p.buildDetails(format)
	return b
}

// clientEnvNames reads only Bazel's client_env options. Action/repository env
// options and arbitrary build metadata are not evidence of client provenance.
// Values are discarded at this boundary and never enter partial or Build.
func clientEnvNames(raw map[string]json.RawMessage) []string {
	var names []string
	add := func(entry string) {
		name, _, _ := strings.Cut(entry, "=")
		if name != "" {
			names = append(names, strings.Clone(name))
		}
	}
	var structured struct {
		Sections []struct {
			OptionList struct {
				Option []struct {
					Name  string `json:"optionName"`
					Value string `json:"optionValue"`
				} `json:"option"`
			} `json:"optionList"`
		} `json:"sections"`
	}
	if json.Unmarshal(raw["structuredCommandLine"], &structured) == nil {
		for _, section := range structured.Sections {
			for _, option := range section.OptionList.Option {
				if option.Name == "client_env" {
					add(option.Value)
				}
			}
		}
	}
	var unstructured struct {
		Args []string `json:"args"`
	}
	var parsed struct {
		CmdLine []string `json:"cmdLine"`
	}
	_ = json.Unmarshal(raw["unstructuredCommandLine"], &unstructured)
	_ = json.Unmarshal(raw["optionsParsed"], &parsed)
	for _, args := range [][]string{unstructured.Args, parsed.CmdLine} {
		for i := 0; i < len(args); i++ {
			if entry, ok := strings.CutPrefix(args[i], "--client_env="); ok {
				add(entry)
			} else if args[i] == "--client_env" && i+1 < len(args) {
				i++
				add(args[i])
			}
		}
	}
	return names
}

func anyToInt64(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case json.Number:
		n, _ := t.Int64()
		return n
	case string:
		n, _ := strconv.ParseInt(t, 10, 64)
		return n
	case int64:
		return t
	case int:
		return int64(t)
	default:
		return 0
	}
}

func jsonTimeMillis(legacy any, timestamp string) int64 {
	if timestamp != "" {
		if parsed, err := time.Parse(time.RFC3339Nano, timestamp); err == nil {
			return parsed.UnixMilli()
		}
	}
	return anyToInt64(legacy)
}
