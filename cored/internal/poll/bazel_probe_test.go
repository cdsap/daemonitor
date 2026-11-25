package poll

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"sync"
	"testing"
	"time"
)

func TestBazelEmbeddedJDKDir(t *testing.T) {
	for _, tc := range []struct {
		name string
		args []string
		want string
	}{
		{"server", []string{"bazel(ws)", "-jar", "/install/A-server.jar", "--product_name=Bazel", "--install_base=/install"}, filepath.Join("/install", "embedded_tools", "jdk")},
		{"spaces", []string{"java", "-jar", "/install dir/A-server.jar", "--output_base=/output", "--install_base=/install dir"}, filepath.Join("/install dir", "embedded_tools", "jdk")},
		{"absent", []string{"java", "-jar", "/install/A-server.jar", "--product_name=Bazel"}, ""},
		{"empty", []string{"java", "-jar", "/install/A-server.jar", "--product_name=Bazel", "--install_base="}, ""},
		{"worker", []string{"java", "io.bazel.Worker", "--persistent_worker", "--install_base=/install"}, ""},
		{"unrelated", []string{"other", "--install_base=/install"}, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := bazelEmbeddedJDKDir(tc.args); got != tc.want {
				t.Fatalf("jdk=%q want %q", got, tc.want)
			}
		})
	}
}

const bazelProbeHelper = "daemonitor-bazel-probe-helper"

// Re-exec the test binary under Java/native executable names so Snapshot uses
// real OS discovery and executable lookup without requiring Bazel or a JDK.
func TestBazelProbeHelperProcess(t *testing.T) {
	if !slices.Contains(os.Args, bazelProbeHelper) {
		return
	}
	fmt.Println("ready")
	_, _ = io.Copy(io.Discard, os.Stdin)
	os.Exit(0)
}

func startBazelProbeProcess(t *testing.T, executable string, args ...string) int32 {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	cmd := exec.CommandContext(ctx, executable, append([]string{"-test.run=^TestBazelProbeHelperProcess$", "--", bazelProbeHelper}, args...)...)
	input, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	output, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = input.Close()
		_ = cmd.Wait()
	})
	if line, err := bufio.NewReader(output).ReadString('\n'); err != nil || line != "ready\n" {
		t.Fatalf("helper readiness=%q err=%v", line, err)
	}
	return int32(cmd.Process.Pid)
}

func TestSnapshotMixedBazelJVMAndNativeProcesses(t *testing.T) {
	native, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	javaName := "java"
	if runtime.GOOS == "windows" {
		javaName += ".exe"
	}
	java := filepath.Join(t.TempDir(), javaName)
	binary, err := os.ReadFile(native)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(java, binary, 0o755); err != nil {
		t.Fatal(err)
	}
	install := filepath.Join(t.TempDir(), "install with spaces")
	type target struct {
		kind string
		jvm  bool
	}
	targets := map[int32]target{}
	serverPID := startBazelProbeProcess(t, native, "-jar", "/install/A-server.jar", "--product_name=Bazel", "--install_base="+install)
	targets[serverPID] = target{"BAZEL_SERVER", true}
	for _, worker := range []string{"JavaBuilder_deploy.jar", "KotlinCompile", "Turbine"} {
		pid := startBazelProbeProcess(t, java, "io.bazel."+worker, "--persistent_worker")
		targets[pid] = target{"BAZEL_WORKER", true}
	}
	pid := startBazelProbeProcess(t, java, "com.google.testing.junit.runner.BazelTestRunner")
	targets[pid] = target{"BAZEL_TEST", true}
	for _, rule := range []string{"rules_swift", "rules_go", "rules_rust"} {
		pid := startBazelProbeProcess(t, native, "/_bazel_test/external/"+rule+"/worker", "--persistent_worker", "JavaBuilder_deploy.jar")
		targets[pid] = target{"BAZEL_WORKER", false}
	}
	pid = startBazelProbeProcess(t, native, "external/bazel_tools/tools/test/test-setup.sh", "/sandbox/native_test.runfiles/cpp_test", "java", "com.google.testing.junit.runner.BazelTestRunner")
	targets[pid] = target{"BAZEL_TEST", false}

	c := NewCollector()
	defer c.Close()
	if c.Heap.TargetJDKDir == nil {
		t.Fatal("collector must wire target JDK resolution")
	}
	if got, want := c.Heap.TargetJDKDir(serverPID), filepath.Join(install, "embedded_tools", "jdk"); got != want {
		t.Fatalf("server jdk=%q want %q", got, want)
	}
	if got := c.Heap.TargetJDKDir(pid); got != "" {
		t.Fatalf("native test jdk=%q", got)
	}
	var mu sync.Mutex
	probed := map[int32]bool{}
	c.Heap.LookPath = func(name string) (string, error) { return name, nil }
	c.Heap.Runner = func(_ context.Context, name string, args ...string) (string, error) {
		pidArg := args[0]
		if name != "jcmd" {
			pidArg = args[1]
		}
		var pid int32
		_, _ = fmt.Sscan(pidArg, &pid)
		mu.Lock()
		probed[pid] = true
		mu.Unlock()
		if target, ok := targets[pid]; ok && !target.jvm {
			return "", context.DeadlineExceeded
		}
		switch {
		case name == "jcmd":
			return "garbage-first heap total reserved 262144K, committed 262144K, used 36447K\nMetaspace used 1024K, committed 2048K, reserved 4096K\n", nil
		case slices.Contains(args, "-gc"):
			return "S0C S1C S0U S1U EC EU OC OU MC MU YGC YGCT FGC FGCT GCT\n0 0 0 0 10 5 20 10 2048 1024 8 1.2 2 2.3 3.5\n", nil
		case slices.Contains(args, "-compiler"):
			return "Compiled Failed Invalid Time\n10 1 2 3.5\n", nil
		case slices.Contains(args, "-flags"):
			return "-XX:+UseG1GC", nil
		default:
			return "", nil
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	snapshot, err := c.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	c.jvmWG.Wait()
	snapshot, err = c.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	for _, row := range snapshot.Processes {
		want, ok := targets[row.PID]
		if !ok {
			continue
		}
		delete(targets, row.PID)
		if row.Type != want.kind || row.ResidentBytes == nil || row.CPUTotalSecondsTotal == nil {
			t.Errorf("missing kind/OS metrics for %d: %+v", row.PID, row)
		}
		if probed[row.PID] != want.jvm {
			t.Errorf("pid=%d probed=%v want %v", row.PID, probed[row.PID], want.jvm)
		}
		if want.jvm {
			if !row.HeapAvailable || row.HeapUsedMB == nil || row.MetaspaceUsedMB == nil || row.YoungGCCount == nil || row.JIT == nil || row.GC == nil {
				t.Errorf("missing JVM telemetry for %d: %+v", row.PID, row)
			}
		} else if row.HeapAvailable || row.HeapSampledAtMs != nil || len(row.HeapProbeDiagnostics) != 0 {
			t.Errorf("native process has JVM probe data: %+v", row)
		}
	}
	if len(targets) != 0 {
		t.Errorf("missing snapshot processes: %v", targets)
	}
	if metrics := c.Observe.Snapshot(); metrics.ProbeTimeoutTotal != 0 {
		t.Errorf("native processes caused probe timeouts: %+v", metrics)
	}
}
