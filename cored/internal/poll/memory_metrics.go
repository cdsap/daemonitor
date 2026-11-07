package poll

import "time"

// proportionalMemoryInterval deliberately keeps smaps_rollup off the normal
// RSS cadence. It is a comparatively expensive, optional measurement.
const proportionalMemoryInterval = 5 * time.Second

type platformMemory struct {
	privateCommittedBytes  *int64
	proportionalBytes      *int64
	physicalFootprintBytes *int64
	peakResidentBytes      *int64
	proportionalSampledAt  *int64
}

func shouldSampleProportional(now, last int64) bool {
	return last == 0 || now-last >= proportionalMemoryInterval.Milliseconds()
}
