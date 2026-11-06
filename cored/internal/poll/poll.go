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
	gradleDaemon       = regexp.MustCompile(`org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon|GradleDaemon`)
	kotlinDaemon       = regexp.MustCompile(`org\.jetbrains\.kotlin\.daemon|KotlinCompileDaemon`)
	gradleWorker       = regexp.MustCompile(`worker\.org\.gradle\.process\.internal\.worker\.GradleWorkerMain|org\.gradle\.process\.internal\.worker\.GradleWorkerMain`)
	gradleTestExecutor = regexp.MustCompile(`Gradle Test Executor`)
	gradleWorkerDaemon = regexp.MustCompile(`Gradle Worker Daemon`)
	wrapper            = regexp.MustCompile(`org\.gradle\.wrapper\.GradleWrapperMain|gradle-wrapper\.jar|org\.gradle\.appname=gradlew|(^|[\s/])gradlew(\s|$)`)
	javaBin            = regexp.MustCompile(`(^|[\s/])java(\s|$)`)
	gradleRuntime      = regexp.MustCompile(`(-Dorg\.gradle\.|org\.gradle\.(launcher|process|tooling|internal|api|workers)\.|GradleWorkerMain)`)
)

type priorCPU struct {
	userSeconds   float64
	systemSeconds float64
	totalSeconds  float64
	wallClock     time.Time
}

type incarnationKey struct {
	pid          int32
	createTimeMs int64
}

func cpuUsage(now time.Time, cpus int, current priorCPU, prior priorCPU, ok bool) (percent, cores *float64) {
	if !ok {
		return nil, nil
	}
	wallSeconds := now.Sub(prior.wallClock).Seconds()
	cpuDelta := current.totalSeconds - prior.totalSeconds
	if wallSeconds <= 0 || cpuDelta < 0 {
		return nil, nil
	}
	used := cpuDelta / wallSeconds
	cores = ptrFloat64(used)
	if cpus > 0 {
		percent = ptrFloat64(used / float64(cpus) * 100.0)
	}
	return percent, cores
}

// Collector lists Gradle-related processes using OS process APIs (gopsutil ≈ OSHI).
type Collector struct {
	mu     sync.Mutex
	priors map[incarnationKey]priorCPU
	cpus   int
	Heap   *heap.Prober
}

func NewCollector() *Collector {
	n, err := cpu.Counts(true)
	if err != nil || n <= 0 {
		n = runtime.NumCPU()
	}
	return &Collector{
		priors: make(map[incarnationKey]priorCPU),
		cpus:   n,
		Heap:   heap.NewProber(),
	}
}

func (c *Collector) Snapshot(ctx context.Context) (model.Snapshot, error) {
	nowTime := time.Now()
	now := nowTime.UnixMilli()
	pids, err := process.PidsWithContext(ctx)
	if err != nil {
		return model.Snapshot{}, err
	}

	seen := make(map[incarnationKey]struct{}, len(pids))
	seenPIDs := make(map[int32]struct{}, len(pids))
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
		seenPIDs[pid] = struct{}{}
		startMs := int64(0)
		if create, err := p.CreateTimeWithContext(ctx); err == nil {
			startMs = create
		}
		rssMB := int64(0)
		osMetrics := collectOSMetrics(ctx, p)
		if mem, err := p.MemoryInfoWithContext(ctx); err == nil && mem != nil {
			rssMB = int64(mem.RSS) / (1024 * 1024)
		}

		var cpuPct, cpuCoresUsed *float64
		var cpuUserSeconds, cpuSystemSeconds, cpuTotalSeconds *float64
		if times, err := p.TimesWithContext(ctx); err == nil && times != nil {
			userSeconds := times.User
			systemSeconds := times.System
			totalSeconds := userSeconds + systemSeconds
			if startMs > 0 {
				key := incarnationKey{pid: pid, createTimeMs: startMs}
				seen[key] = struct{}{}
				c.mu.Lock()
				prior, ok := c.priors[key]
				c.mu.Unlock()
				monotonic := !ok || (userSeconds >= prior.userSeconds && systemSeconds >= prior.systemSeconds && totalSeconds >= prior.totalSeconds)
				if monotonic {
					cpuUserSeconds = ptrFloat64(userSeconds)
					cpuSystemSeconds = ptrFloat64(systemSeconds)
					cpuTotalSeconds = ptrFloat64(totalSeconds)
					cpuPct, cpuCoresUsed = cpuUsage(nowTime, c.cpus, priorCPU{
						userSeconds: userSeconds, systemSeconds: systemSeconds, totalSeconds: totalSeconds,
						wallClock: nowTime,
					}, prior, ok)
					c.mu.Lock()
					c.priors[key] = priorCPU{
						userSeconds: userSeconds, systemSeconds: systemSeconds,
						totalSeconds: totalSeconds, wallClock: nowTime,
					}
					c.mu.Unlock()
				}
			} else {
				cpuUserSeconds = ptrFloat64(userSeconds)
				cpuSystemSeconds = ptrFloat64(systemSeconds)
				cpuTotalSeconds = ptrFloat64(totalSeconds)
			}
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
		var cwd *string
		if dir, err := p.CwdWithContext(ctx); err == nil && dir != "" {
			cwd = &dir
		}
		var project *string
		if kind == "GRADLE_WRAPPER" && cwd != nil {
			project = cwd
		}

		jvm := ParseJVMArgs(cmdline)
		jvmMetrics, probeFailure := collectJVMMetrics(ctx, c.Heap, p, pid, kind, startMs)
		usedMB, committedMB, maxMB, heapSampledAt, heapAvail := liveHeapFieldsFromSample(now, jvmMetrics, probeFailure)
		if jvm.GC == nil {
			jvm.GC = jvmMetrics.GC
		}
		out = append(out, model.Process{
			PID:              pid,
			ParentPID:        parentPID,
			Type:             kind,
			Name:             name,
			CommandLine:      RedactCommandLine(cmdline),
			WorkingDirectory: cwd,
			ProjectPath:      project,
			RSSMemoryMB:      rssMB,
			VirtualMemoryMB:  osMetrics.virtualMemoryMB, SwapMemoryMB: osMetrics.swapMemoryMB,
			ThreadCount: osMetrics.threadCount, ReadBytes: osMetrics.readBytes, WriteBytes: osMetrics.writeBytes,
			ReadOperations: osMetrics.readOperations, WriteOperations: osMetrics.writeOperations,
			MinorPageFaults: osMetrics.minorPageFaults, MajorPageFaults: osMetrics.majorPageFaults,
			VoluntaryContextSwitches:   osMetrics.voluntaryContextSwitches,
			InvoluntaryContextSwitches: osMetrics.involuntaryContextSwitches,
			OpenFileDescriptors:        osMetrics.openFileDescriptors,
			CPUPercent:                 cpuPct,
			CPUUserSecondsTotal:        cpuUserSeconds,
			CPUSystemSecondsTotal:      cpuSystemSeconds,
			CPUTotalSecondsTotal:       cpuTotalSeconds,
			CPUCoresUsed:               cpuCoresUsed,
			LogicalProcessorCount:      ptrInt64(int64(c.cpus)),
			MaxHeapMB:                  jvm.MaxHeapMB,
			MinHeapMB:                  jvm.MinHeapMB,
			GC:                         jvm.GC,
			HeapUsedMB:                 usedMB,
			HeapCommittedMB:            committedMB,
			HeapMaxMB:                  maxMB,
			HeapSampledAtMs:            heapSampledAt,
			HeapAvailable:              heapAvail,
			MetaspaceUsedMB:            jvmMetrics.MetaspaceUsedMB, MetaspaceCommittedMB: jvmMetrics.MetaspaceCommittedMB,
			YoungGCCount: jvmMetrics.YoungGCCount, YoungGCTimeMs: jvmMetrics.YoungGCTimeMs,
			YoungGCTimeSeconds: jvmMetrics.YoungGCTimeSeconds, FullGCTimeSeconds: jvmMetrics.FullGCTimeSeconds,
			ConcurrentGCTimeSeconds: jvmMetrics.ConcurrentGCTimeSeconds, TotalGCTimeSeconds: jvmMetrics.TotalGCTimeSeconds,
			OldGCCount: jvmMetrics.OldGCCount, OldGCTimeMs: jvmMetrics.OldGCTimeMs,
			JavaVersion: jvmMetrics.JavaVersion, JavaRuntimeVersion: jvmMetrics.JavaRuntimeVersion,
			JavaVendor: jvmMetrics.JavaVendor, JavaVMName: jvmMetrics.JavaVMName, JavaVMVersion: jvmMetrics.JavaVMVersion,
			OSName: jvmMetrics.OSName, OSArch: jvmMetrics.OSArch,
			ActiveProcessorCount: jvmMetrics.ActiveProcessorCount,
			HeapProbeDiagnostics: probeDiagnostics(jvmMetrics.Diagnostics, probeFailure),
			StartTimeMs:          startMs,
			Status:               status,
			Automated:            IsNonInteractive(cmdline),
			SampledAtMs:          now,
		})
	}

	c.mu.Lock()
	for key := range c.priors {
		if _, ok := seen[key]; !ok {
			delete(c.priors, key)
		}
	}
	c.mu.Unlock()

	if c.Heap != nil {
		c.Heap.EvictMissing(seenPIDs)
	}

	return model.Snapshot{SampledAtMs: now, Processes: out}, nil
}

// ShouldProbeLiveHeap mirrors Kotlin: only Gradle/Kotlin daemons are probed.
func ShouldProbeLiveHeap(kind string) bool {
	return kind == "GRADLE_DAEMON" || kind == "KOTLIN_DAEMON"
}

type osMetrics struct {
	virtualMemoryMB, swapMemoryMB, threadCount             *int64
	readBytes, writeBytes, readOperations, writeOperations *int64
	minorPageFaults, majorPageFaults                       *int64
	voluntaryContextSwitches, involuntaryContextSwitches   *int64
	openFileDescriptors                                    *int64
}

func collectOSMetrics(ctx context.Context, p *process.Process) osMetrics {
	m := osMetrics{}
	if mem, err := p.MemoryInfoWithContext(ctx); err == nil && mem != nil {
		m.virtualMemoryMB, m.swapMemoryMB = ptrInt64(int64(mem.VMS)/(1024*1024)), ptrInt64(int64(mem.Swap)/(1024*1024))
	}
	if v, err := p.NumThreadsWithContext(ctx); err == nil {
		m.threadCount = ptrInt64(int64(v))
	}
	if io, err := p.IOCountersWithContext(ctx); err == nil && io != nil {
		m.readBytes, m.writeBytes = ptrInt64(int64(io.ReadBytes)), ptrInt64(int64(io.WriteBytes))
		m.readOperations, m.writeOperations = ptrInt64(int64(io.ReadCount)), ptrInt64(int64(io.WriteCount))
	}
	if faults, err := p.PageFaultsWithContext(ctx); err == nil && faults != nil {
		m.minorPageFaults, m.majorPageFaults = ptrInt64(int64(faults.MinorFaults)), ptrInt64(int64(faults.MajorFaults))
	}
	if switches, err := p.NumCtxSwitchesWithContext(ctx); err == nil && switches != nil {
		m.voluntaryContextSwitches, m.involuntaryContextSwitches = ptrInt64(switches.Voluntary), ptrInt64(switches.Involuntary)
	}
	if v, err := p.NumFDsWithContext(ctx); err == nil {
		m.openFileDescriptors = ptrInt64(int64(v))
	}
	return m
}

func collectJVMMetrics(ctx context.Context, prober *heap.Prober, proc *process.Process, pid int32, kind string, startMs int64) (heap.Sample, string) {
	if !ShouldProbeLiveHeap(kind) || int(pid) == os.Getpid() || prober == nil {
		return heap.Sample{}, ""
	}
	if !sameUID(ctx, proc) {
		return heap.Sample{Diagnostics: []heap.Diagnostic{{Tool: "attach", Operation: "identity", Category: heap.FailurePermissionDenied}}}, heap.FailurePermissionDenied
	}
	sample, err := prober.SampleFor(ctx, pid, startMs)
	if err != nil {
		return heap.Sample{Diagnostics: heap.FailureDiagnostics(err)}, heap.FailureCategory(err)
	}
	return sample, ""
}

func liveHeapFieldsFromSample(now int64, sample heap.Sample, probeFailure string) (used, committed, max, sampledAt *int64, available bool) {
	sampled := now
	if probeFailure != "" {
		return nil, nil, nil, &sampled, false
	}
	u := sample.UsedMB
	c := sample.CommittedMB
	return &u, &c, nil, &sampled, true
}

func probeDiagnostics(diagnostics []heap.Diagnostic, terminalFailure string) []model.ProbeDiagnostic {
	if terminalFailure != "" {
		diagnostics = append(diagnostics, heap.Diagnostic{Tool: "probe", Operation: "live_heap", Category: terminalFailure})
	}
	if len(diagnostics) == 0 {
		return nil
	}
	out := make([]model.ProbeDiagnostic, 0, len(diagnostics))
	for _, d := range diagnostics {
		out = append(out, model.ProbeDiagnostic{Tool: d.Tool, Operation: d.Operation, Category: d.Category})
	}
	return out
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
	case gradleWorker.MatchString(commandLine):
		switch {
		case gradleTestExecutor.MatchString(commandLine):
			return "TEST_WORKER"
		case gradleWorkerDaemon.MatchString(commandLine):
			return "GRADLE_WORKER"
		default:
			return "UNKNOWN_GRADLE_WORKER"
		}
	case wrapper.MatchString(commandLine):
		return "GRADLE_WRAPPER"
	case javaBin.MatchString(commandLine) && gradleRuntime.MatchString(commandLine):
		return "JAVA_GRADLE_RELATED"
	default:
		return ""
	}
}

func Platform() string { return runtime.GOOS + "/" + runtime.GOARCH }
