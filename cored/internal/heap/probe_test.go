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
	if calls != 1 {
		t.Fatalf("expected 1 probe call, got %d", calls)
	}
	// Different startTimeMs → cache miss
	if _, err := p.SampleFor(ctx, 7, 100); err != nil {
		t.Fatal(err)
	}
	if calls != 2 {
		t.Fatalf("expected 2 probe calls after identity change, got %d", calls)
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

func TestParseJstatZeroUsedAndCommitted(t *testing.T) {
	out := "S0C S1C S0U S1U EC EU OC OU\n0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0\n"
	_, err := heap.ParseJstatGC(out)
	if err == nil {
		t.Fatal("expected error for zero used+committed")
	}
}
