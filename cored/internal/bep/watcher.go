package bep

import (
	"bytes"
	"context"
	"io"
	"os"
	"strings"
	"sync"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/shirou/gopsutil/v4/process"
)

// Watcher discovers opportunistic BEP sinks and closes builds from JSON or binary files.
type Watcher struct {
	mu              sync.Mutex
	files           map[string]*trackedFile
	seenRank        map[string]int
	ambientEnvNames map[string]struct{}
	// Optional extra absolute paths / globs resolved by the caller each poll.
	ExtraPaths func() []Sink
}

type trackedFile struct {
	sink     Sink
	offset   int64
	pending  []byte
	state    partial
	identity os.FileInfo
	prefix   []byte
	client   clientObservation
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
	tf := w.files[client.Sink.Path]
	if tf == nil {
		tf = &trackedFile{sink: client.Sink}
		w.files[client.Sink.Path] = tf
	}
	if tf.client.Sink.ClientPID == client.Sink.ClientPID && tf.client.StartTimeMs == client.StartTimeMs && client.Agent == nil {
		client.Agent = tf.client.Agent
	}
	tf.client = client
}

// Poll discovers sinks from live processes and reads newly appended bytes.
func (w *Watcher) Poll(ctx context.Context) ([]builds.Build, error) {
	sinks, err := DiscoverFromProcesses(ctx)
	if err != nil {
		return nil, err
	}
	if w.ExtraPaths != nil {
		sinks = append(sinks, w.ExtraPaths()...)
	}

	w.mu.Lock()
	defer w.mu.Unlock()

	for _, sink := range sinks {
		if sink.Path == "" {
			continue
		}
		if _, ok := w.files[sink.Path]; !ok {
			w.files[sink.Path] = &trackedFile{sink: sink}
		} else {
			w.files[sink.Path].sink = sink
		}
	}

	var closed []builds.Build
	for _, tf := range w.files {
		if tf.sink.Format != FormatJSON && tf.sink.Format != FormatBinary {
			continue
		}
		// A malformed event must not discard builds already decoded in this
		// poll. File/read errors are opportunistic and retried next tick.
		buildsClosed, _ := w.readFile(tf)
		for _, b := range buildsClosed {
			if b.BuildID == "" {
				continue
			}
			rank := buildDetailRank(b)
			if prev, seen := w.seenRank[b.BuildID]; seen && rank <= prev {
				continue
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

func (w *Watcher) readFile(tf *trackedFile) ([]builds.Build, error) {
	f, err := os.Open(tf.sink.Path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	size := info.Size()
	prefix := make([]byte, min(size, 64))
	if _, err := f.ReadAt(prefix, 0); err != nil && err != io.EOF {
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
	if _, err := f.Seek(tf.offset, 0); err != nil {
		return nil, err
	}
	tf.state.ambientEnvNames = w.ambientEnvNames
	var closed []builds.Build
	var parseErr error
	for tf.offset < size {
		chunk := make([]byte, min(size-tf.offset, 64*1024))
		n, err := f.Read(chunk)
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

// DiscoverFromProcesses scrapes live process argv for BEP file flags.
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
		if err != nil {
			continue
		}
		cmdline, err := p.CmdlineWithContext(ctx)
		if err != nil || cmdline == "" {
			continue
		}
		if !LooksLikeBazelClient(cmdline) && !hasBEPFlag(cmdline) {
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
