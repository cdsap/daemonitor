package poll

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestLiveHeapUnavailableHasNoMeasurementTime(t *testing.T) {
	used, committed, _, sampledAt, available := liveHeapFieldsFromSample(heap.Sample{}, heap.FailureTimeout)
	if used != nil || committed != nil || sampledAt != nil || available {
		t.Fatalf("failed probe claimed a measurement: used=%v committed=%v at=%v available=%v", used, committed, sampledAt, available)
	}
}

func TestJVMCacheTimeRemainsSeparateFromSnapshotTime(t *testing.T) {
	c := NewCollector()
	defer c.Close()
	c.Cadences.JVMHeapGC = 10 * time.Second
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
	job := scheduleJob(t, 0)
	jobs := []pendingJVM{job}
	// Trigger initial acquisition at 1000ms and wait for async worker completion.
	c.applyJVMProbes(context.Background(), 1000, []model.Process{{PID: job.pid, StartTimeMs: job.startMs, SampledAtMs: 1000}}, jobs)
	c.jvmWG.Wait()
	for _, at := range []int64{1000, 3000, 5000, 11000} {
		now = time.UnixMilli(at)
		rows := []model.Process{{PID: job.pid, StartTimeMs: job.startMs, SampledAtMs: at}}
		c.applyJVMProbes(context.Background(), at, rows, jobs)
		if at == 11000 {
			c.jvmWG.Wait()
			rows = []model.Process{{PID: job.pid, StartTimeMs: job.startMs, SampledAtMs: at}}
			c.applyJVMProbes(context.Background(), at, rows, jobs)
		}
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
