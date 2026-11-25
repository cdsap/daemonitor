package poll

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/shirou/gopsutil/v4/process"
)

func TestLiveHeapUnavailableHasNoMeasurementTime(t *testing.T) {
	used, committed, _, sampledAt, available := liveHeapFieldsFromSample(heap.Sample{}, heap.FailureTimeout)
	if used != nil || committed != nil || sampledAt != nil || available {
		t.Fatalf("failed probe claimed a measurement: used=%v committed=%v at=%v available=%v", used, committed, sampledAt, available)
	}
}

func TestJVMCacheTimeRemainsSeparateFromSnapshotTime(t *testing.T) {
	c := NewCollector()
	now := time.UnixMilli(1000)
	c.Heap.Now = func() time.Time { return now }
	c.Heap.LookPath = func(name string) (string, error) {
		if name == "jcmd" {
			return name, nil
		}
		return "", errors.New("missing tool")
	}
	c.Heap.Runner = func(context.Context, string, ...string) (string, error) {
		return "garbage-first heap total reserved 262144K, committed 262144K, used 36447K\n", nil
	}
	for _, at := range []int64{1000, 3000, 5000, 11000} {
		now = time.UnixMilli(at)
		rows := []model.Process{{PID: 7, StartTimeMs: 99, SampledAtMs: at}}
		c.applyJVMProbes(context.Background(), rows, []pendingJVM{{idx: 0, proc: &process.Process{Pid: 2147483647}, pid: 7, startMs: 99}})
		want := int64(1000)
		if at == 11000 {
			want = at
		}
		p := rows[0]
		if !p.HeapAvailable || p.HeapSampledAtMs == nil || *p.HeapSampledAtMs != want || p.JVMMeasurementTimes["heap"] != want || p.SampledAtMs != at {
			t.Fatalf("snapshot=%d want measurement=%d got %+v", at, want, p)
		}
	}
}
