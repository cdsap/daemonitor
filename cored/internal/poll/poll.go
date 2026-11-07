package poll

import (
	"context"
	"errors"
	"os"
	"regexp"
	"runtime"
	"sync"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/observe"
	"github.com/cdsap/daemonitor/cored/internal/platform"
	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/process"
)

type pendingJVM struct {
	idx     int
	proc    *process.Process
	pid     int32
	kind    string
	startMs int64
}

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

// incarnationKey identifies a process incarnation for counter series (CPU deltas).
// PID alone is insufficient because operating systems reuse PIDs.
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
	mu               sync.Mutex
	priors           map[incarnationKey]priorCPU
	cpus             int
	HostID           string
	Heap             *heap.Prober
	Platform         platform.PlatformCollector
	Observe          *observe.Metrics
	Cadences         observe.Cadences
	ProbeConcurrency int
	PollInterval     time.Duration
}

func NewCollector() *Collector {
	n, err := cpu.Counts(true)
	if err != nil || n <= 0 {
		n = runtime.NumCPU()
	}
	cadences := observe.DefaultCadences()
	prober := heap.NewProber()
	prober.CacheTTL = cadences.JVMHeapGC
	return &Collector{
		priors:           make(map[incarnationKey]priorCPU),
		cpus:             n,
		HostID:           platform.LocalHostID(),
		Heap:             prober,
		Platform:         platform.New(),
		Observe:          &observe.Metrics{},
		Cadences:         cadences,
		ProbeConcurrency: observe.DefaultJVMProbeConcurrency,
		PollInterval:     cadences.ProcessOS,
	}
}

// Capabilities returns the OS platform metric support matrix.
func (c *Collector) Capabilities() []platform.MetricCapability {
	if c == nil || c.Platform == nil {
		return nil
	}
	return c.Platform.Capabilities()
}

// HostMetrics returns a host-level sample from the platform adapter.
func (c *Collector) HostMetrics(ctx context.Context) platform.HostMetrics {
	if c == nil || c.Platform == nil {
		return platform.HostMetrics{}
	}
	return c.Platform.CollectHost(ctx)
}

func (c *Collector) Snapshot(ctx context.Context) (model.Snapshot, error) {
	started := time.Now()
	nowTime := started
	now := nowTime.UnixMilli()
	pids, err := process.PidsWithContext(ctx)
	if err != nil {
		return model.Snapshot{}, err
	}

	seen := make(map[incarnationKey]struct{}, len(pids))
	seenPIDs := make(map[int32]struct{}, len(pids))
	out := make([]model.Process, 0, 8)
	var jvmJobs []pendingJVM
	hostID := c.HostID
	if hostID == "" {
		hostID = platform.LocalHostID()
	}

	// Pass 1: discovery + OS metrics only. JVM probes must not delay this path.
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

		platMetrics := platform.ProcessPlatformMetrics{}
		if c.Platform != nil {
			platMetrics = c.Platform.CollectProcess(ctx, platform.ProcessIdentity{
				PID:          pid,
				CreateTimeMs: startMs,
				HostID:       hostID,
			})
			if platMetrics.Identity.CreateTimeMs > 0 {
				startMs = platMetrics.Identity.CreateTimeMs
			}
		}
		osMetrics := osMetricsFromPlatform(platMetrics)
		rssMB := int64(0)
		if platMetrics.ResidentBytes.Value != nil {
			rssMB = *platMetrics.ResidentBytes.Value / (1024 * 1024)
		}

		var cpuPct, cpuCoresUsed *float64
		var cpuUserSeconds, cpuSystemSeconds, cpuTotalSeconds *float64
		if platMetrics.CPUUserSecondsTotal.Value != nil || platMetrics.CPUSystemSecondsTotal.Value != nil || platMetrics.CPUTotalSecondsTotal.Value != nil {
			userSeconds := 0.0
			systemSeconds := 0.0
			if platMetrics.CPUUserSecondsTotal.Value != nil {
				userSeconds = *platMetrics.CPUUserSecondsTotal.Value
			}
			if platMetrics.CPUSystemSecondsTotal.Value != nil {
				systemSeconds = *platMetrics.CPUSystemSecondsTotal.Value
			}
			totalSeconds := userSeconds + systemSeconds
			if platMetrics.CPUTotalSecondsTotal.Value != nil {
				totalSeconds = *platMetrics.CPUTotalSecondsTotal.Value
			}
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
		idx := len(out)
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
			StartTimeMs:                startMs,
			Status:                     status,
			Automated:                  IsNonInteractive(cmdline),
			SampledAtMs:                now,
		})
		if ShouldProbeLiveHeap(kind) {
			jvmJobs = append(jvmJobs, pendingJVM{idx: idx, proc: p, pid: pid, kind: kind, startMs: startMs})
		}
	}

	// Poll duration covers OS discovery only so self-telemetry matches probe isolation.
	if c.Observe != nil {
		c.Observe.ObservePoll(time.Since(started), c.PollInterval)
	}

	// Pass 2: bounded concurrent JVM probes. Failures never drop OS rows.
	c.applyJVMProbes(ctx, now, out, jvmJobs)

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

func (c *Collector) applyJVMProbes(ctx context.Context, now int64, out []model.Process, jobs []pendingJVM) {
	if len(jobs) == 0 {
		return
	}
	concurrency := c.ProbeConcurrency
	if concurrency <= 0 {
		concurrency = observe.DefaultJVMProbeConcurrency
	}
	sem := make(chan struct{}, concurrency)
	var wg sync.WaitGroup
	for _, job := range jobs {
		job := job
		wg.Add(1)
		go func() {
			defer wg.Done()
			select {
			case <-ctx.Done():
				return
			case sem <- struct{}{}:
			}
			defer func() { <-sem }()

			probeStart := time.Now()
			jvmMetrics, probeFailure := collectJVMMetrics(ctx, c.Heap, job.proc, job.pid, job.kind, job.startMs)
			timedOut := probeFailure == heap.FailureTimeout
			var probeErr error
			if probeFailure != "" {
				probeErr = errors.New(probeFailure)
			}
			if c.Observe != nil {
				c.Observe.ObserveProbe(time.Since(probeStart), probeErr, timedOut)
			}

			usedMB, committedMB, maxMB, heapSampledAt, heapAvail := liveHeapFieldsFromSample(now, jvmMetrics, probeFailure)
			proc := &out[job.idx]
			if proc.GC == nil {
				proc.GC = jvmMetrics.GC
			}
			proc.HeapUsedMB = usedMB
			proc.HeapCommittedMB = committedMB
			proc.HeapMaxMB = maxMB
			proc.HeapSampledAtMs = heapSampledAt
			proc.HeapAvailable = heapAvail
			proc.MetaspaceUsedMB = jvmMetrics.MetaspaceUsedMB
			proc.MetaspaceCommittedMB = jvmMetrics.MetaspaceCommittedMB
			proc.YoungGCCount = jvmMetrics.YoungGCCount
			proc.YoungGCTimeMs = jvmMetrics.YoungGCTimeMs
			proc.YoungGCTimeSeconds = jvmMetrics.YoungGCTimeSeconds
			proc.FullGCTimeSeconds = jvmMetrics.FullGCTimeSeconds
			proc.ConcurrentGCTimeSeconds = jvmMetrics.ConcurrentGCTimeSeconds
			proc.TotalGCTimeSeconds = jvmMetrics.TotalGCTimeSeconds
			proc.OldGCCount = jvmMetrics.OldGCCount
			proc.OldGCTimeMs = jvmMetrics.OldGCTimeMs
			proc.JavaVersion = jvmMetrics.JavaVersion
			proc.JavaRuntimeVersion = jvmMetrics.JavaRuntimeVersion
			proc.JavaVendor = jvmMetrics.JavaVendor
			proc.JavaVMName = jvmMetrics.JavaVMName
			proc.JavaVMVersion = jvmMetrics.JavaVMVersion
			proc.OSName = jvmMetrics.OSName
			proc.OSArch = jvmMetrics.OSArch
			proc.ActiveProcessorCount = jvmMetrics.ActiveProcessorCount
			proc.HeapProbeDiagnostics = probeDiagnostics(jvmMetrics.Diagnostics, probeFailure)
		}()
	}
	wg.Wait()
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

func osMetricsFromPlatform(pm platform.ProcessPlatformMetrics) osMetrics {
	m := osMetrics{}
	if pm.VirtualBytes.Value != nil {
		m.virtualMemoryMB = ptrInt64(*pm.VirtualBytes.Value / (1024 * 1024))
	}
	if pm.SwapBytes.Value != nil {
		m.swapMemoryMB = ptrInt64(*pm.SwapBytes.Value / (1024 * 1024))
	}
	m.threadCount = pm.ThreadCount.Value
	m.readBytes = pm.LogicalReadBytesTotal.Value
	m.writeBytes = pm.LogicalWriteBytesTotal.Value
	m.readOperations = pm.ReadOperationsTotal.Value
	m.writeOperations = pm.WriteOperationsTotal.Value
	m.minorPageFaults = pm.MinorPageFaultsTotal.Value
	m.majorPageFaults = pm.MajorPageFaultsTotal.Value
	m.voluntaryContextSwitches = pm.VoluntaryContextSwitchesTotal.Value
	m.involuntaryContextSwitches = pm.InvoluntaryContextSwitchesTotal.Value
	m.openFileDescriptors = pm.OpenDescriptorsCount.Value
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
