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

	mu    sync.Mutex
	cache map[cacheKey]cacheEntry
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
	}
}

// LookPathWithJavaHome prefers JAVA_HOME/bin tools, then PATH.
func LookPathWithJavaHome(name string) (string, error) {
	jcmd, jstat := FindJavaHomeTools()
	switch name {
	case "jcmd":
		if jcmd != "" {
			return jcmd, nil
		}
	case "jstat":
		if jstat != "" {
			return jstat, nil
		}
	}
	return exec.LookPath(name)
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
	return s, nil
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
	home := os.Getenv("JAVA_HOME")
	if home == "" {
		return "", ""
	}
	jcmd = filepath.Join(home, "bin", "jcmd")
	jstat = filepath.Join(home, "bin", "jstat")
	if _, err := os.Stat(jcmd); err != nil {
		jcmd = ""
	}
	if _, err := os.Stat(jstat); err != nil {
		jstat = ""
	}
	return jcmd, jstat
}
