package poll

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/shirou/gopsutil/v4/process"
)

func TestBazelSandboxOwnership(t *testing.T) {
	for _, wrapper := range []string{"sandbox-exec", "linux-sandbox", "process-wrapper", `C:\Windows\System32\cmd.exe`} {
		t.Run(wrapper, func(t *testing.T) {
			tree := map[int32]processNode{
				10: {parent: 1, start: 100, kind: "BAZEL_SERVER"},
				11: {parent: 10, start: 110, cmd: wrapper},
				12: {parent: 11, start: 120, cmd: "sh"},
				13: {parent: 12, start: 130, kind: "BAZEL_WORKER"},
				14: {parent: 12, start: 140, kind: "BAZEL_TEST"},
			}
			for _, pid := range []int32{13, 14} {
				if got := bazelOwner(pid, tree); got != 10 {
					t.Fatalf("owner(%d)=%d", pid, got)
				}
			}
			tree[11] = processNode{parent: 10, start: 150}
			if got := bazelOwner(13, tree); got != 0 {
				t.Fatalf("reused wrapper accepted: %d", got)
			}
			tree[11] = processNode{parent: 10, start: 110}
			tree[10] = processNode{parent: 1, start: 200, kind: "BAZEL_SERVER"}
			if got := bazelOwner(13, tree); got != 0 {
				t.Fatalf("reused server accepted: %d", got)
			}
		})
	}
}

func TestBazelOwnershipFailsClosed(t *testing.T) {
	for _, tree := range []map[int32]processNode{
		{1: {parent: 2, start: 1}, 2: {parent: 1, start: 1}},
		{1: {parent: 2, start: 2}, 2: {start: 0, kind: "BAZEL_SERVER"}},
		{1: {parent: 99, start: 2}},
	} {
		if got := bazelOwner(1, tree); got != 0 {
			t.Fatalf("invalid tree owner=%d", got)
		}
	}
	tree := map[int32]processNode{100: {start: 1, kind: "BAZEL_SERVER"}}
	for pid := int32(1); pid < 100; pid++ {
		tree[pid] = processNode{parent: pid + 1, start: 1}
	}
	if got := bazelOwner(1, tree); got != 0 {
		t.Fatalf("unbounded ancestry: %d", got)
	}
}

func TestBazelRollupIncludesHiddenActionsOnce(t *testing.T) {
	cpu := 2.0
	out := []model.Process{{PID: 10, Type: "BAZEL_SERVER", RSSMemoryMB: 100, CPUPercent: &cpu},
		{PID: 13, Type: "BAZEL_WORKER", OwnerPID: 10, RSSMemoryMB: 20, CPUPercent: &cpu}}
	hidden := []model.Process{{PID: 11, OwnerPID: 10, RSSMemoryMB: 3}, {PID: 14, OwnerPID: 10, RSSMemoryMB: 40, CPUPercent: &cpu}}
	tree := map[int32]processNode{14: {cmd: "clang -c foo.cc"}}
	applyBazelRollups(out, hidden, tree)
	if out[0].SubtreeRSSMemoryMB == nil || *out[0].SubtreeRSSMemoryMB != 163 || out[0].SubtreeCPUPercent == nil || *out[0].SubtreeCPUPercent != 6 || out[0].ActiveWorkerCount != 1 || out[0].ActiveSandboxedActionCount != 1 {
		t.Fatalf("rollup=%+v", out[0])
	}
	if out[0].RSSMemoryMB != 100 {
		t.Fatal("own RSS changed")
	}
}

func TestBazelTestAncestorBridgesSandboxSpine(t *testing.T) {
	tree := map[int32]processNode{
		10: {start: 10, kind: "BAZEL_SERVER"},
		11: {parent: 10, start: 11, kind: "BAZEL_TEST"},
		12: {parent: 11, start: 12, cmd: "linux-sandbox"},
		13: {parent: 12, start: 13, cmd: "sh"},
		14: {parent: 13, start: 14},
	}
	if got := bazelTestAncestor(14, tree); got != "BAZEL_TEST" {
		t.Fatalf("test ancestry=%q", got)
	}
	tree[11] = processNode{parent: 10, start: 11, kind: "BAZEL_WORKER"}
	if got := bazelTestAncestor(14, tree); got != "" {
		t.Fatalf("worker promoted test: %q", got)
	}
}

func TestBazelCompilerExecutables(t *testing.T) {
	for _, cmd := range []string{"clang -c a.cc", "/bin/clang++ -c a.cc", "swiftc main.swift", "javac Main.java", "kotlinc Main.kt", "rustc main.rs", "ld a.o", `"C:\Program Files\LLVM\bin\clang.exe" -c a.cc`} {
		if !isBazelCompiler(cmd) {
			t.Errorf("compiler not counted: %s", cmd)
		}
	}
	for _, cmd := range []string{"sh -c clang a.cc", "process-wrapper clang a.cc", "echo javac", "", "not-clang"} {
		if isBazelCompiler(cmd) {
			t.Errorf("wrapper counted as compiler: %s", cmd)
		}
	}
}

func TestBazelFirstActionCPUAndIncarnation(t *testing.T) {
	c := &Collector{priors: make(map[incarnationKey]priorCPU), cpus: 2, lastSnapshotTime: time.UnixMilli(1000)}
	old := incarnationKey{pid: 42, createTimeMs: 500}
	c.priors[old] = priorCPU{totalSeconds: 10, wallClock: time.UnixMilli(1000)}
	// Reused PID, born during this interval: include its first 0.5 CPU seconds.
	key := incarnationKey{pid: 42, createTimeMs: 1500}
	current := priorCPU{totalSeconds: 0.5, wallClock: time.UnixMilli(2000)}
	if got := c.bazelCPUUsage(key, current); got == nil || *got != 25 {
		t.Fatalf("first action CPU=%v", got)
	}
	// An older process first observed now has no interval baseline.
	if got := c.bazelCPUUsage(incarnationKey{pid: 43, createTimeMs: 500}, current); got != nil {
		t.Fatalf("invented baseline: %v", got)
	}
}

func TestBazelChainRejectsChangedIncarnation(t *testing.T) {
	ctx := context.Background()
	pid := int32(os.Getpid())
	p, err := process.NewProcessWithContext(ctx, pid)
	if err != nil {
		t.Fatal(err)
	}
	start, err := p.CreateTimeWithContext(ctx)
	if err != nil {
		t.Fatal(err)
	}
	parent, err := p.PpidWithContext(ctx)
	if err != nil {
		t.Fatal(err)
	}
	tree := map[int32]processNode{pid: {start: start, parent: parent}}
	if !validBazelChain(ctx, pid, pid, tree, make(map[int32]bool)) {
		t.Fatal("current incarnation rejected")
	}
	tree[pid] = processNode{start: start + 1, parent: parent}
	if validBazelChain(ctx, pid, pid, tree, make(map[int32]bool)) {
		t.Fatal("changed incarnation accepted")
	}
}

func TestBazelActionsCountSandboxesRatherThanCompilerChildren(t *testing.T) {
	out := []model.Process{{PID: 10, Type: "BAZEL_SERVER"}}
	hidden := []model.Process{
		{PID: 11, OwnerPID: 10}, {PID: 12, OwnerPID: 10}, {PID: 13, OwnerPID: 10},
		{PID: 14, OwnerPID: 10}, {PID: 15, OwnerPID: 10},
	}
	tree := map[int32]processNode{
		10: {kind: "BAZEL_SERVER"},
		11: {parent: 10, cmd: "linux-sandbox"},
		12: {parent: 11, cmd: "clang a.cc"},
		13: {parent: 11, cmd: "clang b.cc"},
		14: {parent: 10, cmd: "process-wrapper"},
		15: {parent: 14, cmd: "sh -c echo hello"},
	}
	applyBazelRollups(out, hidden, tree)
	if out[0].ActiveSandboxedActionCount != 2 {
		t.Fatalf("actions=%d want 2", out[0].ActiveSandboxedActionCount)
	}
}
