package poll

import (
	"context"
	"regexp"
	"runtime"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/model"
	"github.com/shirou/gopsutil/v4/process"
)

var (
	gradleDaemon = regexp.MustCompile(`org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon|GradleDaemon`)
	kotlinDaemon = regexp.MustCompile(`org\.jetbrains\.kotlin\.daemon|KotlinCompileDaemon`)
	testWorker   = regexp.MustCompile(`worker\.org\.gradle\.process\.internal\.worker\.GradleWorkerMain|org\.gradle\.process\.internal\.worker\.GradleWorkerMain`)
	wrapper      = regexp.MustCompile(`org\.gradle\.wrapper\.GradleWrapperMain|gradle-wrapper\.jar|org\.gradle\.appname=gradlew|(^|[\s/])gradlew(\s|$)`)
	javaBin      = regexp.MustCompile(`(^|[\s/])java(\s|$)`)
	gradleRuntime = regexp.MustCompile(`(-Dorg\.gradle\.|org\.gradle\.(launcher|process|tooling|internal|api|workers)\.|GradleWorkerMain)`)
)

// Collector lists Gradle-related processes using OS process APIs (gopsutil ≈ OSHI).
type Collector struct{}

func NewCollector() *Collector { return &Collector{} }

func (c *Collector) Snapshot(ctx context.Context) (model.Snapshot, error) {
	now := time.Now().UnixMilli()
	pids, err := process.PidsWithContext(ctx)
	if err != nil {
		return model.Snapshot{}, err
	}

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

		rssMB := 0.0
		if mem, err := p.MemoryInfoWithContext(ctx); err == nil && mem != nil {
			rssMB = float64(mem.RSS) / (1024 * 1024)
		}
		cpu := 0.0
		if pct, err := p.CPUPercentWithContext(ctx); err == nil {
			cpu = pct
		}
		name, _ := p.NameWithContext(ctx)

		out = append(out, model.Process{
			PID:         pid,
			Type:        kind,
			Name:        name,
			CommandLine: redact(cmdline),
			RSSMemoryMB: rssMB,
			CPUPercent:  cpu,
			SampledAtMs: now,
		})
	}

	return model.Snapshot{SampledAtMs: now, Processes: out}, nil
}

// Classify mirrors a subset of Kotlin GradleProcessClassifier.
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

// redact trims very long classpaths for IPC payloads (spike-level; not full Kotlin redaction).
func redact(cmdline string) string {
	const max = 512
	if len(cmdline) <= max {
		return cmdline
	}
	return cmdline[:max] + "…"
}

func Platform() string { return runtime.GOOS + "/" + runtime.GOARCH }

// WarmCPU primes gopsutil's CPU percent baseline (first sample is often 0).
func WarmCPU(ctx context.Context) {
	pids, err := process.PidsWithContext(ctx)
	if err != nil {
		return
	}
	for _, pid := range pids {
		p, err := process.NewProcessWithContext(ctx, pid)
		if err != nil {
			continue
		}
		_, _ = p.CPUPercentWithContext(ctx)
	}
	time.Sleep(200 * time.Millisecond)
}

func LooksGradleRelated(s string) bool {
	return Classify(s) != "" || strings.Contains(s, "gradle")
}
