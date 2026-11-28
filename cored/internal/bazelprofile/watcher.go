package bazelprofile

import (
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

type fingerprint struct{ profile, log os.FileInfo }

func sameFile(a, b os.FileInfo) bool {
	return a == nil && b == nil || a != nil && b != nil && os.SameFile(a, b) && a.Size() == b.Size() && a.ModTime() == b.ModTime()
}
func (a fingerprint) equal(b fingerprint) bool {
	return sameFile(a.profile, b.profile) && sameFile(a.log, b.log)
}
func stat(path string) os.FileInfo {
	i, err := os.Stat(path)
	if err != nil || !i.Mode().IsRegular() {
		return nil
	}
	return i
}
func signature(path string) fingerprint {
	return fingerprint{stat(path), stat(filepath.Join(filepath.Dir(path), "command.log"))}
}

type tracked struct{ observed, attempted fingerprint }

// Watcher is owned by the service refresh loop. At most one bounded parse runs
// asynchronously; Poll never waits for decompression or JSON processing.
type Watcher struct {
	files   map[string]tracked
	pending chan builds.Build
}

func NewWatcher() *Watcher { return &Watcher{files: map[string]tracked{}} }

func (w *Watcher) Poll(ctx context.Context, processes []model.Process) []builds.Build {
	var out []builds.Build
	if w.pending != nil {
		select {
		case r := <-w.pending:
			w.pending = nil
			if r.BuildID != "" {
				out = append(out, r)
			}
		default:
		}
	}
	active := map[string]bool{}
	for _, p := range processes {
		if ctx.Err() != nil {
			break
		}
		if p.Type != "BAZEL_SERVER" {
			continue
		}
		base := p.BazelOutputBase
		if !filepath.IsAbs(base) {
			continue
		}
		path := filepath.Join(base, "command.profile.gz")
		active[path] = true
		sig := signature(path)
		old := w.files[path]
		w.files[path] = tracked{observed: sig, attempted: old.attempted}
		if sig.profile == nil || !sig.equal(old.observed) || sig.equal(old.attempted) || w.pending != nil {
			continue
		}
		w.files[path] = tracked{observed: sig, attempted: sig}
		ch := make(chan builds.Build, 1)
		w.pending = ch
		go func(p model.Process, path string, sig fingerprint) {
			b, err := readProfile(path)
			// The first build can launch the server after the trace begins.
			if err == nil && b.EndTimeMs != nil && *b.EndTimeMs >= p.StartTimeMs {
				b.DaemonPID = int64(p.PID)
				if p.ProjectPath != nil {
					b.ProjectPath = *p.ProjectPath
				}
				if p.WorkingDirectory != nil {
					b.WorkingDirectory = *p.WorkingDirectory
				}
				if sig.log != nil {
					b.FinalStatus = completionStatus(filepath.Join(filepath.Dir(path), "command.log"), b.BuildID)
				}
				if sig.equal(signature(path)) {
					ch <- b
					return
				}
			}
			ch <- builds.Build{}
		}(p, path, sig)
	}
	for path := range w.files {
		if !active[path] {
			delete(w.files, path)
		}
	}
	return out
}

// Completion lines are trustworthy only when command.log identifies this exact
// invocation. An old log or a new command must never supply a fabricated result.
func completionStatus(path, id string) builds.FinalStatus {
	f, err := os.Open(path)
	if err != nil {
		return builds.StatusCompletedNoOutcome
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() > 1024*1024 {
		return builds.StatusCompletedNoOutcome
	}
	data, err := io.ReadAll(io.LimitReader(f, 1024*1024+1))
	if err != nil || len(data) > 1024*1024 {
		return builds.StatusCompletedNoOutcome
	}
	matched := false
	status := builds.StatusCompletedNoOutcome
	for _, line := range strings.Split(string(data), "\n") {
		if at := strings.Index(line, "Invocation ID: "); at >= 0 {
			matched = strings.TrimSpace(line[at+len("Invocation ID: "):]) == id
			status = builds.StatusCompletedNoOutcome
			continue
		}
		if !matched {
			continue
		}
		if strings.HasPrefix(line, "INFO: Build completed successfully") {
			status = builds.StatusSuccess
		}
		if strings.Contains(line, "Build did NOT complete successfully") || strings.Contains(line, "Build completed, ") && strings.Contains(line, "FAILED") {
			status = builds.StatusFailed
		}
	}
	return status
}
