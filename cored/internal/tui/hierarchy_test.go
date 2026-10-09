package tui

import (
	"testing"

	tea "charm.land/bubbletea/v2"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestHierarchyAggregatesDaemonAndDescendantsOnce(t *testing.T) {
	root := model.Process{PID: 100, StartTimeMs: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 100, CPUPercent: floatPtr(10)}
	worker := model.Process{PID: 101, ParentPID: 100, StartTimeMs: 11, Type: "GRADLE_WORKER", RSSMemoryMB: 40, CPUPercent: floatPtr(4)}
	testExecutor := model.Process{PID: 102, ParentPID: 101, StartTimeMs: 12, Type: "GRADLE_WORKER", RSSMemoryMB: 60, CPUPercent: floatPtr(6)}
	other := model.Process{PID: 200, StartTimeMs: 20, Type: "KOTLIN_DAEMON", RSSMemoryMB: 70, CPUPercent: floatPtr(7)}

	h := newProcessHierarchy()
	rows := h.rows([]model.Process{root, worker, testExecutor, other}, true)
	if len(rows) != 4 || !rows[0].groupRoot || rows[0].children != 2 {
		t.Fatalf("rows=%+v", rows)
	}
	if rows[0].process.RSSMemoryMB != 200 {
		t.Fatalf("aggregate RSS=%d want 200", rows[0].process.RSSMemoryMB)
	}
	if rows[0].process.CPUPercent == nil || *rows[0].process.CPUPercent != 20 {
		t.Fatalf("aggregate CPU=%v want 20", rows[0].process.CPUPercent)
	}
	if rows[1].process.PID != worker.PID || rows[2].process.PID != testExecutor.PID || rows[3].process.PID != other.PID {
		t.Fatalf("unexpected row order: %d, %d, %d, %d", rows[0].process.PID, rows[1].process.PID, rows[2].process.PID, rows[3].process.PID)
	}
}

func TestHierarchyPreservesOwnershipAcrossReparenting(t *testing.T) {
	root := model.Process{PID: 100, StartTimeMs: 10, Type: "GRADLE_DAEMON"}
	worker := model.Process{PID: 101, ParentPID: 100, StartTimeMs: 11, Type: "GRADLE_WORKER"}
	h := newProcessHierarchy()
	if got := h.rows([]model.Process{root, worker}, true); len(got) != 2 {
		t.Fatalf("initial rows=%d", len(got))
	}
	worker.ParentPID = 1 // OS reparenting after an intermediate parent exits.
	rows := h.rows([]model.Process{root, worker}, true)
	if !rows[0].groupRoot || rows[0].children != 1 {
		t.Fatalf("ownership was lost after reparenting: %+v", rows)
	}
}

func TestHierarchyDoesNotReusePIDAcrossStartTime(t *testing.T) {
	root := model.Process{PID: 100, StartTimeMs: 10, Type: "GRADLE_DAEMON"}
	oldWorker := model.Process{PID: 101, ParentPID: 100, StartTimeMs: 11, Type: "GRADLE_WORKER"}
	h := newProcessHierarchy()
	h.rows([]model.Process{root, oldWorker}, true)

	newWorker := model.Process{PID: 101, ParentPID: 1, StartTimeMs: 99, Type: "GRADLE_WORKER"}
	rows := h.rows([]model.Process{root, newWorker}, true)
	if rows[0].groupRoot {
		t.Fatalf("PID reuse incorrectly retained old ownership: %+v", rows)
	}
}

func TestGroupedViewControlsAndDetailsUseSelectedProcess(t *testing.T) {
	m := NewModel(Config{Now: fixedNow, NoColor: true})
	m.applySnapshot(model.Snapshot{SampledAtMs: 1, Processes: []model.Process{
		{PID: 100, StartTimeMs: 10, Type: "GRADLE_DAEMON", RSSMemoryMB: 100},
		{PID: 101, ParentPID: 100, StartTimeMs: 11, Type: "GRADLE_WORKER", RSSMemoryMB: 20},
	}})
	next, _ := m.Update(key('v'))
	m = next.(Model)
	if !m.groupedView || !m.displayRows[0].groupRoot {
		t.Fatalf("grouped view not enabled: %+v", m.displayRows)
	}
	next, _ = m.Update(key('C'))
	m = next.(Model)
	if len(m.displayRows) != 1 || m.displayRows[0].process.PID != 100 {
		t.Fatalf("collapse all rows=%+v", m.displayRows)
	}
	next, _ = m.Update(key('E'))
	m = next.(Model)
	if len(m.displayRows) != 2 {
		t.Fatalf("expand all rows=%+v", m.displayRows)
	}
}

func floatPtr(v float64) *float64 { return &v }

func key(code rune) tea.KeyPressMsg { return tea.KeyPressMsg(tea.Key{Code: code}) }
