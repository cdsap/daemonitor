package bep

import (
	"bytes"
	"cmp"
	"context"
	"errors"
	"io"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/shirou/gopsutil/v4/process"
)

const (
	maxFileBytes = 32 * 1024 * 1024
	// Poll budgets include prefix checks; larger streams resume next poll.
	maxFilePollBytes    = 256 * 1024
	maxPollBytes        = 1024 * 1024
	maxTrackedFiles     = 256
	maxSeenRank         = 2048
	staleTrackedFileTTL = 5 * time.Minute
)

type seenEntry struct {
	rank int
	seq  uint64
}

// Watcher discovers opportunistic BEP sinks and closes builds from JSON or binary files.
type Watcher struct {
	pollMu          sync.Mutex
	mu              sync.Mutex
	files           map[string]*trackedFile
	seenRank        map[string]seenEntry
	seenSeq         uint64
	ambientEnvNames map[string]struct{}
	now             func() time.Time
	// SkipProcessScan disables DiscoverFromProcesses in Poll when the caller
	// already feeds live Bazel clients via ObserveProcess during Pass 0.
	SkipProcessScan bool
	// Optional extra absolute paths / globs resolved by the caller each poll.
	ExtraPaths func() []Sink
}

type trackedFile struct {
	sink       Sink
	offset     int64
	pending    []byte
	state      partial
	identity   os.FileInfo
	prefix     []byte
	client     clientObservation
	lastActive time.Time
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
		seenRank:        make(map[string]seenEntry),
		ambientEnvNames: ambient,
	}
}

func (w *Watcher) currentTime() time.Time {
	if w.now != nil {
		return w.now()
	}
	return time.Now()
}

// observeClient is called during process Pass 0, before slower JVM probes and
// before short-lived clients disappear. Evidence survives until BEP is read.
func (w *Watcher) observeClient(client clientObservation) {
	if client.Sink.Path == "" {
		return
	}
	now := w.currentTime()
	w.mu.Lock()
	defer w.mu.Unlock()
	tf := w.files[client.Sink.Path]
	if tf == nil {
		tf = &trackedFile{sink: client.Sink}
		w.files[client.Sink.Path] = tf
	}
	if tf.client.Sink.ClientPID == client.Sink.ClientPID && tf.client.StartTimeMs == client.StartTimeMs && client.Agent == nil {
		client.Agent = tf.client.Agent
	}
	tf.client = client
	tf.lastActive = now
	if len(w.files) > maxTrackedFiles {
		w.evictFilesLocked()
	}
}

// Poll discovers sinks from live processes (unless SkipProcessScan is set) and reads newly appended bytes.
func (w *Watcher) Poll(ctx context.Context) ([]builds.Build, error) {
	w.pollMu.Lock()
	defer w.pollMu.Unlock()

	var sinks []Sink
	if !w.SkipProcessScan {
		discovered, err := DiscoverFromProcesses(ctx)
		if err != nil {
			return nil, err
		}
		sinks = discovered
	}
	if w.ExtraPaths != nil {
		sinks = append(sinks, w.ExtraPaths()...)
	}

	now := w.currentTime()
	w.mu.Lock()
	activePaths := make(map[string]struct{}, len(sinks))
	for _, sink := range sinks {
		if sink.Path == "" {
			continue
		}
		activePaths[sink.Path] = struct{}{}
		if tf, ok := w.files[sink.Path]; !ok {
			w.files[sink.Path] = &trackedFile{sink: sink, lastActive: now}
		} else {
			tf.sink = sink
			tf.lastActive = now
		}
	}
	if len(w.files) > maxTrackedFiles {
		w.evictFilesLocked()
	}
	targets := make([]*trackedFile, 0, len(w.files))
	for _, tf := range w.files {
		if tf.lastActive.IsZero() {
			tf.lastActive = now
		}
		if tf.sink.Format != FormatJSON && tf.sink.Format != FormatBinary {
			continue
		}
		targets = append(targets, tf)
	}
	w.mu.Unlock()

	var rawClosed []builds.Build
	var missing []*trackedFile
	budget := int64(maxPollBytes)
	for _, tf := range targets {
		if budget < 64 || ctx.Err() != nil {
			break
		}
		// A malformed event must not discard builds already decoded in this
		// poll. File/read errors are opportunistic and retried next tick.
		buildsClosed, readErr := w.readFile(tf, &budget)
		if errors.Is(readErr, os.ErrNotExist) {
			missing = append(missing, tf)
		}
		rawClosed = append(rawClosed, buildsClosed...)
	}

	var closed []builds.Build
	w.mu.Lock()
	for _, b := range rawClosed {
		if b.BuildID == "" {
			continue
		}
		rank := buildDetailRank(b)
		if prev, seen := w.seenRank[b.BuildID]; seen && rank <= prev.rank {
			continue
		}
		w.seenSeq++
		w.seenRank[b.BuildID] = seenEntry{rank: rank, seq: w.seenSeq}
		closed = coalesceBuilds(closed, b)
	}
	if len(w.seenRank) > maxSeenRank {
		w.evictSeenRankLocked()
	}
	for _, tf := range missing {
		path := tf.sink.Path
		if _, active := activePaths[path]; active {
			continue
		}
		if cur, ok := w.files[path]; ok && cur == tf && now.Sub(tf.lastActive) > staleTrackedFileTTL {
			delete(w.files, path)
		}
	}
	w.mu.Unlock()
	return closed, nil
}

func (w *Watcher) evictFilesLocked() {
	type fileAge struct {
		path       string
		lastActive time.Time
	}
	ages := make([]fileAge, 0, len(w.files))
	for path, tf := range w.files {
		ages = append(ages, fileAge{path: path, lastActive: tf.lastActive})
	}
	slices.SortFunc(ages, func(a, b fileAge) int {
		if c := a.lastActive.Compare(b.lastActive); c != 0 {
			return c
		}
		return cmp.Compare(a.path, b.path)
	})
	excess := len(w.files) - maxTrackedFiles
	for i := 0; i < excess && i < len(ages); i++ {
		delete(w.files, ages[i].path)
	}
}

func (w *Watcher) evictSeenRankLocked() {
	seqs := make([]uint64, 0, len(w.seenRank))
	for _, entry := range w.seenRank {
		seqs = append(seqs, entry.seq)
	}
	slices.Sort(seqs)
	keep := maxSeenRank / 2
	if keep >= len(seqs) {
		return
	}
	cutoff := seqs[len(seqs)-keep]
	for id, entry := range w.seenRank {
		if entry.seq < cutoff {
			delete(w.seenRank, id)
		}
	}
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

func (w *Watcher) sinkFor(tf *trackedFile) Sink {
	w.mu.Lock()
	defer w.mu.Unlock()
	return tf.sink
}

func (w *Watcher) clientFor(tf *trackedFile) clientObservation {
	w.mu.Lock()
	defer w.mu.Unlock()
	return tf.client
}

func (w *Watcher) touchTracked(tf *trackedFile) {
	now := w.currentTime()
	w.mu.Lock()
	tf.lastActive = now
	w.mu.Unlock()
}

func (w *Watcher) readFile(tf *trackedFile, budget *int64) ([]builds.Build, error) {
	if *budget < 64 {
		return nil, nil
	}
	sink := w.sinkFor(tf)
	f, info, err := openSink(sink.Path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	w.touchTracked(tf)
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
	var buf []byte
	if tf.offset < size && remaining > 0 {
		buf = make([]byte, min(size-tf.offset, 64*1024, remaining))
	}
	for tf.offset < size && remaining > 0 {
		toRead := min(size-tf.offset, int64(len(buf)), remaining)
		n, err := f.Read(buf[:toRead])
		*budget -= int64(n)
		remaining -= int64(n)
		if n == 0 {
			if err == nil {
				err = io.ErrNoProgress
			}
			applyClientAttribution(closed, w.clientFor(tf))
			return closed, err
		}
		tf.offset += int64(n)
		var rows []builds.Build
		var leftover []byte
		if sink.Format == FormatBinary {
			rows, leftover, parseErr = ConsumeBinaryBytes(&tf.state, tf.pending, buf[:n])
		} else {
			rows, leftover, parseErr = ConsumeJSONBytes(&tf.state, tf.pending, buf[:n])
		}
		tf.pending = leftover
		closed = append(closed, rows...)
		if parseErr != nil {
			applyClientAttribution(closed, w.clientFor(tf))
			return closed, parseErr
		}
		if err != nil {
			applyClientAttribution(closed, w.clientFor(tf))
			return closed, err
		}
	}
	applyClientAttribution(closed, w.clientFor(tf))
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
