package heap_test

import (
	"context"
	"errors"
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
