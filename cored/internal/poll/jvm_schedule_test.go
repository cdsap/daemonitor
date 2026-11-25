package poll

import (
	"context"
	"fmt"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/shirou/gopsutil/v4/process"
)

func TestBlockedJVMDoesNotDelayPublication(t *testing.T) {
	c := NewCollector()
	defer c.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	c.Heap.LookPath = func(name string) (string, error) { return name, nil }
	c.Heap.Runner = func(ctx context.Context, _ string, _ ...string) (string, error) {
		<-ctx.Done()
		return "", ctx.Err()
	}
	exe, _ := os.Executable()
	pid := startBazelProbeProcess(t, exe)
	p, err := process.NewProcess(pid)
	if err != nil {
		t.Fatal(err)
	}
	start, _ := p.CreateTime()
	rows := []model.Process{{PID: pid}}
	done := make(chan struct{})
	go func() {
		c.applyJVMProbes(ctx, time.Now().UnixMilli(), rows, []pendingJVM{{pid: pid, startMs: start, proc: p}})
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(100 * time.Millisecond):
		t.Fatal("JVM work blocked OS publication for 100ms")
	}
	if rows[0].HeapAvailable || rows[0].HeapSampledAtMs != nil {
		t.Fatal("pending heap presented as measured")
	}
}

func scheduleJob(t *testing.T, idx int) pendingJVM {
	t.Helper()
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	pid := startBazelProbeProcess(t, exe)
	p, err := process.NewProcess(pid)
	if err != nil {
		t.Fatal(err)
	}
	start, err := p.CreateTime()
	if err != nil {
		t.Fatal(err)
	}
	return pendingJVM{idx: idx, proc: p, pid: pid, startMs: start}
}

func toolOutput(_ context.Context, name string, args ...string) (string, error) {
	switch {
	case slices.Contains(args, "GC.heap_info"):
		return "garbage-first heap total reserved 262144K, committed 262144K, used 36447K\nMetaspace used 1024K, committed 2048K, reserved 4096K\n", nil
	case slices.Contains(args, "-gc"):
		return "S0C S1C S0U S1U EC EU OC OU MC MU YGC YGCT FGC FGCT GCT\n0 0 0 0 10 5 20 10 2048 1024 8 1.2 2 2.3 3.5\n", nil
	case slices.Contains(args, "-compiler"):
		return "Compiled Failed Invalid Time\n10 1 2 3.5\n", nil
	case slices.Contains(args, "-class"):
		return "Loaded Bytes Unloaded Bytes Time\n100 1024 2 20 0.5\n", nil
	case slices.Contains(args, "-flags"):
		return "-XX:+UseG1GC", nil
	default:
		return "", nil
	}
}

func TestIndependentJVMToolCadencesAndMeasurementTimes(t *testing.T) {
	c := NewCollector()
	defer c.Close()
	c.Cadences.JVMHeapGC = 3 * time.Second
	c.Cadences.JVMJITCodeCache = 7 * time.Second
	c.Heap.LookPath = func(name string) (string, error) { return name, nil }
	var mu sync.Mutex
	calls := map[string]int{}
	c.Heap.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		mu.Lock()
		calls[strings.Join(args, " ")]++
		mu.Unlock()
		return toolOutput(ctx, name, args...)
	}
	job := scheduleJob(t, 0)
	jobs := []pendingJVM{job}
	now := time.Unix(1000, 0)
	rows := []model.Process{{PID: job.pid}}
	scan := func(at time.Time) { c.applyJVMProbes(context.Background(), at.UnixMilli(), rows, jobs); c.jvmWG.Wait() }
	scan(now)
	scan(now.Add(2 * time.Second))
	firstHeap, firstAdvanced := *rows[0].HeapSampledAtMs, *rows[0].JVMAdvancedSampledAtMs
	if !rows[0].HeapAvailable || rows[0].JIT == nil {
		t.Fatal("completed families unavailable")
	}
	scan(now.Add(3 * time.Second))
	scan(now.Add(6 * time.Second))
	scan(now.Add(7 * time.Second))
	mu.Lock()
	defer mu.Unlock()
	pid := strconv.Itoa(int(job.pid))
	for command, want := range map[string]int{
		pid + " GC.heap_info": 3, "-gc " + pid: 3, "-compiler " + pid: 2, "-class " + pid: 2, pid + " Compiler.codecache": 2, pid + " PerfCounter.print": 2,
	} {
		if calls[command] != want {
			t.Errorf("tool %s calls=%d want %d", command, calls[command], want)
		}
	}
	// A cached result must retain its real measurement time, never the scan clock.
	if firstHeap == now.Add(2*time.Second).UnixMilli() || firstAdvanced == now.Add(2*time.Second).UnixMilli() {
		t.Fatal("cached timestamps advanced with scan")
	}
	if *rows[0].HeapSampledAtMs < firstHeap || *rows[0].JVMAdvancedSampledAtMs < firstAdvanced {
		t.Fatal("measurement timestamps regressed")
	}
}

func TestJVMQueuesReserveHeapCapacityAndAdvanceOtherProcesses(t *testing.T) {
	c := NewCollector()
	defer c.Close()
	c.Heap.Timeout = 20 * time.Millisecond
	c.Heap.LookPath = func(name string) (string, error) { return name, nil }
	var mu sync.Mutex
	heapCalls := map[int32]int{}
	advancedCalls := 0
	c.Heap.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		if slices.Contains(args, "-compiler") {
			mu.Lock()
			advancedCalls++
			mu.Unlock()
			<-ctx.Done()
			return "", ctx.Err()
		}
		if slices.Contains(args, "GC.heap_info") {
			var pid int32
			_, _ = fmt.Sscan(args[0], &pid)
			mu.Lock()
			heapCalls[pid]++
			mu.Unlock()
		}
		return toolOutput(ctx, name, args...)
	}
	jobs := []pendingJVM{scheduleJob(t, 0), scheduleJob(t, 1), scheduleJob(t, 2)}
	rows := make([]model.Process, 3)
	c.applyJVMProbes(context.Background(), time.Now().UnixMilli(), rows, jobs)
	c.jvmWG.Wait()
	c.applyJVMProbes(context.Background(), time.Now().UnixMilli(), rows, jobs)
	mu.Lock()
	defer mu.Unlock()
	for _, job := range jobs {
		if heapCalls[job.pid] != 1 || !rows[job.idx].HeapAvailable {
			t.Errorf("heap starved for %d", job.pid)
		}
	}
	if advancedCalls != 3 {
		t.Fatalf("advanced FIFO did not advance: %d", advancedCalls)
	}
	if c.Observe.Snapshot().ProbeTimeoutTotal != 3 {
		t.Fatal("advanced timeouts not reported")
	}
}

func TestJVMNoOverlapAndShutdownCancelsQueuedWork(t *testing.T) {
	c := NewCollector()
	c.Heap.LookPath = func(name string) (string, error) { return name, nil }
	entered := make(chan struct{}, 10)
	c.Heap.Runner = func(ctx context.Context, _ string, _ ...string) (string, error) {
		entered <- struct{}{}
		<-ctx.Done()
		return "", ctx.Err()
	}
	jobs := []pendingJVM{scheduleJob(t, 0), scheduleJob(t, 1)}
	rows := make([]model.Process, 2)
	now := time.Now()
	c.applyJVMProbes(context.Background(), now.UnixMilli(), rows, jobs)
	for i := 0; i < 2; i++ {
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("workers did not start")
		}
	}
	for i := 0; i < 10; i++ {
		c.applyJVMProbes(context.Background(), now.Add(time.Hour).UnixMilli(), rows, jobs)
	}
	select {
	case <-entered:
		t.Fatal("overlapping family probe")
	default:
	}
	done := make(chan struct{})
	go func() { c.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("shutdown did not cancel workers")
	}
	// Runners may be called with an already-cancelled context by best-effort
	// supplements, but queued process 2 must never reach a tool.
	c.jvmMu.Lock()
	defer c.jvmMu.Unlock()
	if c.jvmActive != [2]int{} || len(c.jvmQueues[0])+len(c.jvmQueues[1]) != 0 {
		t.Fatal("work remained after shutdown")
	}
}

func TestJVMIncarnationEvictionAndUnavailableTimes(t *testing.T) {
	c := NewCollector()
	defer c.Close()
	c.Heap.LookPath = func(name string) (string, error) { return name, nil }
	c.Heap.Runner = toolOutput
	job := scheduleJob(t, 0)
	now := time.Now()
	rows := make([]model.Process, 1)
	c.applyJVMProbes(context.Background(), now.UnixMilli(), rows, []pendingJVM{job})
	c.jvmWG.Wait()
	c.applyJVMProbes(context.Background(), now.UnixMilli(), rows, []pendingJVM{job})
	measured := *rows[0].HeapSampledAtMs
	c.applyJVMProbes(context.Background(), now.Add(time.Second).UnixMilli(), rows, []pendingJVM{job})
	if *rows[0].HeapSampledAtMs != measured {
		t.Fatal("cached measurement timestamp changed")
	}
	// Simulate PID reuse. Validation rejects the new key against the live process.
	job.startMs++
	rows = make([]model.Process, 1)
	c.applyJVMProbes(context.Background(), now.UnixMilli(), rows, []pendingJVM{job})
	if rows[0].HeapAvailable || rows[0].HeapSampledAtMs != nil {
		t.Fatal("sample crossed PID lifetime")
	}
	c.jvmWG.Wait()
	c.applyJVMProbes(context.Background(), now.UnixMilli(), rows, []pendingJVM{job})
	if rows[0].HeapAvailable || rows[0].HeapUsedMB != nil || rows[0].HeapSampledAtMs != nil {
		t.Fatal("rejected incarnation presented as measured")
	}
	c.scheduleJVM(context.Background(), now, nil)
	if len(c.jvmStates) != 0 {
		t.Fatal("dead incarnation retained")
	}
}

func TestJVMFailedAttemptReplacesCachedSuccessWithHonestTime(t *testing.T) {
	c := NewCollector()
	defer c.Close()
	c.Heap.LookPath = func(name string) (string, error) { return name, nil }
	c.Heap.Runner = toolOutput
	job := scheduleJob(t, 0)
	jobs := []pendingJVM{job}
	now := time.Now()
	rows := make([]model.Process, 1)
	c.applyJVMProbes(context.Background(), now.UnixMilli(), rows, jobs)
	c.jvmWG.Wait()
	c.applyJVMProbes(context.Background(), now.UnixMilli(), rows, jobs)
	if !rows[0].HeapAvailable {
		t.Fatal("initial heap unavailable")
	}
	first := *rows[0].HeapSampledAtMs
	c.Heap.Runner = func(context.Context, string, ...string) (string, error) { return "", context.DeadlineExceeded }
	retry := now.Add(11 * time.Second)
	c.applyJVMProbes(context.Background(), retry.UnixMilli(), rows, jobs)
	c.jvmWG.Wait()
	rows = make([]model.Process, 1)
	c.applyJVMProbes(context.Background(), retry.UnixMilli(), rows, jobs)
	_, hasHeapTime := rows[0].JVMMeasurementTimes["heap"]
	_, hasGCTime := rows[0].JVMMeasurementTimes["gc"]
	if rows[0].HeapAvailable || rows[0].HeapUsedMB != nil || rows[0].HeapSampledAtMs != nil || hasHeapTime || hasGCTime || first <= 0 {
		t.Fatalf("failed measurement=%+v", rows[0])
	}
	for _, d := range rows[0].HeapProbeDiagnostics {
		if d.Category != heap.FailureTimeout && d.Category != heap.FailureProbeError && d.Category != heap.FailureParseError {
			t.Fatalf("unsanitized failure=%+v", d)
		}
	}
}

func TestJVMRediscoveryDoesNotOverlapCancelledIncarnation(t *testing.T) {
	c := NewCollector()
	c.ProbeConcurrency = 4
	// Hold cancelled commands long enough to exercise rediscovery while spare
	// family slots are available. Close must still join these attempts.
	release := make(chan struct{})
	defer c.Close()
	defer close(release)
	c.Heap.LookPath = func(name string) (string, error) { return name, nil }
	entered := make(chan struct{}, 10)
	c.Heap.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		entered <- struct{}{}
		<-release
		return "", ctx.Err()
	}
	job := scheduleJob(t, 0)
	now := time.Now()
	rows := make([]model.Process, 1)
	c.applyJVMProbes(context.Background(), now.UnixMilli(), rows, []pendingJVM{job})
	for i := 0; i < 2; i++ {
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("workers did not start")
		}
	}
	c.scheduleJVM(context.Background(), now, nil)
	c.applyJVMProbes(context.Background(), now.UnixMilli(), rows, []pendingJVM{job})
	select {
	case <-entered:
		t.Fatal("rediscovery overlapped cancelled family")
	case <-time.After(20 * time.Millisecond):
	}
	if rows[0].HeapSampledAtMs != nil || rows[0].JVMAdvancedSampledAtMs != nil {
		t.Fatal("rediscovery reused evicted samples")
	}
}
