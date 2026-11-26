package store_test

import (
	"database/sql"
	"path/filepath"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestInsertSnapshotDeduplicatesCommandLineViaProcessInstances(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "watcher.db")
	st, err := store.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	longCmd := "java -cp " + strings.Repeat("/gradle/cache/kotlin-compiler-embeddable.jar:", 100) + " org.jetbrains.kotlin.daemon.KotlinCompileDaemon"
	proc := model.Process{
		PID:         4242,
		Type:        "KOTLIN_DAEMON",
		Name:        "java",
		CommandLine: longCmd,
		RSSMemoryMB: 512,
		Status:      "RUNNING",
		StartTimeMs: 1_700_000_000_000,
	}

	// First poll: InsertSnapshotForHost runs before ObserveProcesses (normal cored order).
	if err := st.InsertSnapshotForHost("host-a", model.Snapshot{SampledAtMs: 1_700_000_002_000, Processes: []model.Process{proc}}); err != nil {
		t.Fatal(err)
	}
	if err := st.ObserveProcesses("host-a", 1_700_000_002_000, []model.Process{proc}); err != nil {
		t.Fatal(err)
	}
	// Verify lifecycle event was still emitted (ObserveProcesses saw a brand-new instance).
	events, err := st.ListEvents(0, 10)
	if err != nil {
		t.Fatal(err)
	}
	foundFirstObserved := false
	for _, ev := range events {
		if ev.EventType == store.EventFirstObserved && ev.PID == 4242 {
			foundFirstObserved = true
		}
	}
	if !foundFirstObserved {
		t.Fatalf("expected process.first_observed event, got %+v", events)
	}

	// Subsequent polls: process_instances already stores command_line, so process_samples stores "".
	for i := int64(1); i <= 5; i++ {
		if err := st.InsertSnapshotForHost("host-a", model.Snapshot{
			SampledAtMs: 1_700_000_002_000 + i*2000,
			Processes:   []model.Process{proc},
		}); err != nil {
			t.Fatal(err)
		}
		if err := st.ObserveProcesses("host-a", 1_700_000_002_000+i*2000, []model.Process{proc}); err != nil {
			t.Fatal(err)
		}
	}

	// Raw DB check: all 6 samples stored "" inline while process_command_lines holds one row.
	rawDB, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer rawDB.Close()
	var nonEmptyCount, emptyCount, dictCount int
	if err := rawDB.QueryRow(`SELECT COUNT(*) FROM process_samples WHERE pid = 4242 AND command_line != ''`).Scan(&nonEmptyCount); err != nil {
		t.Fatal(err)
	}
	if err := rawDB.QueryRow(`SELECT COUNT(*) FROM process_samples WHERE pid = 4242 AND command_line = ''`).Scan(&emptyCount); err != nil {
		t.Fatal(err)
	}
	if err := rawDB.QueryRow(`SELECT COUNT(*) FROM process_command_lines`).Scan(&dictCount); err != nil {
		t.Fatal(err)
	}
	if nonEmptyCount != 0 || emptyCount != 6 || dictCount != 1 {
		t.Fatalf("raw command_line counts: nonEmpty=%d empty=%d dict=%d, want 0, 6, and 1", nonEmptyCount, emptyCount, dictCount)
	}

	// HistoryFiltered must still hydrate the full command_line for every returned row.
	hist, err := st.HistoryFiltered(store.HistoryFilter{SinceMs: 0, Limit: 20, PID: 4242, StartTimeMs: proc.StartTimeMs})
	if err != nil {
		t.Fatal(err)
	}
	if len(hist) != 6 {
		t.Fatalf("history len=%d, want 6", len(hist))
	}
	for i, row := range hist {
		if row.CommandLine != longCmd {
			t.Fatalf("history[%d] CommandLine len=%d, want %d", i, len(row.CommandLine), len(longCmd))
		}
	}
}
