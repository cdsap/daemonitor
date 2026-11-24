package bep

import (
	"bytes"
	"context"
	"io"
	"os"
	"sync"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/shirou/gopsutil/v4/process"
)

// Watcher discovers opportunistic BEP sinks and closes builds from JSON or binary files.
type Watcher struct {
	mu      sync.Mutex
	files   map[string]*trackedFile
	seenIDs map[string]struct{}
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
}

func NewWatcher() *Watcher {
	return &Watcher{
		files:   make(map[string]*trackedFile),
		seenIDs: make(map[string]struct{}),
	}
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
			if _, seen := w.seenIDs[b.BuildID]; seen {
				continue
			}
			w.seenIDs[b.BuildID] = struct{}{}
			closed = append(closed, b)
		}
	}
	return closed, nil
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
		tf.state = partial{}
	}
	tf.identity = info
	tf.prefix = prefix
	if _, err := f.Seek(tf.offset, 0); err != nil {
		return nil, err
	}
	var closed []builds.Build
	var parseErr error
	for tf.offset < size {
		chunk := make([]byte, min(size-tf.offset, 64*1024))
		n, err := f.Read(chunk)
		if n == 0 {
			if err == nil {
				err = io.ErrNoProgress
			}
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
			return closed, parseErr
		}
		if err != nil {
			return closed, err
		}
	}
	return closed, nil
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
