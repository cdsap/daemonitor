package logs

import (
	"crypto/sha256"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"sync"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/poll"
)

const (
	defaultTailLines        = 100
	defaultInitialReadBytes = 256 * 1024
	defaultReadChunkBytes   = 16 * 1024
)

var filenameRE = regexp.MustCompile(`^daemon-(\d+)\.out\.log$`)

// DaemonLog is a discovered build tool log. Source distinguishes logs for one PID.
type DaemonLog struct {
	PID           int64  `json:"pid"`
	GradleVersion string `json:"gradle_version"`
	Path          string `json:"path"`
	Tool          string `json:"tool,omitempty"`
	Source        string `json:"source,omitempty"`
	// Ordering metadata is captured by Poll and is not part of the wire format.
	active   bool
	modified time.Time
}

// Tail is the retained redacted lines for one log, plus parsed build events (U3).
type Tail struct {
	PID           int64    `json:"pid"`
	GradleVersion string   `json:"gradle_version"`
	Path          string   `json:"path"`
	Lines         []string `json:"lines"`
	Events        []Event  `json:"events"`
	Tool          string   `json:"tool,omitempty"`
	Source        string   `json:"source,omitempty"`
}

// Watcher discovers daemon logs and keeps a redacted live tail (Kotlin DaemonLogWatcher subset).
type Watcher struct {
	gradleUserHome string
	tailLines      int
	initialBytes   int
	chunkBytes     int

	mu          sync.Mutex
	offsets     map[string]int64
	leftovers   map[string][]byte
	tails       map[string]*ring
	meta        map[string]DaemonLog
	checkpoints map[string]logCheckpoint
}

func NewWatcher(gradleUserHome string) *Watcher {
	if gradleUserHome == "" {
		home, _ := os.UserHomeDir()
		gradleUserHome = filepath.Join(home, ".gradle")
	}
	return &Watcher{
		gradleUserHome: gradleUserHome,
		tailLines:      defaultTailLines,
		initialBytes:   defaultInitialReadBytes,
		chunkBytes:     defaultReadChunkBytes,
		offsets:        make(map[string]int64),
		leftovers:      make(map[string][]byte),
		tails:          make(map[string]*ring),
		meta:           make(map[string]DaemonLog),
		checkpoints:    make(map[string]logCheckpoint),
	}
}

// Discover scans <gradleUserHome>/daemon/<version>/daemon-<pid>.out.log.
func (w *Watcher) Discover() ([]DaemonLog, error) {
	root := filepath.Join(w.gradleUserHome, "daemon")
	entries, err := os.ReadDir(root)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	out := make([]DaemonLog, 0)
	for _, versionDir := range entries {
		if !versionDir.IsDir() {
			continue
		}
		versionPath := filepath.Join(root, versionDir.Name())
		files, err := os.ReadDir(versionPath)
		if err != nil {
			continue
		}
		for _, f := range files {
			if f.IsDir() {
				continue
			}
			if log, ok := ParseLogPath(filepath.Join(versionPath, f.Name())); ok {
				if info, err := f.Info(); err == nil {
					log.modified = info.ModTime()
				}
				out = append(out, log)
			}
		}
	}
	return out, nil
}

// ParseLogPath maps a daemon log path to PID + Gradle version.
func ParseLogPath(path string) (DaemonLog, bool) {
	base := filepath.Base(path)
	m := filenameRE.FindStringSubmatch(base)
	if m == nil {
		return DaemonLog{}, false
	}
	pid, err := strconv.ParseInt(m[1], 10, 64)
	if err != nil {
		return DaemonLog{}, false
	}
	version := filepath.Base(filepath.Dir(path))
	if version == "" || version == "." || version == "/" {
		return DaemonLog{}, false
	}
	return DaemonLog{PID: pid, GradleVersion: version, Path: path}, true
}

// Poll discovers logs and reads newly appended redacted lines into tails.
// When activePIDs is non-nil, only those PIDs are read (list/discover still covers all).
// Pass nil to read every discovered log (tests / full replay).
// The returned map contains newly read redacted lines keyed by daemon PID.
func (w *Watcher) Poll(activePIDs map[int64]struct{}) (map[int64][]string, error) {
	logs, err := w.Discover()
	if err != nil {
		return nil, err
	}
	seen := make(map[string]struct{}, len(logs))
	newByPID := make(map[int64][]string)
	for _, log := range logs {
		seen[log.Path] = struct{}{}
		_, log.active = activePIDs[log.PID]
		w.mu.Lock()
		w.meta[log.Path] = log
		w.mu.Unlock()
		if activePIDs != nil {
			if _, ok := activePIDs[log.PID]; !ok {
				continue
			}
		}
		lines, err := w.readNewLines(log.Path)
		if err != nil {
			continue
		}
		if len(lines) > 0 {
			newByPID[log.PID] = append(newByPID[log.PID], lines...)
		}
	}
	w.mu.Lock()
	for path := range w.meta {
		if _, ok := seen[path]; !ok {
			delete(w.meta, path)
			delete(w.offsets, path)
			delete(w.leftovers, path)
			delete(w.tails, path)
			delete(w.checkpoints, path)
		}
	}
	w.mu.Unlock()
	return newByPID, nil
}

// List returns known logs ordered by activity and recency from the last poll.
func (w *Watcher) List() []DaemonLog {
	w.mu.Lock()
	defer w.mu.Unlock()
	out := make([]DaemonLog, 0, len(w.meta))
	for _, log := range w.meta {
		out = append(out, log)
	}
	Sort(out)
	return out
}

// TailFor returns the retained redacted lines for a daemon PID (empty if unknown).
// Lazily seeds the tail when the log is known but has not been read yet (inactive PID).
func (w *Watcher) TailFor(pid int64) (Tail, bool) {
	w.mu.Lock()
	var path string
	var log DaemonLog
	found := false
	for p, meta := range w.meta {
		if meta.PID == pid {
			path = p
			log = meta
			found = true
			break
		}
	}
	needSeed := false
	if found {
		_, initialized := w.offsets[path]
		needSeed = !initialized
	}
	w.mu.Unlock()
	if !found {
		return Tail{}, false
	}
	if needSeed {
		_, _ = w.readNewLines(path)
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	lines := []string{}
	if r := w.tails[path]; r != nil {
		lines = r.slice()
	}
	return makeTail(log, lines), true
}

// DaemonIdentity returns the latest Gradle daemon uid observed in its log.
// The uid, together with the PID, identifies one daemon incarnation.
func (w *Watcher) DaemonIdentity(pid int64) string {
	tail, ok := w.TailFor(pid)
	if !ok {
		return ""
	}
	for i := len(tail.Events) - 1; i >= 0; i-- {
		if tail.Events[i].Kind == KindDaemonContext && tail.Events[i].UID != "" {
			return tail.Events[i].UID
		}
	}
	return ""
}

func makeTail(log DaemonLog, lines []string) Tail {
	return Tail{
		PID:           log.PID,
		GradleVersion: log.GradleVersion,
		Path:          log.Path,
		Lines:         lines,
		Events:        ParseLines(lines),
	}
}

func (w *Watcher) readNewLines(path string) ([]string, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, nil
	}
	size := info.Size()
	from, initialized := w.offsets[path]
	checkpoint := w.checkpoints[path]
	reset := initialized && (checkpoint.info == nil || !os.SameFile(checkpoint.info, info) || size < from)
	if initialized && !reset && from > 0 {
		previous := make([]byte, from-checkpoint.anchorStart)
		if _, err := f.ReadAt(previous, checkpoint.anchorStart); err != nil {
			return nil, err
		}
		reset = sha256.Sum256(previous) != checkpoint.anchor
	}
	leftover := w.leftovers[path]
	if reset {
		from = 0
		leftover = nil
	}
	if !initialized || reset {
		from = max(int64(0), size-int64(w.initialBytes))
	}
	if initialized && !reset && size == from {
		return nil, nil
	}
	discardInitial := (!initialized || reset) && from > 0 && !startsAtLineBoundary(f, from)
	if _, err := f.Seek(from, io.SeekStart); err != nil {
		return nil, err
	}

	partial := append([]byte{}, leftover...)
	var lines []string
	buf := make([]byte, w.chunkBytes)
	pos := from
	for pos < size {
		toRead := int64(len(buf))
		if remaining := size - pos; remaining < toRead {
			toRead = remaining
		}
		n, err := f.Read(buf[:toRead])
		if n > 0 {
			pos += int64(n)
			for i := 0; i < n; i++ {
				b := buf[i]
				if discardInitial {
					if b == '\n' {
						discardInitial = false
					}
					continue
				}
				if b == '\n' {
					lineBytes := partial
					if len(lineBytes) > 0 && lineBytes[len(lineBytes)-1] == '\r' {
						lineBytes = lineBytes[:len(lineBytes)-1]
					}
					if len(lineBytes) > 0 {
						lines = append(lines, string(lineBytes))
					}
					partial = partial[:0]
				} else {
					partial = append(partial, b)
				}
			}
		}
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, err
		}
		if n == 0 {
			break
		}
	}

	anchorStart := max(int64(0), pos-int64(w.initialBytes))
	anchor := make([]byte, pos-anchorStart)
	if _, err := f.ReadAt(anchor, anchorStart); err != nil {
		return nil, err
	}
	after, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if after.Size() != info.Size() || !after.ModTime().Equal(info.ModTime()) {
		return nil, io.ErrUnexpectedEOF
	}
	redacted := make([]string, 0, len(lines))
	for _, line := range lines {
		redacted = append(redacted, poll.RedactLogLine(line))
	}
	// Publish only after the read and its anchor belong to a stable file.
	w.checkpoints[path] = logCheckpoint{info: info, anchorStart: anchorStart, anchor: sha256.Sum256(anchor)}
	w.offsets[path] = pos
	delete(w.leftovers, path)
	if len(partial) > 0 {
		w.leftovers[path] = append([]byte{}, partial...)
	}
	r := w.tails[path]
	if r == nil || reset {
		r = newRing(w.tailLines)
		w.tails[path] = r
	}
	for _, line := range redacted {
		r.push(line)
	}
	return redacted, nil
}

func startsAtLineBoundary(f *os.File, from int64) bool {
	if from <= 0 {
		return true
	}
	var b [1]byte
	if _, err := f.ReadAt(b[:], from-1); err != nil {
		return false
	}
	return b[0] == '\n'
}

type ring struct {
	max   int
	head  int
	count int
	data  []string
}

func newRing(max int) *ring {
	if max < 0 {
		max = 0
	}
	return &ring{max: max, data: make([]string, max)}
}

func (r *ring) push(line string) {
	if r.max <= 0 {
		return
	}
	idx := (r.head + r.count) % r.max
	r.data[idx] = line
	if r.count < r.max {
		r.count++
	} else {
		r.head = (r.head + 1) % r.max
	}
}

func (r *ring) slice() []string {
	out := make([]string, r.count)
	if r.count == 0 {
		return out
	}
	first := min(r.count, r.max-r.head)
	copy(out, r.data[r.head:r.head+first])
	if r.count > first {
		copy(out[first:], r.data[:r.count-first])
	}
	return out
}

type logCheckpoint struct {
	info        os.FileInfo
	anchorStart int64
	anchor      [sha256.Size]byte
}
