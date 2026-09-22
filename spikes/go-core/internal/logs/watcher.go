package logs

import (
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"sync"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/poll"
)

const (
	defaultTailLines       = 100
	defaultInitialReadBytes = 256 * 1024
	defaultReadChunkBytes   = 16 * 1024
)

var filenameRE = regexp.MustCompile(`^daemon-(\d+)\.out\.log$`)

// DaemonLog is a discovered Gradle daemon out log.
type DaemonLog struct {
	PID           int64  `json:"pid"`
	GradleVersion string `json:"gradle_version"`
	Path          string `json:"path"`
}

// Tail is the retained redacted lines for one log, plus parsed build events (U3).
type Tail struct {
	PID           int64    `json:"pid"`
	GradleVersion string   `json:"gradle_version"`
	Path          string   `json:"path"`
	Lines         []string `json:"lines"`
	Events        []Event  `json:"events"`
}

// Watcher discovers daemon logs and keeps a redacted live tail (Kotlin DaemonLogWatcher subset).
type Watcher struct {
	gradleUserHome string
	tailLines      int
	initialBytes   int
	chunkBytes     int

	mu        sync.Mutex
	offsets   map[string]int64
	leftovers map[string][]byte
	tails     map[string]*ring
	meta      map[string]DaemonLog
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
	}
}

// Discover scans <gradleUserHome>/daemon/<version>/daemon-<pid>.out.log.
func (w *Watcher) Discover() ([]DaemonLog, error) {
	root := filepath.Join(w.gradleUserHome, "daemon")
	entries, err := os.ReadDir(root)
	if err != nil {
		if os.IsNotExist(err) {
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
func (w *Watcher) Poll(activePIDs map[int64]struct{}) error {
	logs, err := w.Discover()
	if err != nil {
		return err
	}
	seen := make(map[string]struct{}, len(logs))
	for _, log := range logs {
		seen[log.Path] = struct{}{}
		w.mu.Lock()
		w.meta[log.Path] = log
		w.mu.Unlock()
		if activePIDs != nil {
			if _, ok := activePIDs[log.PID]; !ok {
				continue
			}
		}
		if _, err := w.readNewLines(log.Path); err != nil {
			continue
		}
	}
	w.mu.Lock()
	for path := range w.meta {
		if _, ok := seen[path]; !ok {
			delete(w.meta, path)
			delete(w.offsets, path)
			delete(w.leftovers, path)
			delete(w.tails, path)
		}
	}
	w.mu.Unlock()
	return nil
}

// List returns currently known daemon logs.
func (w *Watcher) List() []DaemonLog {
	w.mu.Lock()
	defer w.mu.Unlock()
	out := make([]DaemonLog, 0, len(w.meta))
	for _, log := range w.meta {
		out = append(out, log)
	}
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

// AllTails returns tails for every known log.
func (w *Watcher) AllTails() []Tail {
	w.mu.Lock()
	defer w.mu.Unlock()
	out := make([]Tail, 0, len(w.meta))
	for path, log := range w.meta {
		lines := []string{}
		if r := w.tails[path]; r != nil {
			lines = r.slice()
		}
		out = append(out, makeTail(log, lines))
	}
	return out
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
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	size := info.Size()

	w.mu.Lock()
	from, initialized := w.offsets[path]
	if !initialized {
		from = size - int64(w.initialBytes)
		if from < 0 {
			from = 0
		}
	}
	leftover := w.leftovers[path]
	delete(w.leftovers, path)
	w.mu.Unlock()

	if size < from {
		w.mu.Lock()
		w.offsets[path] = 0
		delete(w.leftovers, path)
		w.mu.Unlock()
		return nil, nil
	}
	if size == from {
		w.mu.Lock()
		w.offsets[path] = from
		w.mu.Unlock()
		return nil, nil
	}

	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	discardInitial := !initialized && from > 0 && !startsAtLineBoundary(f, from)
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
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}
		if n == 0 {
			break
		}
	}

	w.mu.Lock()
	w.offsets[path] = pos
	if len(partial) > 0 {
		w.leftovers[path] = append([]byte{}, partial...)
	}
	redacted := make([]string, 0, len(lines))
	for _, line := range lines {
		redacted = append(redacted, poll.RedactLogLine(line))
	}
	r := w.tails[path]
	if r == nil {
		r = newRing(w.tailLines)
		w.tails[path] = r
	}
	for _, line := range redacted {
		r.push(line)
	}
	w.mu.Unlock()
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
	max  int
	data []string
}

func newRing(max int) *ring { return &ring{max: max, data: make([]string, 0, max)} }

func (r *ring) push(line string) {
	if len(r.data) >= r.max {
		r.data = r.data[1:]
	}
	r.data = append(r.data, line)
}

func (r *ring) slice() []string {
	out := make([]string, len(r.data))
	copy(out, r.data)
	return out
}
