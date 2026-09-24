package poll

import (
	"context"
	"os"
	"regexp"
	"runtime"
	"sync"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/process"
)

var (
	gradleDaemon  = regexp.MustCompile(`org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon|GradleDaemon`)
	kotlinDaemon  = regexp.MustCompile(`org\.jetbrains\.kotlin\.daemon|KotlinCompileDaemon`)
	testWorker    = regexp.MustCompile(`worker\.org\.gradle\.process\.internal\.worker\.GradleWorkerMain|org\.gradle\.process\.internal\.worker\.GradleWorkerMain`)
	wrapper       = regexp.MustCompile(`org\.gradle\.wrapper\.GradleWrapperMain|gradle-wrapper\.jar|org\.gradle\.appname=gradlew|(^|[\s/])gradlew(\s|$)`)
	javaBin       = regexp.MustCompile(`(^|[\s/])java(\s|$)`)
	gradleRuntime = regexp.MustCompile(`(-Dorg\.gradle\.|org\.gradle\.(launcher|process|tooling|internal|api|workers)\.|GradleWorkerMain)`)
)

type priorCPU struct {
	cpuTimeMs   float64
	wallClockMs int64
}

// Collector lists Gradle-related processes using OS process APIs (gopsutil ≈ OSHI).
type Collector struct {
	mu     sync.Mutex
	priors map[int32]priorCPU
	cpus   int
	Heap   *heap.Prober
}

func NewCollector() *Collector {
	n, err := cpu.Counts(true)
	if err != nil || n <= 0 {
		n = runtime.NumCPU()
	}
	return &Collector{
		priors: make(map[int32]priorCPU),
		cpus:   n,
		Heap:   heap.NewProber(),
	}
}

func (c *Collector) Snapshot(ctx context.Context) (model.Snapshot, error) {
	now := time.Now().UnixMilli()
	pids, err := process.PidsWithContext(ctx)
	if err != nil {
		return model.Snapshot{}, err
	}

	seen := make(map[int32]struct{}, len(pids))
	out := make([]model.Process, 0, 8)
	for _, pid := range pids {
		select {
		case <-ctx.Done():
			return model.Snapshot{}, ctx.Err()
		default:
		}

		p, err := process.NewProcessWithContext(ctx, pid)
		if err != nil {
			continue
		}
		cmdline, err := p.CmdlineWithContext(ctx)
		if err != nil || cmdline == "" {
			continue
		}
		kind := Classify(cmdline)
		if kind == "" {
			continue
		}
		seen[pid] = struct{}{}

		rssMB := int64(0)
		if mem, err := p.MemoryInfoWithContext(ctx); err == nil && mem != nil {
			rssMB = int64(mem.RSS) / (1024 * 1024)
		}

		var cpuPct *float64
		cpuTimeMs := 0.0
		if times, err := p.TimesWithContext(ctx); err == nil && times != nil {
			cpuTimeMs = (times.User + times.System) * 1000
			c.mu.Lock()
			prior, ok := c.priors[pid]
			c.mu.Unlock()
			if ok {
				wallDelta := now - prior.wallClockMs
				cpuDelta := cpuTimeMs - prior.cpuTimeMs
				if wallDelta > 0 && cpuDelta >= 0 && c.cpus > 0 {
					pct := (cpuDelta / float64(wallDelta)) / float64(c.cpus) * 100.0
					if pct < 0 {
						pct = 0
					}
					cpuPct = ptrFloat64(pct)
				}
			}
			c.mu.Lock()
			c.priors[pid] = priorCPU{cpuTimeMs: cpuTimeMs, wallClockMs: now}
			c.mu.Unlock()
		}

		parentPID := int32(0)
		if ppid, err := p.PpidWithContext(ctx); err == nil {
			parentPID = ppid
		}
		name, _ := p.NameWithContext(ctx)
		status := "RUNNING"
		if statuses, err := p.StatusWithContext(ctx); err == nil && len(statuses) > 0 {
			status = statuses[0]
		}
		startMs := int64(0)
		if create, err := p.CreateTimeWithContext(ctx); err == nil {
			startMs = create
		}

		var cwd *string
		if dir, err := p.CwdWithContext(ctx); err == nil && dir != "" {
			cwd = &dir
		}
		var project *string
		if kind == "GRADLE_WRAPPER" && cwd != nil {
			project = cwd
		}

		jvm := ParseJVMArgs(cmdline)
		usedMB, committedMB, maxMB, heapSampledAt, heapAvail := liveHeapFields(
			ctx, c.Heap, p, pid, kind, startMs, now,
		)
		out = append(out, model.Process{
			PID:              pid,
			ParentPID:        parentPID,
			Type:             kind,
			Name:             name,
			CommandLine:      RedactCommandLine(cmdline),
			WorkingDirectory: cwd,
			ProjectPath:      project,
			RSSMemoryMB:      rssMB,
			CPUPercent:       cpuPct,
			MaxHeapMB:        jvm.MaxHeapMB,
			MinHeapMB:        jvm.MinHeapMB,
			GC:               jvm.GC,
			HeapUsedMB:       usedMB,
			HeapCommittedMB:  committedMB,
			HeapMaxMB:        maxMB,
			HeapSampledAtMs:  heapSampledAt,
			HeapAvailable:    heapAvail,
			StartTimeMs:      startMs,
			Status:           status,
			Automated:        IsNonInteractive(cmdline),
			SampledAtMs:      now,
		})
	}

	c.mu.Lock()
	for pid := range c.priors {
		if _, ok := seen[pid]; !ok {
			delete(c.priors, pid)
		}
	}
	c.mu.Unlock()

	if c.Heap != nil {
		c.Heap.EvictMissing(seen)
	}

	return model.Snapshot{SampledAtMs: now, Processes: out}, nil
}

// ShouldProbeLiveHeap mirrors Kotlin: only Gradle/Kotlin daemons are probed.
func ShouldProbeLiveHeap(kind string) bool {
	return kind == "GRADLE_DAEMON" || kind == "KOTLIN_DAEMON"
}

func liveHeapFields(
	ctx context.Context,
	prober *heap.Prober,
	proc *process.Process,
	pid int32,
	kind string,
	startMs, now int64,
) (used, committed, max, sampledAt *int64, available bool) {
	sampled := now
	if !ShouldProbeLiveHeap(kind) {
		return nil, nil, nil, &sampled, false
	}
	// Self-attach via jcmd can deadlock HotSpot; skip our own PID.
	if int(pid) == os.Getpid() {
		return nil, nil, nil, &sampled, false
	}
	if !sameUID(ctx, proc) {
		return nil, nil, nil, &sampled, false
	}
	if prober == nil {
		return nil, nil, nil, &sampled, false
	}
	sample, err := prober.SampleFor(ctx, pid, startMs)
	if err != nil {
		return nil, nil, nil, &sampled, false
	}
	u := sample.UsedMB
	c := sample.CommittedMB
	return &u, &c, nil, &sampled, true
}

func sameUID(ctx context.Context, proc *process.Process) bool {
	self := os.Getuid()
	if self < 0 {
		// Windows / unsupported: let the tool probe fail rather than skip everyone.
		return true
	}
	uids, err := proc.UidsWithContext(ctx)
	if err != nil || len(uids) == 0 {
		return true
	}
	return int(uids[0]) == self
}

// Classify mirrors Kotlin GradleProcessClassifier.
func Classify(commandLine string) string {
	switch {
	case gradleDaemon.MatchString(commandLine):
		return "GRADLE_DAEMON"
	case kotlinDaemon.MatchString(commandLine):
		return "KOTLIN_DAEMON"
	case testWorker.MatchString(commandLine):
		return "TEST_WORKER"
	case wrapper.MatchString(commandLine):
		return "GRADLE_WRAPPER"
	case javaBin.MatchString(commandLine) && gradleRuntime.MatchString(commandLine):
		return "JAVA_GRADLE_RELATED"
	default:
		return ""
	}
}

func Platform() string { return runtime.GOOS + "/" + runtime.GOARCH }
