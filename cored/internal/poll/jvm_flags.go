package poll

import (
	"context"
	"os"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/shirou/gopsutil/v4/process"
)

func flagsIdentity(ctx context.Context, pid int32, start int64) string {
	if start <= 0 {
		return "identity_unavailable"
	}
	// Use a fresh Process: gopsutil caches create times on Process objects.
	p, err := process.NewProcessWithContext(ctx, pid)
	if err != nil {
		return heap.FailureStalePID
	}
	actual, err := p.CreateTimeWithContext(ctx)
	if err != nil || actual != start {
		return heap.FailureStalePID
	}
	if os.Getuid() >= 0 {
		ids, err := p.UidsWithContext(ctx)
		if err != nil || len(ids) == 0 || int(ids[0]) != os.Getuid() {
			return heap.FailurePermissionDenied
		}
	} else {
		self, err := process.NewProcessWithContext(ctx, int32(os.Getpid()))
		if err != nil {
			return heap.FailurePermissionDenied
		}
		owner, err := p.UsernameWithContext(ctx)
		current, selfErr := self.UsernameWithContext(ctx)
		if err != nil || selfErr != nil || owner == "" || owner != current {
			return heap.FailurePermissionDenied
		}
	}
	return ""
}

func collectJVMFlags(ctx context.Context, p *heap.Prober, pid int32, start int64) model.JVMFlagsMetadata {
	if reason := flagsIdentity(ctx, pid, start); reason != "" {
		return heap.UnavailableFlags(pid, start, reason)
	}
	m := p.FlagsFor(ctx, pid, start)
	// An exit or PID reuse during attach invalidates even a successful result.
	if reason := flagsIdentity(ctx, pid, start); reason != "" {
		return heap.UnavailableFlags(pid, start, reason)
	}
	return m
}
