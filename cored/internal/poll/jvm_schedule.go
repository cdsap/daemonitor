package poll

import (
	"context"
	"errors"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/observe"
	"github.com/shirou/gopsutil/v4/process"
)

type jvmResult struct {
	sample  heap.Sample
	failure string
	at      int64
}
type jvmSamples struct{ heap, advanced jvmResult }
type jvmState struct {
	samples jvmSamples
	next    [2]time.Time
	pending [2]bool
	ctx     context.Context
	cancel  context.CancelFunc
}
type jvmFamilyKey struct {
	incarnationKey
	family int
}

type jvmTask struct {
	key    incarnationKey
	state  *jvmState
	job    pendingJVM
	family int
}

// Close cancels running and queued probes and joins workers. Call after the last
// Snapshot. Each production command honors cancellation via exec.CommandContext.
func (c *Collector) Close() {
	c.jvmMu.Lock()
	c.jvmClosed = true
	for _, state := range c.jvmStates {
		state.cancel()
	}
	c.jvmQueues = [2][]jvmTask{}
	c.jvmMu.Unlock()
	c.jvmWG.Wait()
}

// scheduleJVM never waits for tools. FIFO queues and reserved family slots keep
// failed advanced probes from consuming heap capacity or bypassing older PIDs.
// Cadences are minimum intervals between admissions, dispatched on OS scan ticks.
func (c *Collector) scheduleJVM(ctx context.Context, now time.Time, jobs []pendingJVM) map[incarnationKey]jvmSamples {
	c.jvmMu.Lock()
	defer c.jvmMu.Unlock()
	result := make(map[incarnationKey]jvmSamples, len(jobs))
	if c.jvmClosed || ctx.Err() != nil || c.Heap == nil {
		return result
	}
	if c.jvmStates == nil {
		c.jvmStates = make(map[incarnationKey]*jvmState)
		c.jvmRunning = make(map[jvmFamilyKey]bool)
	}
	live := make(map[incarnationKey]bool, len(jobs))
	for _, job := range jobs {
		live[incarnationKey{job.pid, job.startMs}] = true
	}
	for key, state := range c.jvmStates {
		if !live[key] {
			state.cancel()
			delete(c.jvmStates, key)
		}
	}
	for f := 0; f < 2; f++ {
		queue := c.jvmQueues[f][:0]
		for _, task := range c.jvmQueues[f] {
			if live[task.key] {
				queue = append(queue, task)
			}
		}
		clear(c.jvmQueues[f][len(queue):])
		c.jvmQueues[f] = queue
	}
	for _, job := range jobs {
		key := incarnationKey{job.pid, job.startMs}
		// Unknown start times cannot safely identify a process lifetime.
		if job.startMs <= 0 {
			continue
		}
		state := c.jvmStates[key]
		if state == nil {
			state = &jvmState{}
			state.ctx, state.cancel = context.WithCancel(ctx)
			c.jvmStates[key] = state
		}
		result[key] = state.samples
		for f := 0; f < 2; f++ {
			if state.pending[f] || c.jvmRunning[jvmFamilyKey{key, f}] || now.Before(state.next[f]) {
				continue
			}
			state.pending[f] = true
			state.next[f] = now
			c.jvmQueues[f] = append(c.jvmQueues[f], jvmTask{key, state, job, f})
		}
	}
	c.startJVMWorkersLocked()
	return result
}

func (c *Collector) startJVMWorkersLocked() {
	if c.jvmClosed {
		return
	}
	concurrency := c.ProbeConcurrency
	if concurrency < 2 {
		concurrency = observe.DefaultJVMProbeConcurrency
	}
	limits := [2]int{(concurrency + 1) / 2, concurrency / 2}
	for f := 0; f < 2; f++ {
		for c.jvmActive[f] < limits[f] && len(c.jvmQueues[f]) > 0 {
			task := c.jvmQueues[f][0]
			c.jvmQueues[f][0] = jvmTask{}
			c.jvmQueues[f] = c.jvmQueues[f][1:]
			if task.state.ctx.Err() != nil {
				continue
			}
			c.jvmActive[f]++
			c.jvmRunning[jvmFamilyKey{task.key, f}] = true
			c.jvmWG.Add(1)
			go c.runJVMTask(task)
		}
	}
}

func (c *Collector) runJVMTask(task jvmTask) {
	defer c.jvmWG.Done()
	started := time.Now()
	var sample heap.Sample
	var failure string
	ctx := task.state.ctx
	// Revalidate before and after tools: a reused PID must never receive old data.
	start, err := currentStartTime(ctx, task.key.pid)
	if err != nil || start != task.key.createTimeMs {
		failure = heap.FailureStalePID
	} else if !sameUID(ctx, task.job.proc) {
		failure = heap.FailurePermissionDenied
	} else {
		sample, err = c.Heap.ProbeFamily(ctx, task.key.pid, task.key.createTimeMs, task.family == 1)
		failure = heap.FailureCategory(err)
		if err != nil {
			sample.Diagnostics = heap.FailureDiagnostics(err)
		}
	}
	result := jvmResult{sample: sample, failure: failure, at: time.Now().UnixMilli()}
	if c.Observe != nil {
		var probeErr error
		if failure != "" {
			probeErr = errors.New(failure)
		}
		timedOut := failure == heap.FailureTimeout
		for _, d := range sample.Diagnostics {
			if probeErr == nil {
				probeErr = errors.New(d.Category)
			}
			if d.Category == heap.FailureTimeout {
				timedOut = true
				probeErr = errors.New(heap.FailureTimeout)
			}
		}
		c.Observe.ObserveProbe(time.Since(started), probeErr, timedOut)
	}
	end, endErr := currentStartTime(ctx, task.key.pid)
	c.jvmMu.Lock()
	defer c.jvmMu.Unlock()
	c.jvmActive[task.family]--
	delete(c.jvmRunning, jvmFamilyKey{task.key, task.family})
	if c.jvmStates[task.key] == task.state {
		task.state.pending[task.family] = false
		cadence := c.Cadences.JVMHeapGC
		if task.family == 1 {
			cadence = c.Cadences.JVMJITCodeCache
		}
		if cadence <= 0 {
			cadence = observe.DefaultCadences().JVMHeapGC
			if task.family == 1 {
				cadence = observe.DefaultCadences().JVMJITCodeCache
			}
		}
		// Use the scan clock that admitted this attempt for deterministic cadence.
		task.state.next[task.family] = task.state.next[task.family].Add(cadence)
		if ctx.Err() == nil && endErr == nil && end == task.key.createTimeMs {
			if task.family == 0 {
				task.state.samples.heap = result
			} else {
				task.state.samples.advanced = result
			}
		}
	}
	c.startJVMWorkersLocked()
}

// gopsutil caches CreateTime on Process; use a fresh object for revalidation.
func currentStartTime(ctx context.Context, pid int32) (int64, error) {
	proc, err := process.NewProcessWithContext(ctx, pid)
	if err != nil {
		return 0, err
	}
	return proc.CreateTimeWithContext(ctx)
}
