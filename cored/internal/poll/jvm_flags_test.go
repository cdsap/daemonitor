package poll

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/shirou/gopsutil/v4/process"
)

func TestJVMFlagsIncarnationValidation(t *testing.T) {
	ctx := context.Background()
	proc, err := process.NewProcess(int32(os.Getpid()))
	if err != nil {
		t.Fatal(err)
	}
	start, err := proc.CreateTime()
	if err != nil {
		t.Fatal(err)
	}
	p := heap.NewProber()
	calls := 0
	p.LookPath = func(string) (string, error) { return "jcmd", nil }
	p.Runner = func(context.Context, string, ...string) (string, error) {
		calls++
		return "bool UseG1GC = true {product} {default}\n", nil
	}
	for _, invalid := range []int64{0, start + 1} {
		got := collectJVMFlags(ctx, p, proc.Pid, invalid)
		if got.Availability != "unavailable" || calls != 0 {
			t.Fatalf("unverified incarnation probed: %+v calls=%d", got, calls)
		}
	}
	got := collectJVMFlags(ctx, p, proc.Pid, start)
	if got.Availability != "available" || calls != 1 {
		t.Fatalf("metadata=%+v calls=%d", got, calls)
	}
}

func TestJVMFlagsFailuresPreserveProcessMetrics(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	pid := startBazelProbeProcess(t, exe)
	child, err := process.NewProcess(pid)
	if err != nil {
		t.Fatal(err)
	}
	start, err := child.CreateTime()
	if err != nil {
		t.Fatal(err)
	}
	for _, failure := range []string{"permission denied", "no such process", "attach disabled", "missing tool", "timeout"} {
		t.Run(failure, func(t *testing.T) {
			p := heap.NewProber()
			p.Timeout = time.Millisecond
			p.LookPath = func(string) (string, error) { return "jcmd", nil }
			p.Runner = func(ctx context.Context, _ string, args ...string) (string, error) {
				if len(args) > 1 && args[1] == "GC.heap_info" {
					return "garbage-first heap total reserved 65536K, committed 65536K, used 16384K\n", nil
				}
				if len(args) > 1 && args[1] == "VM.flags" {
					if failure == "timeout" {
						<-ctx.Done()
						return "", ctx.Err()
					}
					return "", errors.New(failure)
				}
				return "", errors.New("not supported")
			}
			c := &Collector{Heap: p, ProbeConcurrency: 2}
			defer c.Close()
			now := time.Now().UnixMilli()
			jobs := []pendingJVM{{idx: 0, proc: child, pid: pid, startMs: start}}
			rows := []model.Process{{PID: pid, StartTimeMs: start, RSSMemoryMB: 99, GC: ptrString("G1")}}
			c.applyJVMProbes(context.Background(), now, rows, jobs)
			c.jvmWG.Wait()
			rows = []model.Process{{PID: pid, StartTimeMs: start, RSSMemoryMB: 99, GC: ptrString("G1")}}
			c.applyJVMProbes(context.Background(), now, rows, jobs)
			if len(rows) != 1 || rows[0].RSSMemoryMB != 99 || !rows[0].HeapAvailable || rows[0].HeapUsedMB == nil || *rows[0].HeapUsedMB != 16 || rows[0].JVMFlags == nil || rows[0].JVMFlags.Availability != "unavailable" {
				t.Fatalf("row=%+v", rows[0])
			}
		})
	}
}
