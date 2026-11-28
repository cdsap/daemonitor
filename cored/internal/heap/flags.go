package heap

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"maps"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

const maxFlagsOutput = 1024 * 1024
const flagsCacheTTL = 10 * time.Minute

var flagLine = regexp.MustCompile(`^\s*(\w+)\s+([A-Za-z][A-Za-z0-9_]*)\s*:?=\s*(.*?)\s*\{`)
var flagNumber = regexp.MustCompile(`^[+-]?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$`)
var sensitiveFlag = regexp.MustCompile(`(?i)password|token|secret|key|credential`)

type flagsCacheEntry struct {
	metadata  model.JVMFlagsMetadata
	expiresAt time.Time
}

func UnavailableFlags(pid int32, start int64, reason string) model.JVMFlagsMetadata {
	return model.JVMFlagsMetadata{SchemaVersion: 1, PID: pid, StartTimeMs: start,
		CollectedAtMs: time.Now().UnixMilli(), Source: "jcmd.VM.flags-all", Availability: "unavailable", Reason: reason,
		PrivacyPolicy: "numeric-boolean-only-v1", Incomplete: true, Flags: map[string]model.JVMFlag{}}
}

// FlagsFor requires a caller-verified same-user incarnation. Both successes and
// failures are cached separately from dynamic heap metrics for ten minutes.
func (p *Prober) FlagsFor(ctx context.Context, pid int32, start int64) model.JVMFlagsMetadata {
	if p == nil || start <= 0 {
		return UnavailableFlags(pid, start, "identity_unavailable")
	}
	key := cacheKey{pid: pid, startTimeMs: start}
	for {
		now := time.Now()
		p.flagsMu.Lock()
		if e, ok := p.flagsCache[key]; ok && now.Before(e.expiresAt) {
			p.flagsMu.Unlock()
			return cloneFlags(e.metadata)
		}
		if pending, ok := p.flagsPending[key]; ok {
			p.flagsMu.Unlock()
			select {
			case <-ctx.Done():
				return UnavailableFlags(pid, start, FailureTimeout)
			case <-pending:
				continue
			}
		}
		if p.flagsPending == nil {
			p.flagsPending = make(map[cacheKey]chan struct{})
		}
		pending := make(chan struct{})
		p.flagsPending[key] = pending
		for k, e := range p.flagsCache {
			if k.pid == pid || now.After(e.expiresAt) {
				delete(p.flagsCache, k)
			}
		}
		p.flagsMu.Unlock()
		m := p.probeFlags(ctx, pid, start)
		p.flagsMu.Lock()
		if p.flagsCache == nil {
			p.flagsCache = make(map[cacheKey]flagsCacheEntry)
		}
		p.flagsCache[key] = flagsCacheEntry{m, time.Now().Add(flagsCacheTTL)}
		delete(p.flagsPending, key)
		close(pending)
		p.flagsMu.Unlock()
		return cloneFlags(m)
	}
}

func cloneFlags(m model.JVMFlagsMetadata) model.JVMFlagsMetadata {
	if m.Flags == nil {
		m.Flags = map[string]model.JVMFlag{}
	} else {
		m.Flags = maps.Clone(m.Flags)
	}
	return m
}

func (p *Prober) probeFlags(ctx context.Context, pid int32, start int64) model.JVMFlagsMetadata {
	m := UnavailableFlags(pid, start, FailureProbeError)
	timeout := p.Timeout
	if timeout <= 0 {
		timeout = DefaultTimeout
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	tool, err := p.lookPath(ctx, pid, "jcmd")
	if err != nil {
		m.Reason = FailureMissingTool
		return m
	}
	run := p.Runner
	if run == nil {
		run = flagsRunner
	}
	out, err := run(ctx, tool, strconv.Itoa(int(pid)), "VM.flags", "-all")
	if ctx.Err() != nil {
		err = ctx.Err()
	}
	if err != nil {
		m.Reason = classifyFailure(err)
		message := strings.ToLower(err.Error())
		if strings.Contains(message, "attach disabled") || strings.Contains(message, "disableattachmechanism") {
			m.Reason = "attach_disabled"
		}
		if message == "output_limit" {
			m.Reason = "output_limit"
		}
		return m
	}
	if len(out) > maxFlagsOutput {
		m.Reason = "output_limit"
		return m
	}
	parsed := parseEffectiveFlags(out)
	parsed.PID = pid
	parsed.StartTimeMs = start
	return parsed
}

func parseEffectiveFlags(out string) model.JVMFlagsMetadata {
	m := UnavailableFlags(0, 0, FailureParseError)
	if len(out) > maxFlagsOutput {
		m.Reason = "output_limit"
		return m
	}
	count := 0
	for _, line := range strings.Split(out, "\n") {
		fields := flagLine.FindStringSubmatch(line)
		if fields == nil {
			continue
		}
		count++
		if count > 2048 {
			return UnavailableFlags(0, 0, "output_limit")
		}
		typ, name, value := fields[1], fields[2], fields[3]
		safe := false
		switch typ {
		case "bool":
			safe = value == "true" || value == "false"
		case "int", "uint", "intx", "uintx", "uint64_t", "size_t", "double":
			safe = flagNumber.MatchString(value)
		}
		if !safe || sensitiveFlag.MatchString(name) {
			m.OmittedCount++
			continue
		}
		m.Flags[name] = model.JVMFlag{Type: typ, Value: value}
	}
	if count == 0 {
		return m
	}
	m.Availability = "available"
	m.Reason = ""
	m.Incomplete = m.OmittedCount > 0
	return m
}

// Bounded buffers discard excess bytes while the command remains deadline-bound.
// Neither raw stdout nor stderr is cached, stored, or included in the API.
type flagsBuffer struct {
	bytes.Buffer
	overflow bool
}

func (b *flagsBuffer) Write(p []byte) (int, error) {
	n := len(p)
	remaining := maxFlagsOutput - b.Len()
	if len(p) > remaining {
		b.overflow = true
		p = p[:remaining]
	}
	_, _ = b.Buffer.Write(p)
	return n, nil
}
func flagsRunner(ctx context.Context, name string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.WaitDelay = 100 * time.Millisecond
	var out, stderr flagsBuffer
	cmd.Stdout = &out
	cmd.Stderr = &stderr
	err := cmd.Run()
	if ctx.Err() != nil {
		return "", ctx.Err()
	}
	if out.overflow || stderr.overflow {
		return "", errors.New("output_limit")
	}
	if err != nil {
		if stderr.Len() == 0 {
			return "", err
		}
		return "", fmt.Errorf("%s", stderr.String())
	}
	return out.String(), nil
}
