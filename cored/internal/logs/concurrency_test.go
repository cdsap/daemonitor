package logs

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestConcurrentPollAndTailPublication(t *testing.T) {
	home := t.TempDir()
	dir := filepath.Join(home, "daemon", "9.0")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "daemon-42.out.log")
	body := []byte(strings.Repeat("line --token=private\n", 200))
	if err := os.WriteFile(path, body, 0600); err != nil {
		t.Fatal(err)
	}
	base := t.TempDir()
	if err := os.WriteFile(filepath.Join(base, "command.log"), body, 0600); err != nil {
		t.Fatal(err)
	}
	gradle, bazel := NewWatcher(home), NewBazelWatcher()
	processes := []model.Process{{PID: 42, Type: "BAZEL_SERVER", StartTimeMs: 1, BazelOutputBase: base}}
	var wg sync.WaitGroup
	for range 4 {
		wg.Go(func() {
			for range 20 {
				if _, err := gradle.Poll(nil); err != nil {
					t.Error(err)
				}
				bazel.Poll(processes)
			}
		})
		wg.Go(func() {
			for range 40 {
				gradle.List()
				gradle.AllTails()
				bazel.List()
				g, _ := gradle.TailFor(42)
				b, _ := bazel.TailFor(42, "")
				for _, tail := range []Tail{g, b} {
					for _, line := range tail.Lines {
						if strings.Contains(line, "private") {
							t.Error("unredacted line published")
						}
					}
				}
			}
		})
	}
	wg.Wait()
	g, _ := gradle.TailFor(42)
	b, _ := bazel.TailFor(42, "")
	if len(g.Lines) != defaultTailLines || len(b.Lines) != defaultTailLines {
		t.Fatalf("tail lengths: gradle=%d bazel=%d", len(g.Lines), len(b.Lines))
	}
}
