package platform

import (
	"testing"

	"github.com/shirou/gopsutil/v4/process"
)

func TestIOCountersMapLogicalAndDiskFamiliesSeparately(t *testing.T) {
	io := process.IOCountersStat{
		ReadBytes:      11,
		WriteBytes:     22,
		DiskReadBytes:  33,
		DiskWriteBytes: 44,
		ReadCount:      55,
		WriteCount:     66,
	}

	logicalRead, logicalWrite, diskRead, diskWrite, readOps, writeOps := ioMetricsFromCounters(io, 1234)
	assertMetricValue(t, logicalRead, 11)
	assertMetricValue(t, logicalWrite, 22)
	assertMetricValue(t, diskRead, 33)
	assertMetricValue(t, diskWrite, 44)
	assertMetricValue(t, readOps, 55)
	assertMetricValue(t, writeOps, 66)
}

func TestIOCountersZeroDiskValuesRemainValidCounters(t *testing.T) {
	io := process.IOCountersStat{}
	_, _, diskRead, diskWrite, _, _ := ioMetricsFromCounters(io, 1234)
	assertMetricValue(t, diskRead, 0)
	assertMetricValue(t, diskWrite, 0)
}

func assertMetricValue(t *testing.T, metric IntMetric, want int64) {
	t.Helper()
	if metric.Availability != Available || metric.Value == nil || *metric.Value != want {
		t.Fatalf("metric=%+v, want available value %d", metric, want)
	}
}
