package bep

import (
	"context"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/shirou/gopsutil/v4/process"
)

func readAncestor(ctx context.Context, pid int32) (int32, []string, error) {
	p, err := process.NewProcessWithContext(ctx, pid)
	if err != nil {
		return 0, nil, err
	}
	parent, err := p.PpidWithContext(ctx)
	if err != nil {
		return 0, nil, err
	}
	argv, err := p.CmdlineSliceWithContext(ctx)
	return parent, argv, err
}

// Walk the client, never the long-lived Bazel server. Nearest agent wins.
// Bound the walk and stop on cycles, permission errors, or vanished ancestors.
func bazelClientAgent(ctx context.Context, pid int32, read func(context.Context, int32) (int32, []string, error)) *builds.AgentAttribution {
	seen := make(map[int32]bool)
	for depth := 0; pid > 1 && depth < 32; depth++ {
		if ctx.Err() != nil || seen[pid] {
			return nil
		}
		seen[pid] = true
		parent, argv, err := read(ctx, pid)
		if err != nil {
			return nil
		}
		if attr := builds.DetectAgentProcess(argv); attr != nil {
			return attr
		}
		pid = parent
	}
	return nil
}

// ObserveProcess handles Pass 0 observations without reading process environments.
func (w *Watcher) ObserveProcess(ctx context.Context, p *process.Process, cmdline string, observedMs int64) {
	if !LooksLikeBazelClient(cmdline) {
		return
	}
	cwd, _ := p.CwdWithContext(ctx)
	sinks := ParseSinksFromArgv(cmdline, cwd, p.Pid)
	if len(sinks) == 0 {
		return
	}
	startMs, _ := p.CreateTimeWithContext(ctx)
	attr := bazelClientAgent(ctx, p.Pid, readAncestor)
	for _, sink := range sinks {
		w.observeClient(clientObservation{Sink: sink, Agent: attr, StartTimeMs: startMs, ObservedAtMs: observedMs})
	}
}
