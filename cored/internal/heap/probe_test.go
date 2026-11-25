package heap_test

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/heap"
)

func TestProberPrefersJcmdThenFallsBackToJstat(t *testing.T) {
	p := heap.NewProber()
	p.LookPath = func(name string) (string, error) {
		switch name {
		case "jcmd":
			return "/fake/jcmd", nil
		case "jstat":
			return "/fake/jstat", nil
		default:
			return "", errors.New("missing")
		}
	}
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		if strings.Contains(name, "jcmd") {
			return "", errors.New("attach failed")
		}
		return jstatGCFixture, nil
	}
	s, err := p.SampleFor(context.Background(), 42, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if s.Source != "jstat" {
		t.Fatalf("expected jstat fallback, got %s", s.Source)
	}
}

func TestProberUsesJinfoOnlyToSupplementUnknownGC(t *testing.T) {
	p := heap.NewProber()
	calls := []string{}
	p.LookPath = func(name string) (string, error) {
		if name == "jcmd" || name == "jinfo" {
			return "/fake/" + name, nil
		}
		return "", errors.New("missing")
	}
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		calls = append(calls, name+" "+strings.Join(args, " "))
		switch {
		case strings.Contains(name, "jcmd") && strings.Contains(strings.Join(args, " "), "GC.heap_info"):
			return "pid:\nheap total reserved 128K, committed 128K, used 64K\n", nil
		case strings.Contains(name, "jcmd"):
			return "", errors.New("unsupported VM.info")
		case strings.Contains(name, "jinfo"):
			return "-XX:+UseG1GC\n", nil
		default:
			return "", errors.New("unexpected command")
		}
	}
	s, err := p.SampleFor(context.Background(), 42, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if s.GC == nil || *s.GC != "G1" {
		t.Fatalf("gc=%v", s.GC)
	}
	if len(calls) != 4 || !strings.Contains(calls[2], "-flags 42") || !strings.Contains(calls[3], "-sysprops 42") {
		t.Fatalf("calls=%v", calls)
	}
}

func TestProberDoesNotRequireJinfoForHeapSample(t *testing.T) {
	p := heap.NewProber()
	p.LookPath = func(name string) (string, error) {
		if name == "jcmd" || name == "jinfo" {
			return "/fake/" + name, nil
		}
		return "", errors.New("missing")
	}
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		if strings.Contains(name, "jinfo") {
			return "", errors.New("attach denied")
		}
		return jcmdHeapInfoFixture, nil
	}
	s, err := p.SampleFor(context.Background(), 42, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if s.UsedMB != 35 || s.CommittedMB != 256 {
		t.Fatalf("sample=%+v", s)
	}
}

func TestProberSupplementsJcmdHeapSampleWithJstatGCTimes(t *testing.T) {
	p := heap.NewProber()
	p.LookPath = func(name string) (string, error) {
		switch name {
		case "jcmd", "jstat":
			return "/fake/" + name, nil
		default:
			return "", errors.New("missing")
		}
	}
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		switch {
		case strings.Contains(name, "jcmd"):
			return jcmdHeapInfoFixture, nil
		case strings.Contains(name, "jstat"):
			return jstatGCFixture, nil
		default:
			return "", errors.New("unexpected command")
		}
	}

	s, err := p.SampleFor(context.Background(), 42, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if s.Source != "jcmd" || s.YoungGCTimeSeconds == nil || *s.YoungGCTimeSeconds != 1.234 ||
		s.FullGCTimeSeconds == nil || *s.FullGCTimeSeconds != 2.345 ||
		s.ConcurrentGCTimeSeconds == nil || *s.ConcurrentGCTimeSeconds != 3.456 ||
		s.TotalGCTimeSeconds == nil || *s.TotalGCTimeSeconds != 7.035 {
		t.Fatalf("sample=%+v", s)
	}
}

func TestProberAdvancedTelemetryIsBestEffort(t *testing.T) {
	p := heap.NewProber()
	p.EnableAdvancedTelemetry = true
	p.LookPath = func(name string) (string, error) { return "/fake/" + name, nil }
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		if strings.Contains(name, "jcmd") {
			switch args[len(args)-1] {
			case "Compiler.codecache":
				return "CodeHeap 'profiled nmethods': size=10Kb used=2Kb max_used=3Kb free=8Kb\n", nil
			case "PerfCounter.print":
				return "sun.rt.safepoints=3\nsun.rt.safepointTime=7\n", nil
			default:
				return jcmdHeapInfoFixture, nil
			}
		}
		if strings.Contains(args[0], "-compiler") {
			return "Compiled Failed Invalid Time\n10 1 2 3.5\n", nil
		}
		if strings.Contains(args[0], "-class") {
			return "Loaded Bytes Unloaded Bytes Time\n12 1K 2 2K 3.5\n", nil
		}
		if strings.Contains(args[0], "-gc") {
			return jstatGCFixture, nil
		}
		return "", errors.New("attach denied")
	}
	s, err := p.SampleFor(context.Background(), 42, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if s.JIT == nil || s.CodeCache == nil || s.Classes == nil || s.Safepoints == nil {
		t.Fatalf("advanced telemetry=%+v", s)
	}
}

func TestProberAdvancedParseFailuresAreSanitized(t *testing.T) {
	for _, operation := range []string{"-compiler", "-class", "Compiler.codecache", "PerfCounter.print"} {
		t.Run(operation, func(t *testing.T) {
			p := heap.NewProber()
			p.EnableAdvancedTelemetry = true
			p.LookPath = func(name string) (string, error) { return "/fake/" + name, nil }
			p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
				op := args[0]
				if strings.HasSuffix(name, "jcmd") {
					op = args[len(args)-1]
				}
				if op == operation {
					return "private.Example secret failedMethod", nil
				}
				switch op {
				case "-compiler":
					return "Compiled Failed Invalid Time FailedType FailedMethod\n128 0 0 0.02 0\n", nil
				case "-class":
					return jstatClassFixture, nil
				case "Compiler.codecache":
					return codeCacheFixture, nil
				case "PerfCounter.print":
					return perfCounterSafepointFixture, nil
				case "GC.heap_info", "VM.info":
					return jcmdHeapInfoFixture, nil
				case "-gc":
					return jstatGCFixture, nil
				default:
					return "", nil
				}
			}
			s, err := p.SampleFor(context.Background(), 42, 1000)
			if err != nil {
				t.Fatal(err)
			}
			if s.UsedMB != 35 || s.CommittedMB != 256 || s.TotalGCTimeSeconds == nil {
				t.Fatalf("lost heap/GC sample: %+v", s)
			}
			if (s.JIT == nil) != (operation == "-compiler") || (s.Classes == nil) != (operation == "-class") || (s.CodeCache == nil) != (operation == "Compiler.codecache") || (s.Safepoints == nil) != (operation == "PerfCounter.print") {
				t.Fatalf("unrelated metrics lost: %+v", s)
			}
			tool := "jcmd"
			if strings.HasPrefix(operation, "-") {
				tool = "jstat"
			}
			want := heap.Diagnostic{Tool: tool, Operation: operation, Category: heap.FailureParseError}
			if len(s.Diagnostics) != 1 || s.Diagnostics[0] != want {
				t.Fatalf("diagnostics=%+v want %+v", s.Diagnostics, want)
			}
			encoded, err := json.Marshal(s.Diagnostics)
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(string(encoded), "private.Example") || strings.Contains(string(encoded), "secret") || strings.Contains(string(encoded), "failedMethod") {
				t.Fatalf("raw output leaked: %s", encoded)
			}
		})
	}
}

func jcmdToolPath(dir string) string {
	name := "jcmd"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	return filepath.Join(dir, "bin", name)
}

func TestLookPathWithJDKDirPrecedesJavaHomeAndPath(t *testing.T) {
	dir := t.TempDir()
	if err := os.Mkdir(filepath.Join(dir, "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	tool := jcmdToolPath(dir)
	if err := os.WriteFile(tool, []byte(""), 0o755); err != nil {
		t.Fatal(err)
	}
	got, err := heap.LookPathWithJDKDir(dir, "jcmd")
	if err != nil {
		t.Fatal(err)
	}
	if got != tool {
		t.Fatalf("tool=%q want %q", got, tool)
	}
}

func TestProberTargetJDKPrecedesAmbientTools(t *testing.T) {
	configured, target, ambient := t.TempDir(), t.TempDir(), t.TempDir()
	for _, dir := range []string{configured, target, ambient} {
		if err := os.Mkdir(filepath.Join(dir, "bin"), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(jcmdToolPath(dir), nil, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("JAVA_HOME", ambient)
	t.Setenv("PATH", "")
	for _, tc := range []struct{ name, configured, target, want string }{
		{"target before ambient", "", target, target},
		{"configured before target", configured, target, configured},
		{"missing configured falls through to target", t.TempDir(), target, target},
		{"missing target falls through to ambient", "", t.TempDir(), ambient},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p := heap.NewProber()
			p.JDKDir = tc.configured
			p.TargetJDKDir = func(pid int32) string {
				if pid != 42 {
					t.Fatalf("target pid=%d", pid)
				}
				return tc.target
			}
			p.Runner = func(_ context.Context, name string, _ ...string) (string, error) {
				if name != jcmdToolPath(tc.want) {
					t.Errorf("tool=%q, want tools from %q", name, tc.want)
				}
				return jcmdHeapInfoFixture, nil
			}
			if _, err := p.SampleFor(context.Background(), 42, 1); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestProberEmbeddedRuntimeWithoutTools(t *testing.T) {
	t.Setenv("JAVA_HOME", t.TempDir())
	t.Setenv("PATH", "")
	p := heap.NewProber()
	embedded := t.TempDir()
	p.TargetJDKDir = func(int32) string { return embedded }
	p.Runner = func(context.Context, string, ...string) (string, error) {
		t.Fatal("missing tools must not start an attach command")
		return "", nil
	}
	_, err := p.SampleFor(context.Background(), 42, 1)
	if heap.FailureCategory(err) != heap.FailureMissingTool {
		t.Fatalf("category=%q err=%v", heap.FailureCategory(err), err)
	}
	diagnostics := heap.FailureDiagnostics(err)
	if len(diagnostics) != 2 {
		t.Fatalf("diagnostics=%+v", diagnostics)
	}
	for _, d := range diagnostics {
		if d.Category != heap.FailureMissingTool {
			t.Fatalf("diagnostic=%+v", d)
		}
	}
}

func TestProberFailureCategoriesAreStableAndSanitized(t *testing.T) {
	tests := []struct {
		name     string
		err      error
		category string
	}{
		{name: "timeout", err: context.DeadlineExceeded, category: heap.FailureTimeout},
		{name: "permission", err: errors.New("attach denied"), category: heap.FailurePermissionDenied},
		{name: "stale pid", err: errors.New("Could not find process 42"), category: heap.FailureStalePID},
		{name: "unsupported jdk", err: errors.New("unsupported option"), category: heap.FailureUnsupportedJDK},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := heap.NewProber()
			p.LookPath = func(name string) (string, error) { return "/fake/" + name, nil }
			p.Runner = func(context.Context, string, ...string) (string, error) { return "", tt.err }
			_, err := p.SampleFor(context.Background(), 42, 1)
			if err == nil || heap.FailureCategory(err) != tt.category {
				t.Fatalf("error=%v category=%q", err, heap.FailureCategory(err))
			}
			diagnostics := heap.FailureDiagnostics(err)
			if len(diagnostics) == 0 || diagnostics[len(diagnostics)-1].Category != tt.category {
				t.Fatalf("diagnostics=%+v", diagnostics)
			}
			if strings.Contains(err.Error(), "42") || strings.Contains(err.Error(), "attach denied") {
				t.Fatalf("probe error leaked target/output: %v", err)
			}
		})
	}

	t.Run("parse error", func(t *testing.T) {
		p := heap.NewProber()
		p.LookPath = func(name string) (string, error) { return "/fake/" + name, nil }
		p.Runner = func(context.Context, string, ...string) (string, error) { return "not a heap dump", nil }
		_, err := p.SampleFor(context.Background(), 42, 1)
		if heap.FailureCategory(err) != heap.FailureParseError {
			t.Fatalf("category=%q error=%v", heap.FailureCategory(err), err)
		}
	})

	t.Run("missing tool", func(t *testing.T) {
		p := heap.NewProber()
		p.LookPath = func(string) (string, error) { return "", errors.New("executable file not found") }
		_, err := p.SampleFor(context.Background(), 42, 1)
		if heap.FailureCategory(err) != heap.FailureMissingTool {
			t.Fatalf("category=%q error=%v", heap.FailureCategory(err), err)
		}
	})
}

func TestProberRetainsOnlyAllowlistedJinfoMetadata(t *testing.T) {
	p := heap.NewProber()
	p.LookPath = func(name string) (string, error) {
		if name == "jcmd" || name == "jinfo" {
			return "/fake/" + name, nil
		}
		return "", errors.New("missing")
	}
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		if strings.Contains(name, "jcmd") {
			return jcmdHeapInfoFixture, nil
		}
		return "java.version=21\njava.vendor=Temurin\nos.name=Linux\njava.home=/secret\n", nil
	}
	s, err := p.SampleFor(context.Background(), 42, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if s.JavaVersion == nil || *s.JavaVersion != "21" || s.JavaVendor == nil || *s.JavaVendor != "Temurin" || s.OSName == nil || *s.OSName != "Linux" {
		t.Fatalf("metadata=%+v", s)
	}
	if s.JavaRuntimeVersion != nil || s.JavaVMName != nil || s.JavaVMVersion != nil || s.OSArch != nil {
		t.Fatalf("unexpected metadata=%+v", s)
	}
}

func TestProberLeavesJinfoMetadataUnavailableWhenAttachFails(t *testing.T) {
	p := heap.NewProber()
	p.LookPath = func(name string) (string, error) {
		if name == "jstat" || name == "jinfo" {
			return "/fake/" + name, nil
		}
		return "", errors.New("missing")
	}
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		if strings.Contains(name, "jstat") {
			return jstatGCFixture, nil
		}
		return "", errors.New("attach failed")
	}
	s, err := p.SampleFor(context.Background(), 42, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if s.UsedMB != 34 || s.CommittedMB != 256 {
		t.Fatalf("sample=%+v", s)
	}
	if s.JavaVersion != nil || s.JavaVendor != nil || s.OSName != nil || s.OSArch != nil {
		t.Fatalf("metadata should be unavailable after attach failure: %+v", s)
	}
}

func TestProberCacheTTL(t *testing.T) {
	p := heap.NewProber()
	p.CacheTTL = time.Hour
	calls := 0
	p.LookPath = func(name string) (string, error) {
		if name == "jcmd" {
			return "/fake/jcmd", nil
		}
		return "", errors.New("no jstat")
	}
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		calls++
		return jcmdHeapInfoFixture, nil
	}
	ctx := context.Background()
	if _, err := p.SampleFor(ctx, 7, 99); err != nil {
		t.Fatal(err)
	}
	if _, err := p.SampleFor(ctx, 7, 99); err != nil {
		t.Fatal(err)
	}
	if calls != 2 {
		t.Fatalf("expected 2 probe calls, got %d", calls)
	}
	// Different startTimeMs → cache miss
	if _, err := p.SampleFor(ctx, 7, 100); err != nil {
		t.Fatal(err)
	}
	if calls != 4 {
		t.Fatalf("expected 4 probe calls after identity change, got %d", calls)
	}
}

func TestProberUnavailableWhenToolsMissing(t *testing.T) {
	p := heap.NewProber()
	p.LookPath = func(string) (string, error) {
		return "", errors.New("not found")
	}
	_, err := p.SampleFor(context.Background(), 1, 1)
	if err == nil {
		t.Fatal("expected unavailable")
	}
	if !errors.Is(err, heap.ErrUnavailable) {
		t.Fatalf("want ErrUnavailable, got %v", err)
	}
}

func TestProberUnavailableOnEmptyParse(t *testing.T) {
	p := heap.NewProber()
	p.LookPath = func(name string) (string, error) {
		return "/fake/" + name, nil
	}
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		return "not a heap dump\n", nil
	}
	_, err := p.SampleFor(context.Background(), 9, 1)
	if err == nil {
		t.Fatal("expected unavailable")
	}
	if !errors.Is(err, heap.ErrUnavailable) {
		t.Fatalf("want ErrUnavailable, got %v", err)
	}
}

func TestProberGCFlagsUsesBoundedCachedAttachProbe(t *testing.T) {
	p := heap.NewProber()
	p.CacheTTL = time.Hour
	p.LookPath = func(name string) (string, error) {
		if name == "jinfo" {
			return "/fake/jinfo", nil
		}
		return "", errors.New("not found")
	}
	calls := 0
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		calls++
		if name != "/fake/jinfo" || len(args) != 2 || args[0] != "-flags" || args[1] != "42" {
			t.Fatalf("unexpected jinfo command: %s %v", name, args)
		}
		return "VM Flags: -XX:+UseG1GC\n", nil
	}

	got, err := p.GCFor(context.Background(), 42, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if got == nil || *got != "G1" {
		t.Fatalf("gc=%v", got)
	}
	if _, err := p.GCFor(context.Background(), 42, 1000); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("expected cached probe, got %d calls", calls)
	}
}

func TestProberGCFlagsReturnsUnavailableWhenJinfoIsMissing(t *testing.T) {
	p := heap.NewProber()
	p.LookPath = func(name string) (string, error) {
		if name == "jinfo" {
			return "", errors.New("permission denied")
		}
		return "", errors.New("not found")
	}
	if _, err := p.GCFor(context.Background(), 42, 1000); err == nil || !errors.Is(err, heap.ErrUnavailable) {
		t.Fatalf("want unavailable jinfo error, got %v", err)
	}
}

func TestProberGCFlagsHonorsTimeout(t *testing.T) {
	p := heap.NewProber()
	p.Timeout = 10 * time.Millisecond
	p.LookPath = func(string) (string, error) { return "/fake/jinfo", nil }
	p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
		<-ctx.Done()
		return "", ctx.Err()
	}
	if _, err := p.GCFor(context.Background(), 42, 1000); err == nil || !errors.Is(err, heap.ErrUnavailable) {
		t.Fatalf("want unavailable timeout error, got %v", err)
	}
}

func TestParseJstatZeroUsedAndCommitted(t *testing.T) {
	out := "S0C S1C S0U S1U EC EU OC OU\n0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0\n"
	_, err := heap.ParseJstatGC(out)
	if err == nil {
		t.Fatal("expected error for zero used+committed")
	}
}
