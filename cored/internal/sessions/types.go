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

type RSSSummary struct {
	HostID      string `json:"host_id"`
	PID         int64  `json:"pid"`
	StartTimeMs int64  `json:"start_time_ms"`
	SampleCount int    `json:"sample_count"`
	FirstRSSMB  int64  `json:"first_rss_mb"`
	PeakRSSMB   int64  `json:"peak_rss_mb"`
	LastRSSMB   int64  `json:"last_rss_mb"`
	DeltaRSSMB  int64  `json:"delta_rss_mb"`
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
	AtMs int64
	RSS  int64
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
	return RSSSummary{
		HostID: ref.HostID, PID: ref.PID, StartTimeMs: ref.StartTimeMs,
		SampleCount: len(samples), FirstRSSMB: first, PeakRSSMB: peak,
		LastRSSMB: last, DeltaRSSMB: last - first, Association: association,
	}, true
}
