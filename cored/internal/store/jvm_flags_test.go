package store_test

import (
	"context"
	"database/sql"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestJVMFlagsMigrationRetentionAndPIDReuse(t *testing.T) {
	path := filepath.Join(t.TempDir(), "flags.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.ObserveProcesses("host", 100, []model.Process{{PID: 7, StartTimeMs: 1}}); err != nil {
		t.Fatal(err)
	}
	s.Close()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	// Emulate a version 11 database without losing its existing process rows.
	if _, err = db.Exec(`ALTER TABLE process_instances DROP COLUMN jvm_flags_json`); err != nil {
		t.Fatal(err)
	}
	db.Close()
	s, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if n, err := s.CountInstances(); err != nil || n != 1 {
		t.Fatalf("migration lost existing rows: %d %v", n, err)
	}
	probe := heap.NewProber()
	probe.LookPath = func(string) (string, error) { return "jcmd", nil }
	probe.Runner = func(context.Context, string, ...string) (string, error) {
		return `42:
[Global flags]
 bool UseG1GC = true {product} {ergonomic}
 uintx MaxHeapSize = 4294967296 {product} {command line}
 ccstr HeapDumpPath = /sensitive/home {product}
 ccstr OnError = token=private {product}
 uintx SecretToken = 12345 {product}
`, nil
	}
	m := probe.FlagsFor(context.Background(), 42, 1000)
	p := model.Process{PID: 42, StartTimeMs: 1000, Type: "GRADLE_DAEMON", RSSMemoryMB: 99, JVMFlags: &m}
	for i := 0; i < 2; i++ {
		if err := s.ObserveProcesses("host", 2000, []model.Process{p}); err != nil {
			t.Fatal(err)
		}
		if err := s.InsertSnapshot(model.Snapshot{SampledAtMs: 2000, Processes: []model.Process{p}}); err != nil {
			t.Fatal(err)
		}
	}
	got, err := s.JVMFlags("host", 42, 1000)
	if err != nil || got == nil || len(got.Flags) != 2 || got.OmittedCount != 3 {
		t.Fatalf("metadata=%+v err=%v", got, err)
	}
	data, _ := json.Marshal(got)
	for _, secret := range []string{"sensitive", "private", "12345", "HeapDumpPath", "OnError", "SecretToken"} {
		if strings.Contains(string(data), secret) {
			t.Fatal("retained sensitive flag", secret)
		}
	}
	snapshot, _ := json.Marshal(p)
	if strings.Contains(string(snapshot), "MaxHeapSize") {
		t.Fatal("full metadata repeated in process row")
	}
	history, err := s.History(0, 10)
	if err != nil || len(history) != 2 || history[0].RSSMemoryMB != 99 {
		t.Fatalf("history=%+v err=%v", history, err)
	}
	if err := s.ObserveProcesses("host", 3000, []model.Process{{PID: 42, StartTimeMs: 2000}}); err != nil {
		t.Fatal(err)
	}
	if got, err := s.JVMFlags("host", 42, 2000); err != nil || got != nil {
		t.Fatal("PID reuse inherited metadata")
	}
	if got, err := s.JVMFlags("other-host", 42, 1000); err != nil || got != nil {
		t.Fatal("host identity inherited metadata")
	}
	s.Close()
	s, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if got, err := s.JVMFlags("host", 42, 1000); err != nil || got == nil {
		t.Fatal("metadata not durable", err)
	}
	if _, err := s.PurgeOlderThan(time.Hour); err != nil {
		t.Fatal(err)
	}
	if got, err := s.JVMFlags("host", 42, 1000); err != nil || got != nil {
		t.Fatal("metadata outlived retention")
	}
}
