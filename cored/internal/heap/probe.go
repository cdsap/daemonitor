package heap

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"
)

const (
	DefaultTimeout     = 750 * time.Millisecond
	DefaultCacheTTL    = 10 * time.Second
	defaultProbeBudget = DefaultTimeout
)

// ErrUnavailable means the probe failed or tools were missing.
var ErrUnavailable = errors.New("live heap unavailable")

// Prober obtains live heap samples for HotSpot PIDs via jcmd/jstat.
type Prober struct {
	Timeout  time.Duration
	CacheTTL time.Duration
	// LookPath finds an executable (defaults to exec.LookPath).
	LookPath func(string) (string, error)
	// Runner runs a command; defaults to exec.CommandContext.
	Runner func(ctx context.Context, name string, args ...string) (stdout string, err error)

	mu      sync.Mutex
	cache   map[cacheKey]cacheEntry
	gcCache map[cacheKey]gcCacheEntry
}

type cacheKey struct {
	pid         int32
	startTimeMs int64
}

type cacheEntry struct {
	sample    Sample
	expiresAt time.Time
}

func NewProber() *Prober {
	return &Prober{
		Timeout:  DefaultTimeout,
		CacheTTL: DefaultCacheTTL,
		LookPath: LookPathWithJavaHome,
		cache:    make(map[cacheKey]cacheEntry),
		gcCache:  make(map[cacheKey]gcCacheEntry),
	}
}

type gcCacheEntry struct {
	gc        *string
	expiresAt time.Time
}

// GCFor returns the selected collector from a bounded, cached jinfo probe.
func (p *Prober) GCFor(ctx context.Context, pid int32, startTimeMs int64) (*string, error) {
	if p == nil {
		return nil, ErrUnavailable
	}
	ttl := p.CacheTTL
	if ttl <= 0 {
		ttl = DefaultCacheTTL
	}
	key := cacheKey{pid: pid, startTimeMs: startTimeMs}
	now := time.Now()
	p.mu.Lock()
	if e, ok := p.gcCache[key]; ok && now.Before(e.expiresAt) {
		gc := e.gc
		p.mu.Unlock()
		return gc, nil
	}
	p.mu.Unlock()

	gc, err := p.probeGC(ctx, pid)
	if err != nil {
		p.mu.Lock()
		delete(p.gcCache, key)
		p.mu.Unlock()
		return nil, err
	}
	p.mu.Lock()
	if p.gcCache == nil {
		p.gcCache = make(map[cacheKey]gcCacheEntry)
	}
	p.gcCache[key] = gcCacheEntry{gc: gc, expiresAt: now.Add(ttl)}
	p.mu.Unlock()
	return gc, nil
}

// LookPathWithJavaHome prefers JAVA_HOME/bin tools, then PATH.
func LookPathWithJavaHome(name string) (string, error) {
	if path := findJavaHomeTool(name); path != "" {
		return path, nil
	}
	return exec.LookPath(name)
}

func findJavaHomeTool(name string) string {
	home := os.Getenv("JAVA_HOME")
	if home == "" {
		return ""
	}
	path := filepath.Join(home, "bin", name)
	if _, err := os.Stat(path); err != nil {
		return ""
	}
	return path
}

// SampleFor returns a cached or fresh live-heap sample for pid.
// startTimeMs keys the cache so PID reuse cannot return another lifetime's sample.
func (p *Prober) SampleFor(ctx context.Context, pid int32, startTimeMs int64) (Sample, error) {
	if p == nil {
		return Sample{}, ErrUnavailable
	}
	ttl := p.CacheTTL
	if ttl <= 0 {
		ttl = DefaultCacheTTL
	}
	key := cacheKey{pid: pid, startTimeMs: startTimeMs}
	now := time.Now()
	p.mu.Lock()
	if e, ok := p.cache[key]; ok && now.Before(e.expiresAt) {
		s := e.sample
		p.mu.Unlock()
		return s, nil
	}
	p.mu.Unlock()

	sample, err := p.probe(ctx, pid)
	if err != nil {
		p.mu.Lock()
		delete(p.cache, key)
		p.mu.Unlock()
		return Sample{}, err
	}
	p.mu.Lock()
	p.cache[key] = cacheEntry{sample: sample, expiresAt: now.Add(ttl)}
	p.mu.Unlock()
	return sample, nil
}

// EvictMissing drops cache entries whose pid is not in live.
func (p *Prober) EvictMissing(live map[int32]struct{}) {
	if p == nil {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	for k := range p.cache {
		if _, ok := live[k.pid]; !ok {
			delete(p.cache, k)
		}
	}
	for k := range p.gcCache {
		if _, ok := live[k.pid]; !ok {
			delete(p.gcCache, k)
		}
	}
}

func (p *Prober) probeGC(ctx context.Context, pid int32) (*string, error) {
	timeout := p.Timeout
	if timeout <= 0 {
		timeout = defaultProbeBudget
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	look := p.LookPath
	if look == nil {
		look = exec.LookPath
	}
	run := p.Runner
	if run == nil {
		run = defaultRunner
	}
	jinfoPath, err := look("jinfo")
	if err != nil {
		return nil, fmt.Errorf("%w: jinfo not on PATH", ErrUnavailable)
	}
	out, err := run(ctx, jinfoPath, "-flags", fmt.Sprintf("%d", pid))
	if err != nil {
		return nil, fmt.Errorf("%w: jinfo: %v", ErrUnavailable, err)
	}
	gc, err := ParseJinfoFlagsGC(out)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	return gc, nil
}

func (p *Prober) probe(ctx context.Context, pid int32) (Sample, error) {
	timeout := p.Timeout
	if timeout <= 0 {
		timeout = defaultProbeBudget
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	look := p.LookPath
	if look == nil {
		look = exec.LookPath
	}
	run := p.Runner
	if run == nil {
		run = defaultRunner
	}

	jcmdPath, jcmdErr := look("jcmd")
	if jcmdErr == nil {
		out, err := run(ctx, jcmdPath, fmt.Sprintf("%d", pid), "GC.heap_info")
		if err == nil {
			if s, perr := ParseJcmdHeapInfo(out); perr == nil {
				if info, infoErr := run(ctx, jcmdPath, fmt.Sprintf("%d", pid), "VM.info"); infoErr == nil {
					s.JavaVersion, s.JavaVendor, s.ActiveProcessorCount = ParseJcmdVMInfo(info)
				}
				p.supplementGC(ctx, look, run, pid, &s)
				return s, nil
			}
		}
	}

	jstatPath, jstatErr := look("jstat")
	if jstatErr != nil {
		if jcmdErr != nil {
			return Sample{}, fmt.Errorf("%w: jcmd and jstat not on PATH", ErrUnavailable)
		}
		return Sample{}, fmt.Errorf("%w: jcmd failed and jstat missing", ErrUnavailable)
	}
	out, err := run(ctx, jstatPath, "-gc", fmt.Sprintf("%d", pid))
	if err != nil {
		return Sample{}, fmt.Errorf("%w: jstat: %v", ErrUnavailable, err)
	}
	s, err := ParseJstatGC(out)
	if err != nil {
		return Sample{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	p.supplementGC(ctx, look, run, pid, &s)
	return s, nil
}

// supplementGC uses jinfo only when the live-heap probe did not identify a
// collector. It never makes a successful heap sample unavailable, and it does
// not collect jinfo system properties.
func (p *Prober) supplementGC(
	ctx context.Context,
	look func(string) (string, error),
	run func(context.Context, string, ...string) (string, error),
	pid int32,
	sample *Sample,
) {
	if sample == nil || sample.GC != nil {
		return
	}
	jinfoPath, err := look("jinfo")
	if err != nil {
		return
	}
	out, err := run(ctx, jinfoPath, "-flags", fmt.Sprintf("%d", pid))
	if err == nil {
		sample.GC = ParseJinfoFlags(out)
	}
}

func defaultRunner(ctx context.Context, name string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	if err != nil {
		msg := stringsTrim(stderr.String())
		if msg == "" {
			msg = err.Error()
		}
		return "", errors.New(msg)
	}
	return stdout.String(), nil
}

func stringsTrim(s string) string {
	return string(bytes.TrimSpace([]byte(s)))
}

// FindJavaHomeTools returns jcmd/jstat under JAVA_HOME/bin when set.
func FindJavaHomeTools() (jcmd, jstat string) {
	jcmd = findJavaHomeTool("jcmd")
	jstat = findJavaHomeTool("jstat")
	return jcmd, jstat
}
