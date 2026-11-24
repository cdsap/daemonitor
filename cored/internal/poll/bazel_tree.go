package poll

import (
	"context"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/shirou/gopsutil/v4/process"
)

const maxBazelAncestryDepth = 64

type processNode struct {
	parent int32
	start  int64
	kind   string
	cmd    string
}

// Walk only this poll's OS tree, checking incarnation ordering on every edge.
// Missing creation times, cycles and excessive depth fail closed.
func bazelAncestor(pid int32, nodes map[int32]processNode, kind string) int32 {
	child, ok := nodes[pid]
	if !ok || child.start <= 0 {
		return 0
	}
	visited := map[int32]bool{pid: true}
	for depth := 0; depth < maxBazelAncestryDepth; depth++ {
		parentPID := child.parent
		parent, ok := nodes[parentPID]
		if !ok || visited[parentPID] || parent.start <= 0 || parent.start > child.start {
			return 0
		}
		if parent.kind == kind {
			return parentPID
		}
		// Nested servers own their own subtrees.
		if parent.kind == "BAZEL_SERVER" {
			return 0
		}
		visited[parentPID] = true
		child = parent
	}
	return 0
}

func bazelOwner(pid int32, nodes map[int32]processNode) int32 {
	return bazelAncestor(pid, nodes, "BAZEL_SERVER")
}

func bazelTestAncestor(pid int32, nodes map[int32]processNode) string {
	if bazelAncestor(pid, nodes, "BAZEL_TEST") != 0 {
		return "BAZEL_TEST"
	}
	return ""
}

// Re-read ancestor identities using fresh handles: gopsutil caches creation time
// on a Process. A wrapper/server that exited after Pass 0 must not own new work.
func validBazelChain(ctx context.Context, pid, owner int32, nodes map[int32]processNode, validated map[int32]bool) bool {
	for depth := 0; depth <= maxBazelAncestryDepth; depth++ {
		node, ok := nodes[pid]
		if !ok {
			return false
		}
		valid, checked := validated[pid]
		if !checked {
			p, err := process.NewProcessWithContext(ctx, pid)
			if err == nil {
				start, startErr := p.CreateTimeWithContext(ctx)
				parent, parentErr := p.PpidWithContext(ctx)
				valid = startErr == nil && parentErr == nil && start == node.start && parent == node.parent
			}
			validated[pid] = valid
		}
		if !valid {
			return false
		}
		if pid == owner {
			return true
		}
		pid = node.parent
	}
	return false
}

// Hidden descendants use only cheap OS counters, with the same CPU incarnation
// keys as visible rows. No heap probes or top-level rows are created for them.
func (c *Collector) sampleBazelDescendant(ctx context.Context, p *process.Process, owner int32, start int64, now time.Time, seen map[incarnationKey]struct{}) model.Process {
	sample := model.Process{PID: p.Pid, OwnerPID: owner, StartTimeMs: start}
	if mem, err := p.MemoryInfoWithContext(ctx); err == nil {
		sample.RSSMemoryMB = int64(mem.RSS) / (1024 * 1024)
	}
	if times, err := p.TimesWithContext(ctx); err == nil {
		key := incarnationKey{pid: p.Pid, createTimeMs: start}
		current := priorCPU{userSeconds: times.User, systemSeconds: times.System, totalSeconds: times.User + times.System, wallClock: now}
		sample.CPUPercent = c.bazelCPUUsage(key, current)
		seen[key] = struct{}{}
	}
	return sample
}

// A newly spawned action can disappear before the next poll. Its first counter
// already represents CPU consumed since the previous snapshot, so include it.
func (c *Collector) bazelCPUUsage(key incarnationKey, current priorCPU) *float64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	prior, ok := c.priors[key]
	if !ok && !c.lastSnapshotTime.IsZero() && key.createTimeMs >= c.lastSnapshotTime.UnixMilli() {
		prior = priorCPU{wallClock: c.lastSnapshotTime}
		ok = true
	}
	percent, _ := cpuUsage(current.wallClock, c.cpus, current, prior, ok)
	c.priors[key] = current
	return percent
}

func commandExecutable(cmd string) string {
	cmd = strings.TrimSpace(cmd)
	var executable string
	if len(cmd) > 0 && (cmd[0] == '"' || cmd[0] == '\'') {
		if end := strings.IndexByte(cmd[1:], cmd[0]); end >= 0 {
			executable = cmd[1 : end+1]
		}
	} else if fields := strings.Fields(cmd); len(fields) > 0 {
		executable = fields[0]
	}
	executable = strings.ReplaceAll(executable, `\`, "/")
	executable = executable[strings.LastIndex(executable, "/")+1:]
	return strings.TrimSuffix(strings.ToLower(executable), ".exe")
}

func isBazelCompiler(cmd string) bool {
	switch commandExecutable(cmd) {
	case "clang", "clang++", "swiftc", "javac", "kotlinc", "rustc", "ld":
		return true
	}
	return false
}

// One sandbox invocation is one action, even when it contains several compiler
// children. Actions without a known wrapper fall back to their compiler/test PID.
func bazelSandboxAction(pid int32, nodes map[int32]processNode) int32 {
	var action int32
	for depth := 0; depth <= maxBazelAncestryDepth; depth++ {
		node, ok := nodes[pid]
		if !ok || node.kind == "BAZEL_SERVER" {
			break
		}
		switch commandExecutable(node.cmd) {
		case "sandbox-exec", "linux-sandbox", "process-wrapper":
			action = pid
		}
		pid = node.parent
	}
	return action
}

func applyBazelRollups(out, hidden []model.Process, nodes map[int32]processNode) {
	roots := make(map[int32]*model.Process)
	for i := range out {
		p := &out[i]
		if p.Type == "BAZEL_SERVER" {
			rss := p.RSSMemoryMB
			p.SubtreeRSSMemoryMB = &rss
			if p.CPUPercent != nil {
				cpu := *p.CPUPercent
				p.SubtreeCPUPercent = &cpu
			}
			roots[p.PID] = p
		}
	}
	actions := make(map[int32]map[int32]bool)
	add := func(p model.Process) {
		root := roots[p.OwnerPID]
		if root == nil || p.PID == root.PID || (p.OwnerStartTimeMs > 0 && root.StartTimeMs != p.OwnerStartTimeMs) {
			return
		}
		*root.SubtreeRSSMemoryMB += p.RSSMemoryMB
		if p.CPUPercent != nil {
			if root.SubtreeCPUPercent == nil {
				root.SubtreeCPUPercent = ptrFloat64(0)
			}
			*root.SubtreeCPUPercent += *p.CPUPercent
		}
		if p.Type == "BAZEL_WORKER" {
			root.ActiveWorkerCount++
		}
		action := bazelSandboxAction(p.PID, nodes)
		if action == 0 && (isBazelCompiler(nodes[p.PID].cmd) || (p.Type == "BAZEL_TEST" && bazelTestAncestor(p.PID, nodes) == "")) {
			action = p.PID
		}
		if action != 0 {
			if actions[root.PID] == nil {
				actions[root.PID] = make(map[int32]bool)
			}
			if !actions[root.PID][action] {
				actions[root.PID][action] = true
				root.ActiveSandboxedActionCount++
			}
		}
	}
	for _, p := range out {
		add(p)
	}
	for _, p := range hidden {
		add(p)
	}
}
