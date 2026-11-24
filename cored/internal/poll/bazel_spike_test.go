package poll_test

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"
)

// Test-only prototype: no public model or collector behavior changes in the spike.
type spikeNode struct {
	PID        int32  `json:"pid"`
	PPID       int32  `json:"ppid"`
	Start      int64  `json:"start"`
	Kind       string `json:"kind"`
	Executable string `json:"executable"`
	Owner      int32  `json:"owner"`
}

// Resolve once per snapshot. Memoize both successes and failures so common
// wrapper chains and malformed cycles do not cause quadratic work.
func spikeOwners(nodes []spikeNode) map[int32]int32 {
	byPID := make(map[int32]spikeNode, len(nodes))
	owners := make(map[int32]int32, len(nodes))
	for _, n := range nodes {
		byPID[n.PID] = n
	}
	for _, n := range nodes {
		path := []int32{}
		visiting := map[int32]bool{}
		pid, owner := n.PID, int32(0)
		for {
			if cached, ok := owners[pid]; ok {
				owner = cached
				break
			}
			current, ok := byPID[pid]
			if !ok || visiting[pid] {
				break
			}
			visiting[pid] = true
			path = append(path, pid)
			if current.Kind == "BAZEL_SERVER" {
				owner = pid
				break
			}
			if current.Kind == "GRADLE_DAEMON" {
				break
			}
			parent, ok := byPID[current.PPID]
			if !ok || current.Start <= 0 || parent.Start <= 0 || parent.Start > current.Start {
				break
			}
			pid = parent.PID
		}
		for _, visited := range path {
			owners[visited] = owner
		}
	}
	return owners
}

func TestBazelSpikeAncestryFixtures(t *testing.T) {
	data, err := os.ReadFile("testdata/bazel_ancestry_spike.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Provenance string `json:"provenance"`
		Cases      []struct {
			Name  string
			Nodes []spikeNode
		}
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(fixture.Provenance, "NOT captured") || len(fixture.Cases) == 0 {
		t.Fatal("fixture provenance or cases missing")
	}
	for _, c := range fixture.Cases {
		t.Run(c.Name, func(t *testing.T) {
			// Discovery order must not determine ownership.
			for pass := 0; pass < 2; pass++ {
				got := spikeOwners(c.Nodes)
				for _, n := range c.Nodes {
					if got[n.PID] != n.Owner {
						t.Fatalf("pid %d owner=%d want %d", n.PID, got[n.PID], n.Owner)
					}
				}
				for i, j := 0, len(c.Nodes)-1; i < j; i, j = i+1, j-1 {
					c.Nodes[i], c.Nodes[j] = c.Nodes[j], c.Nodes[i]
				}
			}
		})
	}
}

// Executable means the OS-resolved image, not the flattened command line.
// JVM marker strings in a native launcher's arguments do not prove a JVM image.
func spikeProbeJVM(kind, executable string) bool {
	switch kind {
	case "BAZEL_SERVER", "BAZEL_WORKER", "BAZEL_TEST":
	default:
		return false
	}
	parts := strings.Split(strings.ReplaceAll(executable, `\`, "/"), "/")
	switch strings.ToLower(parts[len(parts)-1]) {
	case "java", "java.exe", "javaw.exe":
		return true
	}
	return false
}

func TestBazelSpikeJVMImageEvidence(t *testing.T) {
	for _, tc := range []struct {
		name, kind, exe string
		want            bool
	}{
		{"embedded server", "BAZEL_SERVER", "/install/embedded_tools/jdk/bin/java", true},
		{"JavaBuilder", "BAZEL_WORKER", "/jdk/bin/java", true},
		{"KotlinCompile", "BAZEL_WORKER", "/jdk/bin/java", true},
		{"Turbine", "BAZEL_WORKER", `C:\jdk\bin\JAVA.EXE`, true},
		{"BazelTestRunner", "BAZEL_TEST", `C:\jdk\bin\javaw.exe`, true},
		{"native Swift worker", "BAZEL_WORKER", "/rules_swift/worker", false},
		{"Go test", "BAZEL_TEST", "/runfiles/go_test", false},
		{"Rust test", "BAZEL_TEST", "/runfiles/rust_test", false},
		{"C++ test", "BAZEL_TEST", "/runfiles/cc_test", false},
		{"test setup shell", "BAZEL_TEST", "/bin/bash", false},
		{"wrapper carrying JavaBuilder argv", "BAZEL_WORKER", "/usr/bin/sandbox-exec", false},
		{"javac launcher needs image evidence", "BAZEL_WORKER", "/jdk/bin/javac", false},
		{"unknown image", "BAZEL_WORKER", "", false},
		{"unrelated Java", "", "/jdk/bin/java", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := spikeProbeJVM(tc.kind, tc.exe); got != tc.want {
				t.Fatalf("probe=%v want %v", got, tc.want)
			}
		})
	}
}

func BenchmarkBazelSpikeAncestry(b *testing.B) {
	for _, size := range []int{1000, 10000} {
		for _, shape := range []string{"fanout", "chain"} {
			nodes := make([]spikeNode, size)
			// Reverse PID order forces a full chain traversal before memoization.
			for i := range nodes {
				pid := int32(size - i)
				parent := int32(1)
				if shape == "chain" {
					parent = pid - 1
				}
				nodes[i] = spikeNode{PID: pid, PPID: parent, Start: int64(pid)}
				if pid == 1 {
					nodes[i].Kind = "BAZEL_SERVER"
				}
			}
			b.Run(fmt.Sprintf("%s/%d", shape, size), func(b *testing.B) {
				b.ReportAllocs()
				for b.Loop() {
					spikeOwners(nodes)
				}
			})
		}
	}
}
