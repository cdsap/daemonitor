package heap

import (
	"context"
	"errors"
	"testing"
	"time"
)

const flagFixture = `42:
[Global flags]
     bool UseG1GC = true {product} {ergonomic}
    uintx MaxHeapSize := 4294967296 {product}
     intx ActiveProcessorCount = -1 {product} {default}
   double MaxRAMPercentage = 25.000000 {product} {default}
    ccstr HeapDumpPath = /Users/private/secret {manageable} {command line}
    ccstr OnError = echo token=secret {product}
    uintx SecretToken = 12345 {product}
`

func TestFlagsPrivacyAndJDKFormats(t *testing.T) {
	m := parseEffectiveFlags(flagFixture)
	if m.Availability != "available" || !m.Incomplete || m.OmittedCount != 3 || len(m.Flags) != 4 {
		t.Fatalf("metadata=%+v", m)
	}
	if m.Flags["MaxHeapSize"].Value != "4294967296" || m.Flags["UseG1GC"].Value != "true" {
		t.Fatalf("flags=%+v", m.Flags)
	}
	for _, name := range []string{"HeapDumpPath", "OnError", "SecretToken"} {
		if _, ok := m.Flags[name]; ok {
			t.Fatal("retained", name)
		}
	}
	if got := parseEffectiveFlags("42:\nnot flags\n"); got.Availability != "unavailable" {
		t.Fatalf("%+v", got)
	}
}

func TestFlagsCacheFailuresAndIncarnations(t *testing.T) {
	for _, failure := range []string{"", "permission denied", "no such process", "AttachNotSupportedException: attach disabled", "missing_tool", "timeout"} {
		t.Run(failure, func(t *testing.T) {
			p := NewProber()
			p.Timeout = time.Millisecond
			p.LookPath = func(string) (string, error) {
				if failure == "missing_tool" {
					return "", errors.New("not found")
				}
				return "jcmd", nil
			}
			calls := 0
			p.Runner = func(ctx context.Context, name string, args ...string) (string, error) {
				calls++
				if len(args) != 3 || args[1] != "VM.flags" || args[2] != "-all" {
					t.Fatalf("args=%v", args)
				}
				if failure == "timeout" {
					<-ctx.Done()
					return "", ctx.Err()
				}
				if failure != "" {
					return "", errors.New(failure)
				}
				return flagFixture, nil
			}
			a := p.FlagsFor(context.Background(), 42, 1000)
			b := p.FlagsFor(context.Background(), 42, 1000)
			if a.CollectedAtMs != b.CollectedAtMs || (failure != "missing_tool" && calls != 1) {
				t.Fatalf("not cached: %d", calls)
			}
			if failure == "" && a.Availability != "available" {
				t.Fatalf("%+v", a)
			}
			if failure != "" && (a.Availability != "unavailable" || len(a.Flags) != 0) {
				t.Fatalf("%+v", a)
			}
			expectedReason := map[string]string{"permission denied": "permission_denied", "no such process": "stale_pid", "AttachNotSupportedException: attach disabled": "attach_disabled", "missing_tool": "missing_tool", "timeout": "timeout"}[failure]
			if a.Reason != expectedReason {
				t.Fatalf("reason=%q want %q", a.Reason, expectedReason)
			}
			p.FlagsFor(context.Background(), 42, 2000)
			if failure != "missing_tool" && calls != 2 {
				t.Fatal("PID reuse inherited flags")
			}
			p.EvictMissing(map[int32]struct{}{})
			p.FlagsFor(context.Background(), 42, 2000)
			if failure != "missing_tool" && calls != 3 {
				t.Fatal("not evicted")
			}
		})
	}
}

func TestFlagsBoundsAndCacheIsolation(t *testing.T) {
	p := NewProber()
	p.LookPath = func(string) (string, error) { return "jcmd", nil }
	calls := 0
	p.Runner = func(context.Context, string, ...string) (string, error) { calls++; return flagFixture, nil }
	first := p.FlagsFor(context.Background(), 42, 1000)
	delete(first.Flags, "MaxHeapSize")
	if got := p.FlagsFor(context.Background(), 42, 1000); len(got.Flags) != 4 {
		t.Fatal("caller mutated cache")
	}
	key := cacheKey{pid: 42, startTimeMs: 1000}
	p.flagsCache[key] = flagsCacheEntry{metadata: first, expiresAt: time.Now().Add(-time.Second)}
	p.FlagsFor(context.Background(), 42, 1000)
	if calls != 2 {
		t.Fatal("expired cache reused")
	}
	oversized := string(make([]byte, maxFlagsOutput+1))
	if got := parseEffectiveFlags(oversized); got.Availability != "unavailable" || got.Reason != "output_limit" {
		t.Fatalf("%+v", got)
	}
	var b flagsBuffer
	if n, err := b.Write([]byte(oversized)); err != nil || n != len(oversized) || !b.overflow || b.Len() != maxFlagsOutput {
		t.Fatal("output buffer unbounded")
	}
	p.Runner = func(context.Context, string, ...string) (string, error) { return oversized, nil }
	if got := p.FlagsFor(context.Background(), 99, 1000); got.Reason != "output_limit" {
		t.Fatalf("%+v", got)
	}
}

func TestFlagsConcurrentSnapshotsShareProbe(t *testing.T) {
	p := NewProber()
	p.LookPath = func(string) (string, error) { return "jcmd", nil }
	entered := make(chan struct{}, 10)
	release := make(chan struct{})
	p.Runner = func(ctx context.Context, _ string, _ ...string) (string, error) {
		entered <- struct{}{}
		select {
		case <-release:
			return flagFixture, nil
		case <-ctx.Done():
			return "", ctx.Err()
		}
	}
	done := make(chan struct{}, 10)
	for i := 0; i < 10; i++ {
		go func() { p.FlagsFor(context.Background(), 42, 1000); done <- struct{}{} }()
	}
	<-entered
	close(release)
	for i := 0; i < 10; i++ {
		<-done
	}
	if len(entered) != 0 {
		t.Fatal("concurrent snapshots attached more than once")
	}
}

func TestFlagsAdditionalHotSpotTypes(t *testing.T) {
	m := parseEffectiveFlags(`123:
[Global flags]
 bool UseParallelGC = false {product} {default}
 size_t MaxHeapSize = 2147483648 {product} {ergonomic}
 uint64_t MaxDirectMemorySize := 18446744073709551615 {product}
 double MinHeapFreeRatio = 1.25e+01 {product} {command line}
 ccstrlist CompileCommand = C:\private\rules.txt {product}
 address UnsafeAddress = 0xdeadbeef {product}
 uintx InvalidValue = 12/path {product}
`)
	if m.Availability != "available" || len(m.Flags) != 4 || m.OmittedCount != 3 || !m.Incomplete {
		t.Fatalf("%+v", m)
	}
	if m.Flags["UseParallelGC"].Value != "false" || m.Flags["MaxDirectMemorySize"].Value != "18446744073709551615" {
		t.Fatalf("%+v", m.Flags)
	}
}
