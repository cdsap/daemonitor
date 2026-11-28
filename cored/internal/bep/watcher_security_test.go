package bep

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestWatcherRejectsUnsafeFiles(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := f.Truncate(32*1024*1024 + 1); err != nil {
		t.Fatal(err)
	}
	f.Close()
	for _, path := range []string{dir, path} {
		tf := &trackedFile{sink: Sink{Path: path, Format: FormatJSON}}
		budget := int64(maxPollBytes)
		if _, err := NewWatcher().readFile(tf, &budget); err == nil || tf.offset != 0 {
			t.Errorf("unsafe path %q: err=%v offset=%d", path, err, tf.offset)
		}
	}
	// The exact cap remains eligible; a prefix-only budget avoids reading the
	// sparse body while testing the inclusive boundary.
	if err := os.Truncate(path, 32*1024*1024); err != nil {
		t.Fatal(err)
	}
	tf := &trackedFile{sink: Sink{Path: path, Format: FormatJSON}}
	budget := int64(64)
	if _, err := NewWatcher().readFile(tf, &budget); err != nil || tf.identity == nil || budget != 0 {
		t.Fatalf("file at size limit: err=%v identity=%v budget=%d", err, tf.identity, budget)
	}
}

func TestWatcherResumesBoundedReads(t *testing.T) {
	for _, format := range []Format{FormatJSON, FormatBinary} {
		t.Run(string(format), func(t *testing.T) {
			var body []byte
			if format == FormatJSON {
				// The event itself crosses the per-file poll boundary.
				body = []byte(`{"started":{"uuid":"bounded","startTimeMillis":"1000"},"padding":"`)
				body = append(body, bytes.Repeat([]byte("x"), maxFilePollBytes)...)
				body = append(body, []byte("\"}\n{\"finished\":{\"finishTimeMillis\":\"2500\"}}\n")...)
			} else {
				body = binaryStart("bounded")
				body = append(body, wireFrame(wireBytes(100, bytes.Repeat([]byte("x"), maxFilePollBytes)))...)
				body = append(body, binaryFinish(0)...)
			}
			path := filepath.Join(t.TempDir(), "events")
			if err := os.WriteFile(path, body, 0600); err != nil {
				t.Fatal(err)
			}
			w := NewWatcher()
			tf := &trackedFile{sink: Sink{Path: path, Format: format}}
			budget := int64(maxPollBytes)
			got, err := w.readFile(tf, &budget)
			if err != nil || len(got) != 0 || tf.offset <= 0 || tf.offset >= int64(len(body)) || maxPollBytes-budget > maxFilePollBytes {
				t.Fatalf("first read: rows=%v err=%v offset=%d budget=%d", got, err, tf.offset, budget)
			}
			budget = maxPollBytes
			got, err = w.readFile(tf, &budget)
			if err != nil || len(got) != 1 || got[0].BuildID != "bounded" || tf.offset != int64(len(body)) || len(tf.pending) != 0 {
				t.Fatalf("resumed read: rows=%v err=%v offset=%d pending=%d", got, err, tf.offset, len(tf.pending))
			}
		})
	}
}

func TestWatcherPollHasSharedBudget(t *testing.T) {
	w := NewWatcher()
	dir := t.TempDir()
	for i := 0; i < 8; i++ {
		path := filepath.Join(dir, fmt.Sprint(i))
		if err := os.WriteFile(path, bytes.Repeat([]byte("\n"), maxFilePollBytes), 0600); err != nil {
			t.Fatal(err)
		}
		w.files[path] = &trackedFile{sink: Sink{Path: path, Format: FormatJSON}}
	}
	if _, err := w.Poll(t.Context()); err != nil {
		t.Fatal(err)
	}
	var consumed int64
	for _, tf := range w.files {
		consumed += tf.offset
	}
	if consumed <= 0 || consumed > maxPollBytes {
		t.Fatalf("poll consumed %d bytes", consumed)
	}
}

func TestWatcherBoundsTrackedFilesAndSeenRank(t *testing.T) {
	w := NewWatcher()
	w.SkipProcessScan = true
	base := time.Unix(1700000000, 0)
	cur := base
	w.now = func() time.Time { return cur }

	for i := 0; i < maxTrackedFiles+40; i++ {
		cur = cur.Add(time.Second)
		p := filepath.Join(t.TempDir(), fmt.Sprintf("bep-%d.json", i))
		w.observeClient(clientObservation{Sink: Sink{Path: p, Format: FormatJSON}})
	}
	if got := w.WatchedCount(); got > maxTrackedFiles {
		t.Fatalf("WatchedCount=%d want <= %d", got, maxTrackedFiles)
	}

	// Evict stale missing files after TTL expires.
	cur = cur.Add(staleTrackedFileTTL + time.Second)
	if _, err := w.Poll(t.Context()); err != nil {
		t.Fatal(err)
	}
	if got := w.WatchedCount(); got != 0 {
		t.Fatalf("stale missing files not evicted: WatchedCount=%d", got)
	}

	// Verify seenRank eviction keeps the map bounded.
	w.mu.Lock()
	for i := 0; i < maxSeenRank+50; i++ {
		w.seenSeq++
		w.seenRank[fmt.Sprintf("b-%d", i)] = seenEntry{rank: 2, seq: w.seenSeq}
	}
	w.evictSeenRankLocked()
	gotRank := len(w.seenRank)
	w.mu.Unlock()
	if gotRank > maxSeenRank {
		t.Fatalf("seenRank=%d want <= %d", gotRank, maxSeenRank)
	}
}

func TestWatcherSkipProcessScanAndConcurrentObserve(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bep.json")
	body := `{"started":{"uuid":"conc-1","startTimeMillis":"100","serverPid":"7"}}` + "\n" +
		`{"finished":{"finishTimeMillis":"200","exitCode":{"name":"SUCCESS"}}}` + "\n"
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	w := NewWatcher()
	w.SkipProcessScan = true
	sink := Sink{Path: path, Format: FormatJSON, ClientPID: 12}
	w.observeClient(clientObservation{Sink: sink, StartTimeMs: 90, ObservedAtMs: 250})

	var wg sync.WaitGroup
	wg.Add(3)
	go func() {
		defer wg.Done()
		for i := 0; i < 30; i++ {
			_, _ = w.Poll(t.Context())
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 30; i++ {
			w.observeClient(clientObservation{Sink: sink, StartTimeMs: 90, ObservedAtMs: 250})
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 30; i++ {
			_ = w.WatchedCount()
		}
	}()
	wg.Wait()
}
