package bep

import (
	"context"
	"os"
	"sync"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/shirou/gopsutil/v4/process"
)

// Watcher discovers opportunistic BEP sinks and closes builds from JSON files.
type Watcher struct {
	mu      sync.Mutex
	files   map[string]*trackedFile
	seenIDs map[string]struct{}
	// Optional extra absolute paths / globs resolved by the caller each poll.
	ExtraPaths func() []Sink
}

type trackedFile struct {
	sink    Sink
	offset  int64
	pending []byte
	state   partial
}

func NewWatcher() *Watcher {
	return &Watcher{
		files:   make(map[string]*trackedFile),
		seenIDs: make(map[string]struct{}),
	}
}

// Poll discovers sinks from live processes and reads newly appended bytes.
// Binary sinks are registered but skipped until a binary parser is wired (H2b).
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
	for path, tf := range w.files {
		if tf.sink.Format != FormatJSON {
			continue
		}
		buildsClosed, err := w.readJSON(tf)
		if err != nil {
			// Missing file is normal before Bazel creates it; keep tracking.
			if os.IsNotExist(err) {
				continue
			}
			continue
		}
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
		_ = path
	}
	return closed, nil
}

func (w *Watcher) readJSON(tf *trackedFile) ([]builds.Build, error) {
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
	if size < tf.offset {
		// Rotated / truncated — restart.
		tf.offset = 0
		tf.pending = nil
		tf.state = partial{}
	}
	if size == tf.offset {
		return nil, nil
	}
	if _, err := f.Seek(tf.offset, 0); err != nil {
		return nil, err
	}
	chunk := make([]byte, size-tf.offset)
	n, err := f.Read(chunk)
	if err != nil && n == 0 {
		return nil, err
	}
	chunk = chunk[:n]
	tf.offset += int64(n)

	closed, leftover, err := ConsumeJSONBytes(&tf.state, tf.pending, chunk)
	tf.pending = leftover
	return closed, err
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
