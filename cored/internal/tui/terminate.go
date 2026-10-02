package tui

import (
	"context"
	"fmt"
	"os"

	"github.com/shirou/gopsutil/v4/process"
)

// TerminateFunc stops one monitored process. startTimeMs is the start time
// reported in the snapshot; implementations must refuse to signal the PID when
// it no longer matches, so a PID reused by an unrelated process is never hit.
type TerminateFunc func(ctx context.Context, pid int32, startTimeMs int64) error

// TerminateProcess asks a process to stop (SIGTERM on Unix, TerminateProcess
// on Windows). When startTimeMs is known it must equal the live process create
// time, so the PID is confirmed to still belong to the sampled process.
func TerminateProcess(ctx context.Context, pid int32, startTimeMs int64) error {
	if pid <= 0 {
		return fmt.Errorf("invalid pid %d", pid)
	}
	if int(pid) == os.Getpid() {
		return fmt.Errorf("refusing to stop daemonitor-cli itself")
	}
	p, err := process.NewProcessWithContext(ctx, pid)
	if err != nil {
		return fmt.Errorf("process no longer running")
	}
	if startTimeMs > 0 {
		created, err := p.CreateTimeWithContext(ctx)
		if err != nil {
			return fmt.Errorf("cannot verify process identity: %w", err)
		}
		if created != startTimeMs {
			return fmt.Errorf("pid now belongs to a different process")
		}
	}
	return p.TerminateWithContext(ctx)
}
