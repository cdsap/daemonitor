package bep

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/shirou/gopsutil/v4/process"
)

const (
	maxTrackedFiles = 256
	maxSeenBuilds   = 4096
	maxFileBytes    = 32 * 1024 * 1024
	// Poll budgets include prefix checks; larger streams resume next poll.
	maxFilePollBytes = 256 * 1024
	maxPollBytes     = 1024 * 1024
)

// Watcher discovers opportunistic BEP sinks and closes builds from JSON or binary files.
type Watcher struct {
	pollMu    sync.Mutex // owns parser state and the reusable read buffer
	mu        sync.Mutex
	files     map[string]*trackedFile
	seenRank  map[string]int
	seenOrder []string
	seenNext  int
	readBuf   [64 * 1024]byte

	ambientEnvNames map[string]struct{}
	// Optional extra absolute paths / globs resolved by the caller each poll.
	ExtraPaths func() []Sink
}

type trackedFile struct {
	lastObserved time.Time
	sink         Sink
	offset       int64
	pending      []byte
	state        partial
	identity     os.FileInfo
	prefix       []byte
	client       clientObservation
}

// clientObservation retains fixed attribution only, never ancestry argv or env values.
// The time window prevents attribution leaking to another invocation reusing a sink.
type clientObservation struct {
	Sink         Sink
	Agent        *builds.AgentAttribution
	StartTimeMs  int64
	ObservedAtMs int64
}

func NewWatcher() *Watcher {
	ambient := make(map[string]struct{})
	for _, entry := range os.Environ() {
		name, _, _ := strings.Cut(entry, "=")
		ambient[strings.Clone(name)] = struct{}{}
	}
	return &Watcher{
		files:           make(map[string]*trackedFile),
		seenRank:        make(map[string]int),
		ambientEnvNames: ambient,
	}
}

// observeClient is called during process Pass 0, before slower JVM probes and
// before short-lived clients disappear. Evidence survives until BEP is read.
func (w *Watcher) observeClient(client clientObservation) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if client.Sink.Path == "" {
		return
	}
	tf := w.trackSink(client.Sink, time.Now())
	if tf.client.Sink.ClientPID == client.Sink.ClientPID && tf.client.StartTimeMs == client.StartTimeMs && client.Agent == nil {
		client.Agent = tf.client.Agent
	}
	tf.client = client
}

// trackSink requires mu. Evict the least recently observed sink at capacity.
func (w *Watcher) trackSink(sink Sink, now time.Time) *trackedFile {
	tf := w.files[sink.Path]
	if tf == nil {
		if len(w.files) >= maxTrackedFiles {
			var oldest string
			var at time.Time
			for path, f := range w.files {
				if oldest == "" || f.lastObserved.Before(at) {
					oldest, at = path, f.lastObserved
				}
			}
			delete(w.files, oldest)
		}
		tf = &trackedFile{}
		w.files[sink.Path] = tf
	}
	tf.sink = sink
	tf.lastObserved = now
	return tf
}

// Poll reads sinks supplied by ObserveProcess and ExtraPaths.
func (w *Watcher) Poll(ctx context.Context) ([]builds.Build, error) {
	w.pollMu.Lock()
	defer w.pollMu.Unlock()
	var sinks []Sink
	if w.ExtraPaths != nil {
		sinks = append(sinks, w.ExtraPaths()...)
	}

	now := time.Now()
	w.mu.Lock()
	for path, tf := range w.files {
		if !tf.lastObserved.IsZero() && now.Sub(tf.lastObserved) > 24*time.Hour {
			delete(w.files, path)
		}
	}
	for _, sink := range sinks {
		if sink.Path != "" {
			w.trackSink(sink, now)
		}
	}
	// Each copy owns parser state for this poll; observations may still update
	// client attribution while disk I/O is in progress.
	files := make(map[*trackedFile]trackedFile, len(w.files))
	for _, tf := range w.files {
		files[tf] = *tf
	}
	w.mu.Unlock()

	var closed []builds.Build
	budget := int64(maxPollBytes)
	for original, state := range files {
		if budget < 64 || ctx.Err() != nil {
			break
		}
		if state.sink.Format != FormatJSON && state.sink.Format != FormatBinary {
			continue
		}
		rows, err := w.readFile(&state, &budget)
		w.mu.Lock()
		if w.files[state.sink.Path] == original {
			if errors.Is(err, os.ErrNotExist) {
				delete(w.files, state.sink.Path)
			} else {
				state.client = original.client
				state.sink = original.sink
				state.lastObserved = original.lastObserved
				*original = state
			}
		}
		w.mu.Unlock()
		for _, b := range rows {
			if b.BuildID == "" {
				continue
			}
			rank := buildDetailRank(b)
			if prev, seen := w.seenRank[b.BuildID]; seen && rank <= prev {
				continue
			}
			if _, seen := w.seenRank[b.BuildID]; !seen {
				if len(w.seenOrder) < maxSeenBuilds {
					w.seenOrder = append(w.seenOrder, b.BuildID)
				} else {
					delete(w.seenRank, w.seenOrder[w.seenNext])
					w.seenOrder[w.seenNext] = b.BuildID
					w.seenNext = (w.seenNext + 1) % maxSeenBuilds
				}
			}
			w.seenRank[b.BuildID] = rank
			closed = coalesceBuilds(closed, b)
		}
	}
	return closed, nil
}

func buildDetailRank(b builds.Build) int {
	if b.BazelDetails == nil {
		return 1
	}
	if b.BazelDetails.CollectionState == builds.CollectionStateSealed || b.BazelDetails.CollectionState == builds.CollectionStateTruncated {
		return 4
	}
	m := b.BazelDetails.Metrics
	if m.Memory != nil || m.Timing != nil || m.Actions != nil || len(m.Workers) > 0 || len(m.WorkerPools) > 0 || len(m.Tests) > 0 || m.Diagnostics != nil {
		return 3
	}
	return 2
}

// openSink validates both the path and the opened descriptor. The identity
// check rejects replacements between Lstat and open, before any bytes are read.
func openSink(path string) (*os.File, os.FileInfo, error) {
	before, err := os.Lstat(path)
	if err != nil {
		return nil, nil, err
	}
	valid := func(info os.FileInfo) bool {
		return info.Mode().IsRegular() && info.Size() >= 0 && info.Size() <= maxFileBytes && ownedFile(info)
	}
	if !valid(before) {
		return nil, nil, errors.New("BEP sink is not a user-owned bounded regular file")
	}
	f, err := openSinkFile(path)
	if err != nil {
		return nil, nil, err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, nil, err
	}
	if !valid(info) || !os.SameFile(before, info) {
		f.Close()
		return nil, nil, errors.New("BEP sink changed or is unsafe")
	}
	return f, info, nil
}

func (w *Watcher) readFile(tf *trackedFile, budget *int64) ([]builds.Build, error) {
	if *budget < 64 {
		return nil, nil
	}
	f, info, err := openSink(tf.sink.Path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	remaining := min(*budget, int64(maxFilePollBytes))
	size := info.Size()
	prefix := make([]byte, min(size, 64))
	*budget -= int64(len(prefix))
	remaining -= int64(len(prefix))
	if _, err := f.ReadAt(prefix, 0); err != nil && !errors.Is(err, io.EOF) {
		return nil, err
	}
	replaced := tf.identity != nil && !os.SameFile(tf.identity, info)
	rewritten := len(prefix) < len(tf.prefix) || !bytes.Equal(prefix[:min(len(prefix), len(tf.prefix))], tf.prefix)
	if size < tf.offset || replaced || rewritten {
		// Bazel may truncate and rewrite the entire sink between polls,
		// so a size comparison alone can miss a new invocation.
		tf.offset = 0
		tf.pending = nil
		tf.state = partial{ambientEnvNames: w.ambientEnvNames}
	}
	tf.identity = info
	tf.prefix = prefix
	if _, err := f.Seek(tf.offset, io.SeekStart); err != nil {
		return nil, err
	}
	tf.state.ambientEnvNames = w.ambientEnvNames
	var closed []builds.Build
	var parseErr error
	for tf.offset < size && remaining > 0 {
		chunk := w.readBuf[:min(size-tf.offset, int64(len(w.readBuf)), remaining)]
		n, err := f.Read(chunk)
		*budget -= int64(n)
		remaining -= int64(n)
		if n == 0 {
			if err == nil {
				err = io.ErrNoProgress
			}
			applyClientAttribution(closed, tf.client)
			return closed, err
		}
		tf.offset += int64(n)
		var rows []builds.Build
		var leftover []byte
		if tf.sink.Format == FormatBinary {
			rows, leftover, parseErr = ConsumeBinaryBytes(&tf.state, tf.pending, chunk[:n])
		} else {
			rows, leftover, parseErr = ConsumeJSONBytes(&tf.state, tf.pending, chunk[:n])
		}
		tf.pending = leftover
		closed = append(closed, rows...)
		if parseErr != nil {
			applyClientAttribution(closed, tf.client)
			return closed, parseErr
		}
		if err != nil {
			applyClientAttribution(closed, tf.client)
			return closed, err
		}
	}
	applyClientAttribution(closed, tf.client)
	return closed, nil
}

func applyClientAttribution(closed []builds.Build, c clientObservation) {
	if c.Agent == nil || c.StartTimeMs <= 0 {
		return
	}
	for i := range closed {
		b := &closed[i]
		// Direct ancestry wins over the weaker env-name hint, including when
		// Daemonitor has the same ambient agent variables as the build client.
		if b.StartTimeMs >= c.StartTimeMs && b.StartTimeMs <= c.ObservedAtMs {
			b.Agent, b.AgentProvider = c.Agent.Agent, c.Agent.Provider
		}
	}
}

// DiscoverFromProcesses scrapes Bazel client argv for BEP file flags.
// On Unix, ownership must be established before reading argv or cwd.
func DiscoverFromProcesses(ctx context.Context) ([]Sink, error) {
	pids, err := process.PidsWithContext(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]Sink, 0)
	seen := make(map[string]struct{})
	for _, pid := range pids {
		select {
		case <-ctx.Done():
			return out, ctx.Err()
		default:
		}
		p, err := process.NewProcessWithContext(ctx, pid)
		if err != nil || !ownedProcess(ctx, p) {
			continue
		}
		cmdline, err := p.CmdlineWithContext(ctx)
		if err != nil || cmdline == "" {
			continue
		}
		if !LooksLikeBazelClient(cmdline) {
			continue
		}
		cwd, _ := p.CwdWithContext(ctx)
		for _, sink := range ParseSinksFromArgv(cmdline, cwd, pid) {
			if _, ok := seen[sink.Path]; ok {
				continue
			}
			seen[sink.Path] = struct{}{}
			out = append(out, sink)
		}
	}
	return out, nil
}

// WatchedCount returns how many sink paths are currently tracked (tests/metrics).
func (w *Watcher) WatchedCount() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return len(w.files)
}
