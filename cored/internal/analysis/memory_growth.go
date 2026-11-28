// Package analysis contains rendering-neutral diagnostics for persisted daemon data.
package analysis

import (
	"cmp"
	"slices"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

// Assessment is deliberately a diagnostic signal, not a memory-leak verdict.
type Assessment string

const (
	InsufficientData Assessment = "INSUFFICIENT_DATA"
	Stable           Assessment = "STABLE"
	Growing          Assessment = "GROWING"
	HighGrowth       Assessment = "HIGH_GROWTH"
)

const (
	minimumSamples          = 5
	minimumHeapSamples      = 4
	minimumWindowMs         = 2 * 60 * 1000
	maximumWindowMs         = 30 * 60 * 1000
	baselineBucketSize      = 3
	noiseToleranceMB        = 32
	minimumGrowthMB         = 256
	minimumGrowthPercent    = 20.0
	highGrowthMB            = 1024
	minimumGrowingRatio     = 0.70
	maximumSurroundingGapMs = 2 * 60 * 1000
)

// Build is the timestamp subset needed for retention correlation.
type Build struct {
	ID          string
	PID         int32
	StartTimeMs int64
	EndTimeMs   *int64
}

// Retention reports RSS (and optional live heap) around one completed build.
// It is omitted when a complete build window or sufficiently close before/after
// RSS samples are absent. Heap fields remain nil when valid heap samples are
// unavailable around the build (never treating missing heap as zero).
type Retention struct {
	BuildID        string
	BeforeMB       int64
	PeakMB         int64
	AfterMB        int64
	RetainedMB     int64
	HeapBeforeMB   *int64
	HeapPeakMB     *int64
	HeapAfterMB    *int64
	HeapRetainedMB *int64
}

// Metric contains the bounded-window summary for one memory signal.
type Metric struct {
	Available           bool
	SampleCount         int
	BaselineMB          int64
	CurrentMB           int64
	PeakMB              int64
	AbsoluteGrowthMB    int64
	GrowthPercent       float64
	GrowthRateMBPerHour float64
	Values              []int64
}

// Result is the complete time-based memory-growth diagnosis for one process
// incarnation. Assessment is based on RSS; Heap is an independent optional
// signal and never treats missing heap samples as zero.
type Result struct {
	SampleCount   int
	WindowStartMs int64
	WindowEndMs   int64
	RSS           Metric
	Heap          Metric
	Assessment    Assessment
}

// Analyze examines recent samples for pid and one process incarnation. A
// non-zero startTimeMs is an incarnation boundary; samples from other PIDs or
// start times are excluded before any heuristic is applied.
func Analyze(samples []model.Process, pid int32, startTimeMs int64) Result {
	filtered := make([]model.Process, 0, len(samples))
	for _, sample := range samples {
		if sample.PID != pid || sample.SampledAtMs <= 0 || sample.RSSMemoryMB < 0 {
			continue
		}
		if startTimeMs > 0 && sample.StartTimeMs != startTimeMs {
			continue
		}
		filtered = append(filtered, sample)
	}
	slices.SortStableFunc(filtered, func(a, b model.Process) int { return cmp.Compare(a.SampledAtMs, b.SampledAtMs) })
	if len(filtered) > 0 {
		end := filtered[len(filtered)-1].SampledAtMs
		first := 0
		for first < len(filtered) && end-filtered[first].SampledAtMs > maximumWindowMs {
			first++
		}
		filtered = filtered[first:]
	}

	result := Result{SampleCount: len(filtered)}
	if len(filtered) == 0 {
		result.Assessment = InsufficientData
		return result
	}
	result.WindowStartMs = filtered[0].SampledAtMs
	result.WindowEndMs = filtered[len(filtered)-1].SampledAtMs
	result.RSS = metric(valuesFor(filtered, func(p model.Process) (int64, bool) { return p.RSSMemoryMB, true }), result.WindowEndMs-result.WindowStartMs)
	if result.SampleCount < minimumSamples || result.WindowEndMs-result.WindowStartMs < minimumWindowMs {
		result.Assessment = InsufficientData
		return result
	}
	result.Heap = metric(valuesFor(filtered, func(p model.Process) (int64, bool) {
		if p.HeapUsedMB == nil || *p.HeapUsedMB < 0 {
			return 0, false
		}
		return *p.HeapUsedMB, true
	}), result.WindowEndMs-result.WindowStartMs)
	result.Assessment = assess(result.RSS)
	return result
}

// AnalyzeBuildRetention correlates only completed, daemon-scoped builds with
// samples from the same process incarnation.
func AnalyzeBuildRetention(samples []model.Process, builds []Build, pid int32, startTimeMs int64) []Retention {
	valid := make([]model.Process, 0, len(samples))
	for _, sample := range samples {
		if sample.PID == pid && sample.StartTimeMs == startTimeMs && sample.SampledAtMs > 0 && sample.RSSMemoryMB >= 0 {
			valid = append(valid, sample)
		}
	}
	slices.SortFunc(valid, func(a, b model.Process) int { return cmp.Compare(a.SampledAtMs, b.SampledAtMs) })
	var result []Retention
	for _, build := range builds {
		if build.PID != pid || build.StartTimeMs <= 0 || build.EndTimeMs == nil || *build.EndTimeMs <= build.StartTimeMs {
			continue
		}
		var before, after *model.Process
		peak := int64(-1)
		var heapBefore, heapAfter *model.Process
		heapPeak := int64(-1)
		for i := range valid {
			sample := &valid[i]
			hasHeap := sample.HeapUsedMB != nil && *sample.HeapUsedMB >= 0
			switch {
			case sample.SampledAtMs < build.StartTimeMs:
				before = sample
				if hasHeap {
					heapBefore = sample
				}
			case sample.SampledAtMs >= build.StartTimeMs && sample.SampledAtMs <= *build.EndTimeMs:
				if sample.RSSMemoryMB > peak {
					peak = sample.RSSMemoryMB
				}
				if hasHeap && *sample.HeapUsedMB > heapPeak {
					heapPeak = *sample.HeapUsedMB
				}
			case sample.SampledAtMs > *build.EndTimeMs:
				if after == nil {
					after = sample
				}
				if hasHeap && heapAfter == nil {
					heapAfter = sample
				}
			}
		}
		if before == nil || after == nil || peak < 0 || build.StartTimeMs-before.SampledAtMs > maximumSurroundingGapMs || after.SampledAtMs-*build.EndTimeMs > maximumSurroundingGapMs {
			continue
		}
		entry := Retention{
			BuildID:    build.ID,
			BeforeMB:   before.RSSMemoryMB,
			PeakMB:     peak,
			AfterMB:    after.RSSMemoryMB,
			RetainedMB: after.RSSMemoryMB - before.RSSMemoryMB,
		}
		if heapBefore != nil && heapAfter != nil && heapPeak >= 0 &&
			build.StartTimeMs-heapBefore.SampledAtMs <= maximumSurroundingGapMs &&
			heapAfter.SampledAtMs-*build.EndTimeMs <= maximumSurroundingGapMs {
			hb := *heapBefore.HeapUsedMB
			hp := heapPeak
			ha := *heapAfter.HeapUsedMB
			hr := ha - hb
			entry.HeapBeforeMB = &hb
			entry.HeapPeakMB = &hp
			entry.HeapAfterMB = &ha
			entry.HeapRetainedMB = &hr
		}
		result = append(result, entry)
	}
	return result
}

func valuesFor(samples []model.Process, read func(model.Process) (int64, bool)) []int64 {
	values := make([]int64, 0, len(samples))
	for _, sample := range samples {
		if value, ok := read(sample); ok {
			values = append(values, value)
		}
	}
	return values
}

func metric(values []int64, elapsedMs int64) Metric {
	if len(values) == 0 {
		return Metric{}
	}
	if len(values) > 1 && elapsedMs <= 0 {
		return Metric{SampleCount: len(values), Values: values}
	}
	bucket := values
	if len(bucket) > baselineBucketSize {
		bucket = bucket[:baselineBucketSize]
	}
	baseline := median(bucket)
	current := values[len(values)-1]
	peak := slices.Max(values)
	growth := current - baseline
	percent := 0.0
	if baseline > 0 {
		percent = float64(growth) * 100 / float64(baseline)
	}
	rate := 0.0
	if elapsedMs > 0 {
		rate = float64(growth) / (float64(elapsedMs) / (60 * 60 * 1000))
	}
	return Metric{Available: len(values) >= minimumHeapSamples, SampleCount: len(values), BaselineMB: baseline, CurrentMB: current, PeakMB: peak, AbsoluteGrowthMB: growth, GrowthPercent: percent, GrowthRateMBPerHour: rate, Values: slices.Clone(values)}
}

func assess(metric Metric) Assessment {
	if metric.SampleCount < minimumSamples {
		return InsufficientData
	}
	if metric.AbsoluteGrowthMB < minimumGrowthMB || metric.GrowthPercent < minimumGrowthPercent || metric.CurrentMB <= metric.BaselineMB+noiseToleranceMB || increasingRatio(metric.Values) < minimumGrowingRatio {
		return Stable
	}
	if metric.AbsoluteGrowthMB >= highGrowthMB {
		return HighGrowth
	}
	return Growing
}

func increasingRatio(values []int64) float64 {
	if len(values) < 2 {
		return 0
	}
	good := 0
	for i := 1; i < len(values); i++ {
		if values[i]-values[i-1] >= -noiseToleranceMB {
			good++
		}
	}
	return float64(good) / float64(len(values)-1)
}

func median(values []int64) int64 {
	ordered := slices.Clone(values)
	slices.Sort(ordered)
	return ordered[len(ordered)/2]
}
