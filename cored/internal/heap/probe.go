package heap

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
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

// Failure categories are part of the diagnostic contract. They deliberately
// contain no tool output, paths, or JVM properties.
const (
	FailureMissingTool      = "missing_tool"
	FailureTimeout          = "timeout"
	FailurePermissionDenied = "permission_denied"
	FailureStalePID         = "stale_pid"
	FailureParseError       = "parse_error"
	FailureUnsupportedJDK   = "unsupported_jdk"
	FailureProbeError       = "probe_error"
)

// Diagnostic describes one failed probe attempt without exposing its output.
type Diagnostic struct {
	Tool      string
	Operation string
	Category  string
}

// ProbeError is safe to classify and unwrap while keeping its message
// internal to the collector.
type ProbeError struct {
	Category    string
	Diagnostics []Diagnostic
	err         error
}

func (e *ProbeError) Error() string { return e.Category }
func (e *ProbeError) Unwrap() error { return e.err }

// FailureCategory returns the stable category for a probe error.
func FailureCategory(err error) string {
	if err == nil {
		return ""
	}
	var probeErr *ProbeError
	if errors.As(err, &probeErr) {
		return probeErr.Category
	}
	return FailureProbeError
}

// FailureDiagnostics returns sanitized attempt summaries carried by a probe
// error. It returns nil for unrelated errors.
func FailureDiagnostics(err error) []Diagnostic {
	var probeErr *ProbeError
	if err == nil || !errors.As(err, &probeErr) {
		return nil
	}
	return probeErr.Diagnostics
}

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

	var diagnostics []Diagnostic
	jcmdPath, jcmdErr := look("jcmd")
	if jcmdErr != nil {
		diagnostics = append(diagnostics, diagnostic("jcmd", "GC.heap_info", jcmdErr))
	}
	if jcmdErr == nil {
		out, err := run(ctx, jcmdPath, fmt.Sprintf("%d", pid), "GC.heap_info")
		if err == nil {
			s, perr := ParseJcmdHeapInfo(out)
			if perr == nil {
				s.Diagnostics = diagnostics
				if info, infoErr := run(ctx, jcmdPath, fmt.Sprintf("%d", pid), "VM.info"); infoErr == nil {
					// Processor count remains sourced from jcmd; JVM identity is
					// intentionally sourced only from the sysprops allowlist below.
					_, _, s.ActiveProcessorCount = ParseJcmdVMInfo(info)
				} else {
					s.Diagnostics = append(s.Diagnostics, diagnostic("jcmd", "VM.info", infoErr))
				}
				p.supplementGC(ctx, look, run, pid, &s)
				p.collectAllowlistedSysprops(ctx, look, run, pid, &s)
				return s, nil
			}
			diagnostics = append(diagnostics, diagnostic("jcmd", "GC.heap_info", perr))
		} else {
			diagnostics = append(diagnostics, diagnostic("jcmd", "GC.heap_info", err))
		}
	}

	jstatPath, jstatErr := look("jstat")
	if jstatErr != nil {
		diagnostics = append(diagnostics, diagnostic("jstat", "-gc", jstatErr))
	}
	if jstatErr != nil {
		return Sample{}, unavailableError(diagnostics, jcmdErr, jstatErr)
	}
	out, err := run(ctx, jstatPath, "-gc", fmt.Sprintf("%d", pid))
	if err != nil {
		diagnostics = append(diagnostics, diagnostic("jstat", "-gc", err))
		return Sample{}, unavailableError(diagnostics, jcmdErr, err)
	}
	s, err := ParseJstatGC(out)
	if err != nil {
		diagnostics = append(diagnostics, diagnostic("jstat", "-gc", err))
		return Sample{}, unavailableError(diagnostics, jcmdErr, err)
	}
	s.Diagnostics = diagnostics
	p.supplementGC(ctx, look, run, pid, &s)
	p.collectAllowlistedSysprops(ctx, look, run, pid, &s)
	return s, nil
}

// collectAllowlistedSysprops obtains only the JVM properties explicitly
// needed by the process model. Attach failure leaves those values unavailable;
// it never turns the raw property output into a model field or error message.
func (p *Prober) collectAllowlistedSysprops(
	ctx context.Context,
	look func(string) (string, error),
	run func(context.Context, string, ...string) (string, error),
	pid int32,
	sample *Sample,
) {
	if sample == nil {
		return
	}
	jinfoPath, err := look("jinfo")
	if err != nil {
		return
	}
	out, err := run(ctx, jinfoPath, "-sysprops", fmt.Sprintf("%d", pid))
	if err != nil {
		return
	}
	sample.JavaVersion, sample.JavaRuntimeVersion, sample.JavaVendor,
		sample.JavaVMName, sample.JavaVMVersion, sample.OSName, sample.OSArch = ParseJinfoSysprops(out)
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
		sample.Diagnostics = append(sample.Diagnostics, diagnostic("jinfo", "-flags", err))
		return
	}
	out, err := run(ctx, jinfoPath, "-flags", fmt.Sprintf("%d", pid))
	if err == nil {
		sample.GC = ParseJinfoFlags(out)
	} else {
		sample.Diagnostics = append(sample.Diagnostics, diagnostic("jinfo", "-flags", err))
	}
}

func diagnostic(tool, operation string, err error) Diagnostic {
	return Diagnostic{Tool: tool, Operation: operation, Category: classifyFailure(err)}
}

func classifyFailure(err error) string {
	if err == nil {
		return ""
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return FailureTimeout
	}
	message := strings.ToLower(err.Error())
	switch {
	case strings.Contains(message, "no heap"), strings.Contains(message, "need header"), strings.Contains(message, "column count mismatch"), strings.Contains(message, "zero used"):
		return FailureParseError
	case strings.Contains(message, "permission denied"), strings.Contains(message, "operation not permitted"), strings.Contains(message, "access is denied"), strings.Contains(message, "attach denied"):
		return FailurePermissionDenied
	case strings.Contains(message, "no such process"), strings.Contains(message, "process not found"), strings.Contains(message, "could not find"):
		return FailureStalePID
	case strings.Contains(message, "unsupported"), strings.Contains(message, "not supported"), strings.Contains(message, "unrecognized option"), strings.Contains(message, "not recognized"):
		return FailureUnsupportedJDK
	case strings.Contains(message, "not found"), strings.Contains(message, "executable file"), strings.Contains(message, "cannot find"):
		return FailureMissingTool
	default:
		return FailureProbeError
	}
}

func unavailableError(diagnostics []Diagnostic, first, last error) error {
	category := FailureProbeError
	if len(diagnostics) > 0 {
		category = diagnostics[len(diagnostics)-1].Category
	}
	return &ProbeError{Category: category, Diagnostics: diagnostics, err: fmt.Errorf("%w: %v", ErrUnavailable, lastOrFirst(first, last))}
}

func lastOrFirst(first, last error) error {
	if last != nil {
		return last
	}
	return first
}

func defaultRunner(ctx context.Context, name string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	if err != nil {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
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
