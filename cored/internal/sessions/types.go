package sessions

import "sort"

const (
	AssociationConfirmed = "confirmed"
	AssociationInferred  = "inferred"
)

type ProcessRef struct {
	HostID      string `json:"host_id"`
	PID         int64  `json:"pid"`
	StartTimeMs int64  `json:"start_time_ms"`
	ProcessType string `json:"process_type,omitempty"`
}

type OpenRequest struct {
	Name        string       `json:"name"`
	Association string       `json:"association"`
	HostID      string       `json:"host_id"`
	Processes   []ProcessRef `json:"processes,omitempty"`
}

type CloseRequest struct {
	Name string `json:"name"`
}

// RSSSummary is the per-process build-window rollup. Phase 1 = RSS; Phase 2 adds
// optional heap/metaspace/GC first→last deltas when HotSpot samples exist.
type RSSSummary struct {
	HostID      string `json:"host_id"`
	PID         int64  `json:"pid"`
	StartTimeMs int64  `json:"start_time_ms"`
	ProcessType string `json:"process_type,omitempty"`

	FirstSampleAtMs int64 `json:"first_sample_at_ms,omitempty"`
	LastSampleAtMs  int64 `json:"last_sample_at_ms,omitempty"`

	SampleCount int   `json:"sample_count"`
	FirstRSSMB  int64 `json:"first_rss_mb"`
	PeakRSSMB   int64 `json:"peak_rss_mb"`
	LastRSSMB   int64 `json:"last_rss_mb"`
	DeltaRSSMB  int64 `json:"delta_rss_mb"`

	FirstHeapUsedMB *int64 `json:"first_heap_used_mb,omitempty"`
	PeakHeapUsedMB  *int64 `json:"peak_heap_used_mb,omitempty"`
	LastHeapUsedMB  *int64 `json:"last_heap_used_mb,omitempty"`
	DeltaHeapUsedMB *int64 `json:"delta_heap_used_mb,omitempty"`

	FirstMetaspaceUsedMB *int64 `json:"first_metaspace_used_mb,omitempty"`
	PeakMetaspaceUsedMB  *int64 `json:"peak_metaspace_used_mb,omitempty"`
	LastMetaspaceUsedMB  *int64 `json:"last_metaspace_used_mb,omitempty"`
	DeltaMetaspaceUsedMB *int64 `json:"delta_metaspace_used_mb,omitempty"`

	FirstYoungGCCount *int64 `json:"first_young_gc_count,omitempty"`
	LastYoungGCCount  *int64 `json:"last_young_gc_count,omitempty"`
	DeltaYoungGCCount *int64 `json:"delta_young_gc_count,omitempty"`

	FirstTotalGCCount *int64 `json:"first_total_gc_count,omitempty"`
	LastTotalGCCount  *int64 `json:"last_total_gc_count,omitempty"`
	DeltaTotalGCCount *int64 `json:"delta_total_gc_count,omitempty"`

	FirstYoungGCTimeMs *int64 `json:"first_young_gc_time_ms,omitempty"`
	LastYoungGCTimeMs  *int64 `json:"last_young_gc_time_ms,omitempty"`
	DeltaYoungGCTimeMs *int64 `json:"delta_young_gc_time_ms,omitempty"`

	FirstOldGCTimeMs *int64 `json:"first_old_gc_time_ms,omitempty"`
	LastOldGCTimeMs  *int64 `json:"last_old_gc_time_ms,omitempty"`
	DeltaOldGCTimeMs *int64 `json:"delta_old_gc_time_ms,omitempty"`

	Association string `json:"association"`
}

type BuildTotals struct {
	Count        int `json:"count"`
	SuccessCount int `json:"success_count"`
	FailureCount int `json:"failure_count"`
}

type Response struct {
	Name        string       `json:"name"`
	Association string       `json:"association"`
	HostID      string       `json:"host_id"`
	OpenedAtMs  int64        `json:"opened_at_ms"`
	ClosedAtMs  *int64       `json:"closed_at_ms,omitempty"`
	Processes   []RSSSummary `json:"processes"`
	Builds      BuildTotals  `json:"builds"`
}

type Sample struct {
	AtMs             int64
	RSS              int64
	HeapUsedMB       *int64
	MetaspaceUsedMB  *int64
	YoungGCCount     *int64
	TotalGCCount     *int64
	YoungGCTimeMs    *int64
	OldGCTimeMs      *int64
}

func SummarizeRSS(ref ProcessRef, association string, samples []Sample) (RSSSummary, bool) {
	if len(samples) == 0 {
		return RSSSummary{}, false
	}
	sort.SliceStable(samples, func(i, j int) bool { return samples[i].AtMs < samples[j].AtMs })
	peak := samples[0].RSS
	for _, sample := range samples[1:] {
		if sample.RSS > peak {
			peak = sample.RSS
		}
	}
	first, last := samples[0].RSS, samples[len(samples)-1].RSS
	summary := RSSSummary{
		HostID: ref.HostID, PID: ref.PID, StartTimeMs: ref.StartTimeMs, ProcessType: ref.ProcessType,
		FirstSampleAtMs: samples[0].AtMs, LastSampleAtMs: samples[len(samples)-1].AtMs,
		SampleCount: len(samples), FirstRSSMB: first, PeakRSSMB: peak,
		LastRSSMB: last, DeltaRSSMB: last - first, Association: association,
	}
	summary.FirstHeapUsedMB, summary.PeakHeapUsedMB, summary.LastHeapUsedMB, summary.DeltaHeapUsedMB =
		windowGauge(samples, func(s Sample) *int64 { return s.HeapUsedMB })
	summary.FirstMetaspaceUsedMB, summary.PeakMetaspaceUsedMB, summary.LastMetaspaceUsedMB, summary.DeltaMetaspaceUsedMB =
		windowGauge(samples, func(s Sample) *int64 { return s.MetaspaceUsedMB })
	summary.FirstYoungGCCount, _, summary.LastYoungGCCount, summary.DeltaYoungGCCount =
		windowGauge(samples, func(s Sample) *int64 { return s.YoungGCCount })
	summary.FirstTotalGCCount, _, summary.LastTotalGCCount, summary.DeltaTotalGCCount =
		windowGauge(samples, func(s Sample) *int64 { return s.TotalGCCount })
	summary.FirstYoungGCTimeMs, _, summary.LastYoungGCTimeMs, summary.DeltaYoungGCTimeMs =
		windowGauge(samples, func(s Sample) *int64 { return s.YoungGCTimeMs })
	summary.FirstOldGCTimeMs, _, summary.LastOldGCTimeMs, summary.DeltaOldGCTimeMs =
		windowGauge(samples, func(s Sample) *int64 { return s.OldGCTimeMs })
	return summary, true
}

func windowGauge(samples []Sample, get func(Sample) *int64) (first, peak, last, delta *int64) {
	var have bool
	var firstV, peakV, lastV int64
	for _, sample := range samples {
		v := get(sample)
		if v == nil {
			continue
		}
		if !have {
			firstV, peakV, lastV = *v, *v, *v
			have = true
			continue
		}
		lastV = *v
		if *v > peakV {
			peakV = *v
		}
	}
	if !have {
		return nil, nil, nil, nil
	}
	d := lastV - firstV
	return int64Ptr(firstV), int64Ptr(peakV), int64Ptr(lastV), int64Ptr(d)
}

func int64Ptr(v int64) *int64 { return &v }
