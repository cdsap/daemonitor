package poll

import (
	"context"
	"os"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/model"
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
	cpuTimeMs  float64
	wallClockMs int64
}

// Collector lists Gradle-related processes using OS process APIs (gopsutil ≈ OSHI).
type Collector struct {
	mu     sync.Mutex
	priors map[int32]priorCPU
	cpus   int
}

func NewCollector() *Collector {
	n, err := cpu.Counts(true)
	if err != nil || n <= 0 {
		n = runtime.NumCPU()
	}
	return &Collector{
		priors: make(map[int32]priorCPU),
		cpus:   n,
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
		out = append(out, model.Process{
			PID:              pid,
			ParentPID:        parentPID,
			Type:             kind,
			Name:             name,
			CommandLine:      redact(cmdline),
			WorkingDirectory: cwd,
			ProjectPath:      project,
			RSSMemoryMB:      rssMB,
			CPUPercent:       cpuPct,
			MaxHeapMB:        jvm.MaxHeapMB,
			MinHeapMB:        jvm.MinHeapMB,
			GC:               jvm.GC,
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

	return model.Snapshot{SampledAtMs: now, Processes: out}, nil
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

// redact trims very long classpaths; full Kotlin Redactor parity is a later step.
func redact(cmdline string) string {
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		cmdline = strings.ReplaceAll(cmdline, home, "~")
	}
	const max = 512
	if len(cmdline) <= max {
		return cmdline
	}
	return cmdline[:max] + "…"
}

func Platform() string { return runtime.GOOS + "/" + runtime.GOARCH }
