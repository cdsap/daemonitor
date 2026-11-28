package bep

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestWatcherBoundsObservedPaths(t *testing.T) {
	w := NewWatcher()
	for i := range maxTrackedFiles + 10 {
		w.observeClient(clientObservation{Sink: Sink{Path: fmt.Sprintf("/events/%d", i), Format: FormatJSON}})
	}
	if got := w.WatchedCount(); got != maxTrackedFiles {
		t.Fatalf("tracked paths=%d", got)
	}
	if w.files["/events/0"] != nil {
		t.Fatal("oldest path retained")
	}
	if w.files[fmt.Sprintf("/events/%d", maxTrackedFiles+9)] == nil {
		t.Fatal("newest path evicted")
	}
}

func TestWatcherBoundsDeduplicationAndEvictsMissingFiles(t *testing.T) {
	path := filepath.Join(t.TempDir(), "events.json")
	var body strings.Builder
	for i := range maxSeenBuilds + 2 {
		fmt.Fprintf(&body, "{\"started\":{\"uuid\":\"bounded-%d\",\"startTimeMillis\":\"100\"}}\n{\"finished\":{\"finishTimeMillis\":\"200\"}}\n", i)
	}
	if err := os.WriteFile(path, []byte(body.String()), 0600); err != nil {
		t.Fatal(err)
	}
	w := NewWatcher()
	w.observeClient(clientObservation{Sink: Sink{Path: path, Format: FormatJSON}})
	count := 0
	for w.files[path].offset < int64(body.Len()) {
		rows, err := w.Poll(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		count += len(rows)
	}
	if count != maxSeenBuilds+2 || len(w.seenRank) != maxSeenBuilds {
		t.Fatalf("count=%d retained=%d", count, len(w.seenRank))
	}
	if _, ok := w.seenRank["bounded-0"]; ok {
		t.Fatal("oldest dedup entry retained")
	}
	if _, ok := w.seenRank[fmt.Sprintf("bounded-%d", maxSeenBuilds+1)]; !ok {
		t.Fatal("newest dedup entry evicted")
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if _, err := w.Poll(t.Context()); err != nil {
		t.Fatal(err)
	}
	if w.WatchedCount() != 0 {
		t.Fatal("missing file retained")
	}
}

func TestWatcherExpiresUnobservedPaths(t *testing.T) {
	w := NewWatcher()
	w.trackSink(Sink{Path: "/old", Format: FormatJSON}, time.Now().Add(-25*time.Hour))
	if _, err := w.Poll(t.Context()); err != nil {
		t.Fatal(err)
	}
	if w.WatchedCount() != 0 {
		t.Fatal("stale path retained")
	}
}

func TestWatcherConcurrentObservationAndPoll(t *testing.T) {
	path := filepath.Join(t.TempDir(), "events.json")
	body := []byte("{\"started\":{\"uuid\":\"concurrent\",\"startTimeMillis\":\"100\"}}\n{\"finished\":{\"finishTimeMillis\":\"200\"}}\n")
	if err := os.WriteFile(path, body, 0600); err != nil {
		t.Fatal(err)
	}
	w := NewWatcher()
	c := clientObservation{Sink: Sink{Path: path, Format: FormatJSON}}
	w.observeClient(c)
	var wg sync.WaitGroup
	var count atomic.Int32
	for range 4 {
		wg.Go(func() {
			for range 20 {
				w.observeClient(c)
				_ = w.WatchedCount()
			}
		})
		wg.Go(func() {
			for range 20 {
				rows, err := w.Poll(t.Context())
				if err != nil {
					t.Error(err)
				}
				count.Add(int32(len(rows)))
			}
		})
	}
	wg.Wait()
	if count.Load() != 1 {
		t.Fatalf("completed builds=%d", count.Load())
	}
}
