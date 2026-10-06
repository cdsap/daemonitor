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
	if len(calls) != 3 || !strings.Contains(calls[2], "-flags 42") {
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
		return "pid:\nheap total 262144K, committed 262144K, used 36447K\n", nil
	}
	s, err := p.SampleFor(context.Background(), 42, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if s.UsedMB != 35 || s.CommittedMB != 256 {
		t.Fatalf("sample=%+v", s)
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
