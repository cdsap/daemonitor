package logs

import (
	"bytes"
	"crypto/sha256"
	"io"
	"os"
	"path/filepath"
	"sync"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/poll"
)

type bazelLogKey struct {
	pid    int64
	source string
}

type bazelLog struct {
	meta      DaemonLog
	startTime int64
	info      os.FileInfo
	offset    int64
	// A digest detects truncate-and-regrow between polls, even at the same size.
	anchorStart int64
	anchor      [sha256.Size]byte
	discarding  bool
	tail        *ring
}

// BazelWatcher reads only command.log and server/jvm.out for observed servers.
// Reads and retained lines are bounded; incomplete raw lines are never retained.
// It neither invokes Bazel nor opens the output_base lock.
type BazelWatcher struct {
	mu    sync.Mutex
	files map[bazelLogKey]*bazelLog
}

func NewBazelWatcher() *BazelWatcher { return &BazelWatcher{files: make(map[bazelLogKey]*bazelLog)} }

func (w *BazelWatcher) Poll(processes []model.Process) {
	w.mu.Lock()
	defer w.mu.Unlock()
	seen := make(map[bazelLogKey]bool)
	for _, p := range processes {
		if p.Type != "BAZEL_SERVER" || !filepath.IsAbs(p.BazelOutputBase) {
			continue
		}
		for _, source := range []string{"command.log", "server/jvm.out"} {
			key := bazelLogKey{int64(p.PID), source}
			path := filepath.Join(p.BazelOutputBase, filepath.FromSlash(source))
			info, err := os.Stat(path)
			if err != nil || !info.Mode().IsRegular() {
				continue
			}
			log := w.files[key]
			if log == nil || log.meta.Path != path || log.startTime != p.StartTimeMs {
				log = &bazelLog{meta: DaemonLog{PID: key.pid, Path: path, Tool: "bazel", Source: source}, startTime: p.StartTimeMs, tail: newRing(defaultTailLines)}
				w.files[key] = log
			}
			seen[key] = true
			log.meta.active = true
			log.meta.modified = info.ModTime()
			// On read errors retain only previously redacted lines and retry next poll.
			_ = log.read()
		}
	}
	for key := range w.files {
		if !seen[key] {
			delete(w.files, key)
		}
	}
}

func (w *BazelWatcher) List() []DaemonLog {
	w.mu.Lock()
	defer w.mu.Unlock()
	out := make([]DaemonLog, 0, len(w.files))
	for _, log := range w.files {
		out = append(out, log.meta)
	}
	Sort(out)
	return out
}

// TailFor defaults to command.log. If it is absent, the server log is used.
// An explicit source selects exactly that file; arbitrary paths are not read.
func (w *BazelWatcher) TailFor(pid int64, source string) (Tail, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if source == "" {
		source = "command.log"
		if w.files[bazelLogKey{pid, source}] == nil {
			source = "server/jvm.out"
		}
	}
	log := w.files[bazelLogKey{pid, source}]
	if log == nil {
		return Tail{}, false
	}
	return Tail{PID: pid, Path: log.meta.Path, Tool: "bazel", Source: source, Lines: log.tail.slice(), Events: []Event{}}, true
}

func (l *bazelLog) read() error {
	f, err := os.Open(l.meta.Path)
	if err != nil {
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return nil
	}
	reset := l.info == nil || !os.SameFile(l.info, info) || info.Size() < l.offset
	if !reset && l.offset > 0 {
		previous := make([]byte, l.offset-l.anchorStart)
		if _, err := f.ReadAt(previous, l.anchorStart); err != nil {
			return err
		}
		reset = sha256.Sum256(previous) != l.anchor
	}
	from := l.offset
	discard := l.discarding
	if reset {
		from = 0
		discard = false
	}
	// Seek to a bounded recent window on initial discovery or large growth.
	if info.Size()-from > defaultInitialReadBytes {
		from = info.Size() - defaultInitialReadBytes
		discard = !startsAtLineBoundary(f, from)
	}
	buf := make([]byte, info.Size()-from)
	n, err := f.ReadAt(buf, from)
	if err != nil && err != io.EOF {
		return err
	}
	buf = buf[:n]
	nextTail := newRing(defaultTailLines)
	if !reset {
		for _, line := range l.tail.slice() {
			nextTail.push(line)
		}
	}
	consumed := 0
	for len(buf) > 0 {
		end := bytes.IndexByte(buf, '\n')
		if end < 0 {
			// Oversized lines are dropped in full, never exposed as a suffix that
			// could lose the flag prefix needed for redaction.
			if discard || len(buf) > defaultReadChunkBytes {
				consumed += len(buf)
				discard = true
			}
			break
		}
		if !discard && end <= defaultReadChunkBytes {
			line := bytes.TrimSuffix(buf[:end], []byte{'\r'})
			if len(line) > 0 {
				nextTail.push(poll.RedactLogLine(string(line)))
			}
		}
		discard = false
		consumed += end + 1
		buf = buf[end+1:]
	}
	offset := from + int64(consumed)
	anchorStart := offset - int64(defaultInitialReadBytes)
	if anchorStart < 0 {
		anchorStart = 0
	}
	anchorBytes := make([]byte, offset-anchorStart)
	if _, err := f.ReadAt(anchorBytes, anchorStart); err != nil {
		return err
	}
	// Publish the new offset and tail together, only after a stable read.
	// A concurrent append or rewrite is retried on the next poll.
	after, err := f.Stat()
	if err != nil {
		return err
	}
	if after.Size() != info.Size() || !after.ModTime().Equal(info.ModTime()) {
		return io.ErrUnexpectedEOF
	}
	l.tail = nextTail
	l.offset = offset
	l.anchorStart = anchorStart
	l.anchor = sha256.Sum256(anchorBytes)
	l.discarding = discard
	l.info = info
	return nil
}
