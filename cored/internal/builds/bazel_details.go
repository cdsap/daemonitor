package builds

const (
	BazelDetailsSchemaVersion = 1

	CollectionStatePartial   = "partial"
	CollectionStateSealed    = "sealed"
	CollectionStateTruncated = "truncated"
	CollectionStateInvalid   = "invalid"
)

// BazelBuildDetails is the versioned, allowlisted BEP telemetry projection
// persisted in bazel_build_details and returned on opt-in detail queries.
type BazelBuildDetails struct {
	SchemaVersion   int               `json:"schema_version"`
	BazelVersion    string            `json:"bazel_version,omitempty"`
	SourceFormat    string            `json:"source_format"`
	CollectionState string            `json:"collection_state"`
	UpdatedAtMs     int64             `json:"updated_at_ms"`
	Metrics         BazelBuildMetrics `json:"metrics"`
}

type BazelBuildMetrics struct {
	Memory      *BazelMemoryMetrics     `json:"memory,omitempty"`
	Timing      *BazelTimingMetrics     `json:"timing,omitempty"`
	Actions     *BazelActionSummary     `json:"actions,omitempty"`
	Workers     []BazelWorkerMetric     `json:"workers,omitempty"`
	WorkerPools []BazelWorkerPoolMetric `json:"worker_pools,omitempty"`
	Tests       []BazelTestSummary      `json:"tests,omitempty"`
	TestTotals  *BazelTestTotals        `json:"test_totals,omitempty"`
	Diagnostics *BazelDiagnostics       `json:"diagnostics,omitempty"`
}

type BazelMemoryMetrics struct {
	UsedHeapPostBuildBytes *int64               `json:"used_heap_post_build_bytes,omitempty"`
	PeakPostGCHeapBytes    *int64               `json:"peak_post_gc_heap_bytes,omitempty"`
	PeakPostGCTenuredBytes *int64               `json:"peak_post_gc_tenured_bytes,omitempty"`
	Garbage                []BazelGarbageMetric `json:"garbage,omitempty"`
}

type BazelGarbageMetric struct {
	Type                  string `json:"type"`
	GarbageCollectedBytes int64  `json:"garbage_collected_bytes"`
}

type BazelTimingMetrics struct {
	CPUTimeMs               *int64 `json:"cpu_ms,omitempty"`
	WallTimeMs              *int64 `json:"wall_ms,omitempty"`
	AnalysisPhaseMs         *int64 `json:"analysis_phase_ms,omitempty"`
	ExecutionPhaseMs        *int64 `json:"execution_phase_ms,omitempty"`
	ActionsExecutionStartMs *int64 `json:"actions_execution_start_ms,omitempty"`
}

type BazelActionSummary struct {
	Created                    *int64             `json:"created,omitempty"`
	Executed                   *int64             `json:"executed,omitempty"`
	CreatedNotIncludingAspects *int64             `json:"created_not_including_aspects,omitempty"`
	RemoteCacheHits            *int64             `json:"remote_cache_hits,omitempty"`
	LocalCacheHits             *int64             `json:"local_cache_hits,omitempty"`
	LocalCacheMisses           *int64             `json:"local_cache_misses,omitempty"`
	Runners                    []BazelRunnerCount `json:"runners,omitempty"`
}

type BazelRunnerCount struct {
	Name     string `json:"name"`
	ExecKind string `json:"exec_kind,omitempty"`
	Count    int64  `json:"count"`
}

type BazelWorkerMetric struct {
	ProcessID       int64  `json:"process_id,omitempty"`
	Mnemonic        string `json:"mnemonic,omitempty"`
	IsMultiplex     bool   `json:"is_multiplex,omitempty"`
	IsSandbox       bool   `json:"is_sandbox,omitempty"`
	IsMeasurable    bool   `json:"is_measurable,omitempty"`
	ActionsExecuted int64  `json:"actions_executed,omitempty"`
	PeakMemoryKB    *int64 `json:"peak_memory_kb,omitempty"`
	LastMemoryKB    *int64 `json:"last_memory_kb,omitempty"`
	WorkerIDCount   int    `json:"worker_id_count,omitempty"`
}

type BazelWorkerPoolMetric struct {
	Mnemonic       string `json:"mnemonic,omitempty"`
	CreatedCount   int64  `json:"created_count,omitempty"`
	DestroyedCount int64  `json:"destroyed_count,omitempty"`
	EvictedCount   int64  `json:"evicted_count,omitempty"`
	AliveCount     int64  `json:"alive_count,omitempty"`
}

type BazelTestSummary struct {
	Label              string `json:"label"`
	ConfigID           string `json:"config_id,omitempty"`
	OverallStatus      string `json:"overall_status"`
	TotalRunCount      int64  `json:"total_run_count,omitempty"`
	RunCount           int64  `json:"run_count,omitempty"`
	ShardCount         int64  `json:"shard_count,omitempty"`
	AttemptCount       int64  `json:"attempt_count,omitempty"`
	TotalNumCached     int64  `json:"total_num_cached,omitempty"`
	TotalRunDurationMs *int64 `json:"total_run_duration_ms,omitempty"`
}

type BazelTestTotals struct {
	Total           int   `json:"total"`
	Passed          int   `json:"passed"`
	Failed          int   `json:"failed"`
	Flaky           int   `json:"flaky"`
	Timeout         int   `json:"timeout"`
	Cached          int   `json:"cached"`
	TotalDurationMs int64 `json:"total_duration_ms"`
}

type BazelDiagnostics struct {
	AbortReason string `json:"abort_reason,omitempty"`
	Summary     string `json:"summary,omitempty"`
}
