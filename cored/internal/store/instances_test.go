package store_test

import (
	"database/sql"
	"path/filepath"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/store"
	_ "modernc.org/sqlite"
)

func TestProcessInstancesSeparatePIDReuse(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "instances.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	host := "test-host"
	first := []model.Process{{
		PID: 42, Type: "GRADLE_DAEMON", Name: "java",
		CommandLine: "GradleDaemon-a", StartTimeMs: 1000, Status: "R",
	}}
	if err := s.ObserveProcesses(host, 2000, first); err != nil {
		t.Fatal(err)
	}

	// Same PID, new OS create time = new incarnation.
	second := []model.Process{{
		PID: 42, Type: "GRADLE_DAEMON", Name: "java",
		CommandLine: "GradleDaemon-b", StartTimeMs: 5000, Status: "R",
	}}
	if err := s.ObserveProcesses(host, 6000, second); err != nil {
		t.Fatal(err)
	}

	n, err := s.CountInstances()
	if err != nil || n != 2 {
		t.Fatalf("instances=%d err=%v want 2", n, err)
	}

	events, err := s.ListEvents(0, 100)
	if err != nil {
		t.Fatal(err)
	}
	var firstObs, disappear int
	ids := map[string]struct{}{}
	for _, ev := range events {
		if ev.SchemaVersion != 1 {
			t.Fatalf("schema_version=%d", ev.SchemaVersion)
		}
		ids[ev.ProcessInstanceID] = struct{}{}
		switch ev.EventType {
		case store.EventFirstObserved:
			firstObs++
		case store.EventDisappearanceDetected:
			disappear++
		}
	}
	if firstObs != 2 {
		t.Fatalf("first_observed=%d want 2; events=%+v", firstObs, events)
	}
	if disappear != 1 {
		t.Fatalf("disappearance_detected=%d want 1; events=%+v", disappear, events)
	}
	wantA := store.ProcessInstanceID(host, 42, 1000)
	wantB := store.ProcessInstanceID(host, 42, 5000)
	if _, ok := ids[wantA]; !ok {
		t.Fatalf("missing instance %s in %+v", wantA, ids)
	}
	if _, ok := ids[wantB]; !ok {
		t.Fatalf("missing instance %s in %+v", wantB, ids)
	}
}

func TestResurrectionDoesNotReemitFirstObserved(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "resurrect.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	host := "host"
	alive := []model.Process{{
		PID: 7, Type: "KOTLIN_DAEMON", CommandLine: "Kotlin", StartTimeMs: 10,
	}}
	if err := s.ObserveProcesses(host, 100, alive); err != nil {
		t.Fatal(err)
	}
	if err := s.ObserveProcesses(host, 200, nil); err != nil {
		t.Fatal(err)
	}
	if err := s.ObserveProcesses(host, 300, alive); err != nil {
		t.Fatal(err)
	}

	events, err := s.ListEvents(0, 50)
	if err != nil {
		t.Fatal(err)
	}
	var firstObs, disappear int
	for _, ev := range events {
		switch ev.EventType {
		case store.EventFirstObserved:
			firstObs++
		case store.EventDisappearanceDetected:
			disappear++
		}
	}
	if firstObs != 1 {
		t.Fatalf("first_observed=%d want 1; events=%+v", firstObs, events)
	}
	if disappear != 1 {
		t.Fatalf("disappearance=%d want 1; events=%+v", disappear, events)
	}
}

func TestSkipUnknownCreateTime(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "nocreate.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	if err := s.ObserveProcesses("host", 100, []model.Process{{
		PID: 1, Type: "GRADLE_DAEMON", CommandLine: "x", StartTimeMs: 0,
	}}); err != nil {
		t.Fatal(err)
	}
	n, err := s.CountInstances()
	if err != nil || n != 0 {
		t.Fatalf("instances=%d err=%v want 0", n, err)
	}
	events, err := s.ListEvents(0, 10)
	if err != nil || len(events) != 0 {
		t.Fatalf("events=%+v err=%v", events, err)
	}
}

func TestDisappearanceDetectedWithoutExitFabrication(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "disappear.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	host := "host"
	alive := []model.Process{{
		PID: 7, Type: "KOTLIN_DAEMON", CommandLine: "Kotlin", StartTimeMs: 10,
	}}
	if err := s.ObserveProcesses(host, 100, alive); err != nil {
		t.Fatal(err)
	}
	if err := s.ObserveProcesses(host, 200, nil); err != nil {
		t.Fatal(err)
	}
	// Second empty poll must not emit another disappearance.
	if err := s.ObserveProcesses(host, 300, nil); err != nil {
		t.Fatal(err)
	}

	events, err := s.ListEvents(0, 50)
	if err != nil {
		t.Fatal(err)
	}
	var disappear int
	for _, ev := range events {
		if ev.EventType == store.EventDisappearanceDetected {
			disappear++
			if ev.Source != store.EventSourceProcessPoll {
				t.Fatalf("source=%q", ev.Source)
			}
		}
	}
	if disappear != 1 {
		t.Fatalf("disappearance=%d want 1; events=%+v", disappear, events)
	}
}

func TestConfigurationObservedOnCommandLineChange(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "config.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	host := "host"
	p := model.Process{PID: 9, Type: "GRADLE_DAEMON", CommandLine: "a", StartTimeMs: 1}
	if err := s.ObserveProcesses(host, 10, []model.Process{p}); err != nil {
		t.Fatal(err)
	}
	p.CommandLine = "b"
	if err := s.ObserveProcesses(host, 20, []model.Process{p}); err != nil {
		t.Fatal(err)
	}

	events, err := s.ListEvents(0, 50)
	if err != nil {
		t.Fatal(err)
	}
	var config int
	for _, ev := range events {
		if ev.EventType == store.EventConfigurationObserved {
			config++
		}
	}
	if config != 1 {
		t.Fatalf("configuration_observed=%d want 1; events=%+v", config, events)
	}
}

func TestEventRetentionMatchesSamplePurge(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "retention.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	host := "host"
	oldMs := time.Now().Add(-48 * time.Hour).UnixMilli()
	if err := s.ObserveProcesses(host, oldMs, []model.Process{{
		PID: 1, Type: "GRADLE_DAEMON", CommandLine: "x", StartTimeMs: oldMs,
	}}); err != nil {
		t.Fatal(err)
	}
	if err := s.ObserveProcesses(host, oldMs+1, nil); err != nil {
		t.Fatal(err)
	}

	deleted, err := s.PurgeOlderThan(24 * time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if deleted == 0 {
		t.Fatal("expected purge to remove old events/instances")
	}
	nEvents, err := s.CountEvents()
	if err != nil || nEvents != 0 {
		t.Fatalf("events=%d err=%v", nEvents, err)
	}
	nInst, err := s.CountInstances()
	if err != nil || nInst != 0 {
		t.Fatalf("instances=%d err=%v", nInst, err)
	}
}

func TestSchemaVersionIncludesProcessInstances(t *testing.T) {
	path := filepath.Join(t.TempDir(), "schema.sqlite")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}

	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	var version int
	if err := db.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != 5 {
		t.Fatalf("user_version=%d want 5", version)
	}
	for _, table := range []string{"process_instances", "process_events", "process_samples", "builds"} {
		var name string
		err := db.QueryRow(
			`SELECT name FROM sqlite_master WHERE type='table' AND name=?`, table,
		).Scan(&name)
		if err != nil {
			t.Fatalf("missing table %s: %v", table, err)
		}
	}
}
