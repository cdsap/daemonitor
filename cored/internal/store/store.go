package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
	_ "modernc.org/sqlite"
)

// Store persists process samples with a simple retention window.
type Store struct {
	db *sql.DB
}

// busyTimeoutMs lets a second process (CLI/desktop) wait briefly when the core holds a write lock.
const busyTimeoutMs = 5000

func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create db dir: %w", err)
	}
	// _pragma query params apply before first use; WAL allows the app to read while core writes.
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(%d)&_pragma=journal_mode(WAL)", path, busyTimeoutMs)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// Single writer connection inside this process; other processes open their own handles under WAL.
	db.SetMaxOpenConns(1)
	s := &Store{db: db}
	if err := s.configureSharedAccess(); err != nil {
		_ = db.Close()
		return nil, err
	}
	if err := s.migrate(); err != nil {
		_ = db.Close()
		return nil, err
	}
	// SQLite files can contain command lines, paths, and diagnostic metadata.
	// Do not rely on the process umask for this privacy boundary.
	if err := os.Chmod(path, 0o600); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("restrict db permissions: %w", err)
	}
	return s, nil
}

func (s *Store) configureSharedAccess() error {
	if _, err := s.db.Exec(fmt.Sprintf(`PRAGMA busy_timeout = %d`, busyTimeoutMs)); err != nil {
		return err
	}
	var mode string
	if err := s.db.QueryRow(`PRAGMA journal_mode = WAL`).Scan(&mode); err != nil {
		return err
	}
	if mode != "wal" && mode != "WAL" {
		return fmt.Errorf("journal_mode=%q want wal", mode)
	}
	return nil
}

// JournalMode returns the current SQLite journal_mode (for tests / diagnostics).
func (s *Store) JournalMode() (string, error) {
	var mode string
	err := s.db.QueryRow(`PRAGMA journal_mode`).Scan(&mode)
	return mode, err
}

func (s *Store) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

func (s *Store) migrate() error {
	if err := s.ensureProcessSamplesAlignedWithApp(); err != nil {
		return err
	}
	if err := s.ensureBuildsAlignedWithApp(); err != nil {
		return err
	}
	if err := s.ensureProcessInstancesAndEvents(); err != nil {
		return err
	}
	if err := s.ensureObservationSessions(); err != nil {
		return err
	}
	if err := s.ensureBazelBuildDetails(); err != nil {
		return err
	}
	_, err := s.db.Exec(fmt.Sprintf(`PRAGMA user_version = %d`, schemaVersion))
	return err
}

// schemaVersion tracks spike SQLite migrations toward the app WatcherDatabase schema.
// v2: builds columns match Watcher.sq (start_time/end_time/command_line).
// v4: process_samples includes optional OS and JVM observability metrics.
// v5: process_samples includes cumulative CPU counters and interval cores used.
// v6: process_instances + process_events for incarnation identity and lifecycle.
// v7: builds.tool distinguishes GRADLE vs BAZEL (opportunistic BEP) rows.
// v8: optional Bazel trace summaries retained across BEP enrichment.
// v9: inclusive Bazel subtree resource samples for build rollups.
// v10: Bazel descendant owner columns on process_samples and build window_summaries.
// v11: bazel_build_details table for rich BEP telemetry.
// v12: optional per-incarnation effective JVM flags document.
// v13: successful JVM acquisition times by family (null for legacy rows).
// v14: nullable JIT/class metrics and independent cumulative GC seconds.
const schemaVersion = 14

func (s *Store) ensureProcessSamplesAlignedWithApp() error {
	exists, err := s.tableExists("process_samples")
	if err != nil {
		return err
	}
	if !exists {
		return s.createProcessSamplesAppAligned()
	}
	cols, err := s.tableColumns("process_samples")
	if err != nil {
		return err
	}
	if contains(cols, "timestamp_ms") {
		return s.migrateProcessSamplesLegacyToAppAligned()
	}
	if !contains(cols, "timestamp") {
		return s.createProcessSamplesAppAligned()
	}
	return s.ensureProcessSamplesAdditiveColumns(cols)
}

func (s *Store) createProcessSamplesAppAligned() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS process_samples (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp INTEGER NOT NULL,
  host_id TEXT NOT NULL DEFAULT '',
  pid INTEGER NOT NULL,
  parent_pid INTEGER NOT NULL DEFAULT 0,
  owner_pid INTEGER,
  owner_start_time_ms INTEGER,
  process_type TEXT NOT NULL,
  command_line TEXT NOT NULL,
  working_directory TEXT,
  project_path TEXT,
  cpu_percent REAL,
	cpu_user_seconds_total REAL,
	cpu_system_seconds_total REAL,
	cpu_total_seconds_total REAL,
	cpu_cores_used REAL,
	logical_processor_count INTEGER,
  rss_memory_mb INTEGER NOT NULL,
  subtree_rss_memory_mb INTEGER,
  subtree_cpu_percent REAL,
	virtual_memory_mb INTEGER,
	swap_memory_mb INTEGER,
	resident_bytes INTEGER,
	virtual_bytes INTEGER,
	private_committed_bytes INTEGER,
	proportional_bytes INTEGER,
	physical_footprint_bytes INTEGER,
	swap_bytes INTEGER,
	peak_resident_bytes INTEGER,
	proportional_sampled_at_ms INTEGER,
	thread_count INTEGER,
	read_bytes INTEGER,
	write_bytes INTEGER,
	read_operations INTEGER,
	write_operations INTEGER,
	minor_page_faults INTEGER,
	major_page_faults INTEGER,
	voluntary_context_switches INTEGER,
	involuntary_context_switches INTEGER,
	open_file_descriptors INTEGER,
  max_heap_mb INTEGER,
  heap_used_mb INTEGER,
  heap_committed_mb INTEGER,
  heap_max_mb INTEGER,
  heap_sampled_at_ms INTEGER,
  jvm_sampled_at_ms TEXT,
  heap_available INTEGER,
  status TEXT NOT NULL,
  name TEXT,
  min_heap_mb INTEGER,
  gc TEXT,
	metaspace_used_mb INTEGER,
	metaspace_committed_mb INTEGER,
	young_gc_count INTEGER,
	young_gc_time_ms INTEGER,
	old_gc_count INTEGER,
	old_gc_time_ms INTEGER,
	full_gc_count INTEGER,
	concurrent_gc_count INTEGER,
	total_gc_count INTEGER,
	java_version TEXT,
	java_runtime_version TEXT,
	java_vendor TEXT,
	java_vm_name TEXT,
	java_vm_version TEXT,
	os_name TEXT,
	os_arch TEXT,
	active_processor_count INTEGER,
  jit_present INTEGER,
  jit_compiled_count INTEGER,
  jit_failed_count INTEGER,
  jit_invalid_count INTEGER,
  jit_compilation_time_seconds REAL,
  classes_present INTEGER,
  classes_loaded INTEGER,
  classes_unloaded_total INTEGER,
  classes_loaded_total INTEGER,
  classes_time_seconds REAL,
  young_gc_time_seconds REAL,
  full_gc_time_seconds REAL,
  concurrent_gc_time_seconds REAL,
  total_gc_time_seconds REAL,
  start_time_ms INTEGER NOT NULL DEFAULT 0,
  automated INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS process_samples_ts ON process_samples(timestamp);
CREATE INDEX IF NOT EXISTS process_samples_pid_ts ON process_samples(pid, timestamp);
`)
	return err
}

func (s *Store) ensureProcessSamplesAdditiveColumns(cols []string) error {
	alters := []struct {
		col string
		ddl string
	}{
		{"jvm_sampled_at_ms", `ALTER TABLE process_samples ADD COLUMN jvm_sampled_at_ms TEXT`},
		{"jit_present", `ALTER TABLE process_samples ADD COLUMN jit_present INTEGER`},
		{"jit_compiled_count", `ALTER TABLE process_samples ADD COLUMN jit_compiled_count INTEGER`},
		{"jit_failed_count", `ALTER TABLE process_samples ADD COLUMN jit_failed_count INTEGER`},
		{"jit_invalid_count", `ALTER TABLE process_samples ADD COLUMN jit_invalid_count INTEGER`},
		{"jit_compilation_time_seconds", `ALTER TABLE process_samples ADD COLUMN jit_compilation_time_seconds REAL`},
		{"classes_present", `ALTER TABLE process_samples ADD COLUMN classes_present INTEGER`},
		{"classes_loaded", `ALTER TABLE process_samples ADD COLUMN classes_loaded INTEGER`},
		{"classes_unloaded_total", `ALTER TABLE process_samples ADD COLUMN classes_unloaded_total INTEGER`},
		{"classes_loaded_total", `ALTER TABLE process_samples ADD COLUMN classes_loaded_total INTEGER`},
		{"classes_time_seconds", `ALTER TABLE process_samples ADD COLUMN classes_time_seconds REAL`},
		{"young_gc_time_seconds", `ALTER TABLE process_samples ADD COLUMN young_gc_time_seconds REAL`},
		{"full_gc_time_seconds", `ALTER TABLE process_samples ADD COLUMN full_gc_time_seconds REAL`},
		{"concurrent_gc_time_seconds", `ALTER TABLE process_samples ADD COLUMN concurrent_gc_time_seconds REAL`},
		{"total_gc_time_seconds", `ALTER TABLE process_samples ADD COLUMN total_gc_time_seconds REAL`},
		{"owner_pid", `ALTER TABLE process_samples ADD COLUMN owner_pid INTEGER`},
		{"owner_start_time_ms", `ALTER TABLE process_samples ADD COLUMN owner_start_time_ms INTEGER`},
		{"subtree_rss_memory_mb", `ALTER TABLE process_samples ADD COLUMN subtree_rss_memory_mb INTEGER`},
		{"subtree_cpu_percent", `ALTER TABLE process_samples ADD COLUMN subtree_cpu_percent REAL`},
		{"virtual_memory_mb", `ALTER TABLE process_samples ADD COLUMN virtual_memory_mb INTEGER`},
		{"swap_memory_mb", `ALTER TABLE process_samples ADD COLUMN swap_memory_mb INTEGER`},
		{"resident_bytes", `ALTER TABLE process_samples ADD COLUMN resident_bytes INTEGER`},
		{"virtual_bytes", `ALTER TABLE process_samples ADD COLUMN virtual_bytes INTEGER`},
		{"private_committed_bytes", `ALTER TABLE process_samples ADD COLUMN private_committed_bytes INTEGER`},
		{"proportional_bytes", `ALTER TABLE process_samples ADD COLUMN proportional_bytes INTEGER`},
		{"physical_footprint_bytes", `ALTER TABLE process_samples ADD COLUMN physical_footprint_bytes INTEGER`},
		{"swap_bytes", `ALTER TABLE process_samples ADD COLUMN swap_bytes INTEGER`},
		{"peak_resident_bytes", `ALTER TABLE process_samples ADD COLUMN peak_resident_bytes INTEGER`},
		{"proportional_sampled_at_ms", `ALTER TABLE process_samples ADD COLUMN proportional_sampled_at_ms INTEGER`},
		{"thread_count", `ALTER TABLE process_samples ADD COLUMN thread_count INTEGER`},
		{"read_bytes", `ALTER TABLE process_samples ADD COLUMN read_bytes INTEGER`},
		{"write_bytes", `ALTER TABLE process_samples ADD COLUMN write_bytes INTEGER`},
		{"read_operations", `ALTER TABLE process_samples ADD COLUMN read_operations INTEGER`},
		{"write_operations", `ALTER TABLE process_samples ADD COLUMN write_operations INTEGER`},
		{"minor_page_faults", `ALTER TABLE process_samples ADD COLUMN minor_page_faults INTEGER`},
		{"major_page_faults", `ALTER TABLE process_samples ADD COLUMN major_page_faults INTEGER`},
		{"voluntary_context_switches", `ALTER TABLE process_samples ADD COLUMN voluntary_context_switches INTEGER`},
		{"involuntary_context_switches", `ALTER TABLE process_samples ADD COLUMN involuntary_context_switches INTEGER`},
		{"open_file_descriptors", `ALTER TABLE process_samples ADD COLUMN open_file_descriptors INTEGER`},
		{"metaspace_used_mb", `ALTER TABLE process_samples ADD COLUMN metaspace_used_mb INTEGER`},
		{"metaspace_committed_mb", `ALTER TABLE process_samples ADD COLUMN metaspace_committed_mb INTEGER`},
		{"young_gc_count", `ALTER TABLE process_samples ADD COLUMN young_gc_count INTEGER`},
		{"young_gc_time_ms", `ALTER TABLE process_samples ADD COLUMN young_gc_time_ms INTEGER`},
		{"old_gc_count", `ALTER TABLE process_samples ADD COLUMN old_gc_count INTEGER`},
		{"old_gc_time_ms", `ALTER TABLE process_samples ADD COLUMN old_gc_time_ms INTEGER`},
		{"full_gc_count", `ALTER TABLE process_samples ADD COLUMN full_gc_count INTEGER`},
		{"concurrent_gc_count", `ALTER TABLE process_samples ADD COLUMN concurrent_gc_count INTEGER`},
		{"total_gc_count", `ALTER TABLE process_samples ADD COLUMN total_gc_count INTEGER`},
		{"java_version", `ALTER TABLE process_samples ADD COLUMN java_version TEXT`},
		{"java_runtime_version", `ALTER TABLE process_samples ADD COLUMN java_runtime_version TEXT`},
		{"java_vendor", `ALTER TABLE process_samples ADD COLUMN java_vendor TEXT`},
		{"java_vm_name", `ALTER TABLE process_samples ADD COLUMN java_vm_name TEXT`},
		{"java_vm_version", `ALTER TABLE process_samples ADD COLUMN java_vm_version TEXT`},
		{"os_name", `ALTER TABLE process_samples ADD COLUMN os_name TEXT`},
		{"os_arch", `ALTER TABLE process_samples ADD COLUMN os_arch TEXT`},
		{"active_processor_count", `ALTER TABLE process_samples ADD COLUMN active_processor_count INTEGER`},
		{"cpu_user_seconds_total", `ALTER TABLE process_samples ADD COLUMN cpu_user_seconds_total REAL`},
		{"cpu_system_seconds_total", `ALTER TABLE process_samples ADD COLUMN cpu_system_seconds_total REAL`},
		{"cpu_total_seconds_total", `ALTER TABLE process_samples ADD COLUMN cpu_total_seconds_total REAL`},
		{"cpu_cores_used", `ALTER TABLE process_samples ADD COLUMN cpu_cores_used REAL`},
		{"logical_processor_count", `ALTER TABLE process_samples ADD COLUMN logical_processor_count INTEGER`},
		{"heap_used_mb", `ALTER TABLE process_samples ADD COLUMN heap_used_mb INTEGER`},
		{"heap_committed_mb", `ALTER TABLE process_samples ADD COLUMN heap_committed_mb INTEGER`},
		{"heap_max_mb", `ALTER TABLE process_samples ADD COLUMN heap_max_mb INTEGER`},
		{"heap_sampled_at_ms", `ALTER TABLE process_samples ADD COLUMN heap_sampled_at_ms INTEGER`},
		{"heap_available", `ALTER TABLE process_samples ADD COLUMN heap_available INTEGER`},
		{"name", `ALTER TABLE process_samples ADD COLUMN name TEXT`},
		{"min_heap_mb", `ALTER TABLE process_samples ADD COLUMN min_heap_mb INTEGER`},
		{"gc", `ALTER TABLE process_samples ADD COLUMN gc TEXT`},
		{"start_time_ms", `ALTER TABLE process_samples ADD COLUMN start_time_ms INTEGER NOT NULL DEFAULT 0`},
		{"host_id", `ALTER TABLE process_samples ADD COLUMN host_id TEXT NOT NULL DEFAULT ''`},
		{"automated", `ALTER TABLE process_samples ADD COLUMN automated INTEGER NOT NULL DEFAULT 0`},
	}
	for _, a := range alters {
		if contains(cols, a.col) {
			continue
		}
		if _, err := s.db.Exec(a.ddl); err != nil {
			return err
		}
	}
	_, err := s.db.Exec(`
CREATE INDEX IF NOT EXISTS process_samples_ts ON process_samples(timestamp);
CREATE INDEX IF NOT EXISTS process_samples_pid_ts ON process_samples(pid, timestamp);
`)
	return err
}

func (s *Store) migrateProcessSamplesLegacyToAppAligned() error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.Exec(`
CREATE TABLE process_samples_v3 (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp INTEGER NOT NULL,
  pid INTEGER NOT NULL,
  parent_pid INTEGER NOT NULL DEFAULT 0,
  owner_pid INTEGER,
  owner_start_time_ms INTEGER,
  process_type TEXT NOT NULL,
  command_line TEXT NOT NULL,
  working_directory TEXT,
  project_path TEXT,
  cpu_percent REAL,
  rss_memory_mb INTEGER NOT NULL,
  max_heap_mb INTEGER,
  heap_used_mb INTEGER,
  heap_committed_mb INTEGER,
  heap_max_mb INTEGER,
  heap_sampled_at_ms INTEGER,
  heap_available INTEGER,
  status TEXT NOT NULL,
  name TEXT,
  min_heap_mb INTEGER,
  gc TEXT,
  start_time_ms INTEGER NOT NULL DEFAULT 0,
  automated INTEGER NOT NULL DEFAULT 0
)`); err != nil {
		return err
	}
	if _, err := tx.Exec(`
INSERT INTO process_samples_v3(
  timestamp, pid, parent_pid, process_type, command_line, working_directory, project_path,
  cpu_percent, rss_memory_mb, max_heap_mb, heap_used_mb, heap_committed_mb, heap_max_mb,
  heap_sampled_at_ms, heap_available, status, name, min_heap_mb, gc, start_time_ms, automated
)
SELECT
  timestamp_ms, pid, parent_pid, process_type, command_line, working_directory, project_path,
  cpu_percent, rss_memory_mb, max_heap_mb, NULL, NULL, NULL,
  NULL, 0, status, name, min_heap_mb, gc, start_time_ms, automated
FROM process_samples`); err != nil {
		return err
	}
	if _, err := tx.Exec(`DROP TABLE process_samples`); err != nil {
		return err
	}
	if _, err := tx.Exec(`ALTER TABLE process_samples_v3 RENAME TO process_samples`); err != nil {
		return err
	}
	if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS process_samples_ts ON process_samples(timestamp)`); err != nil {
		return err
	}
	if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS process_samples_pid_ts ON process_samples(pid, timestamp)`); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	cols, err := s.tableColumns("process_samples")
	if err != nil {
		return err
	}
	return s.ensureProcessSamplesAdditiveColumns(cols)
}

func (s *Store) ensureBuildsAlignedWithApp() error {
	exists, err := s.tableExists("builds")
	if err != nil {
		return err
	}
	if !exists {
		return s.createBuildsAppAligned()
	}
	cols, err := s.tableColumns("builds")
	if err != nil {
		return err
	}
	if contains(cols, "start_time_ms") {
		if err := s.migrateBuildsLegacyToAppAligned(); err != nil {
			return err
		}
		cols, err = s.tableColumns("builds")
		if err != nil {
			return err
		}
	}
	if !contains(cols, "start_time") {
		return s.createBuildsAppAligned()
	}
	if !contains(cols, "command_line") {
		if _, err := s.db.Exec(`ALTER TABLE builds ADD COLUMN command_line TEXT`); err != nil {
			return err
		}
	}
	cols, err = s.tableColumns("builds")
	if err != nil {
		return err
	}
	for _, a := range []struct {
		col string
		ddl string
	}{
		{"agent", `ALTER TABLE builds ADD COLUMN agent TEXT`},
		{"agent_provider", `ALTER TABLE builds ADD COLUMN agent_provider TEXT`},
		{"tool", `ALTER TABLE builds ADD COLUMN tool TEXT NOT NULL DEFAULT 'GRADLE'`},
		{"bazel_profile", `ALTER TABLE builds ADD COLUMN bazel_profile TEXT`},
		{"window_summaries", `ALTER TABLE builds ADD COLUMN window_summaries TEXT`},
	} {
		if contains(cols, a.col) {
			continue
		}
		if _, err := s.db.Exec(a.ddl); err != nil {
			return err
		}
	}
	_, err = s.db.Exec(`
CREATE INDEX IF NOT EXISTS builds_project ON builds(project_path);
CREATE INDEX IF NOT EXISTS builds_daemon_pid ON builds(daemon_pid);
CREATE INDEX IF NOT EXISTS builds_tool ON builds(tool)`)
	return err
}

func (s *Store) createBuildsAppAligned() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS builds (
  build_id TEXT PRIMARY KEY,
  daemon_pid INTEGER NOT NULL,
  daemon_identity TEXT,
  command_line TEXT,
  working_directory TEXT,
  project_path TEXT,
  start_time INTEGER NOT NULL,
  end_time INTEGER,
  duration_seconds REAL,
  peak_memory_mb INTEGER,
  avg_memory_mb INTEGER,
  peak_cpu_percent REAL,
  inferred_source TEXT NOT NULL DEFAULT 'UNKNOWN',
  final_status TEXT NOT NULL,
  log_snippet TEXT,
  agent TEXT,
  agent_provider TEXT,
  tool TEXT NOT NULL DEFAULT 'GRADLE',
  bazel_profile TEXT,
  window_summaries TEXT
);
CREATE INDEX IF NOT EXISTS builds_start ON builds(start_time);
CREATE INDEX IF NOT EXISTS builds_project ON builds(project_path);
CREATE INDEX IF NOT EXISTS builds_daemon_pid ON builds(daemon_pid);
CREATE INDEX IF NOT EXISTS builds_tool ON builds(tool);
`)
	return err
}

func (s *Store) migrateBuildsLegacyToAppAligned() error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.Exec(`
CREATE TABLE builds_v2 (
  build_id TEXT PRIMARY KEY,
  daemon_pid INTEGER NOT NULL,
  daemon_identity TEXT,
  command_line TEXT,
  working_directory TEXT,
  project_path TEXT,
  start_time INTEGER NOT NULL,
  end_time INTEGER,
  duration_seconds REAL,
  peak_memory_mb INTEGER,
  avg_memory_mb INTEGER,
  peak_cpu_percent REAL,
  inferred_source TEXT NOT NULL DEFAULT 'UNKNOWN',
  final_status TEXT NOT NULL,
  log_snippet TEXT,
  agent TEXT,
  agent_provider TEXT,
  tool TEXT NOT NULL DEFAULT 'GRADLE'
)`); err != nil {
		return err
	}
	if _, err := tx.Exec(`
INSERT INTO builds_v2(
  build_id, daemon_pid, daemon_identity, command_line, working_directory, project_path,
  start_time, end_time, duration_seconds, peak_memory_mb, avg_memory_mb, peak_cpu_percent,
  inferred_source, final_status, log_snippet, agent, agent_provider, tool
)
SELECT
  build_id, daemon_pid, daemon_identity, NULL, working_directory, project_path,
  start_time_ms, end_time_ms, duration_seconds, peak_memory_mb, avg_memory_mb, peak_cpu_percent,
  inferred_source, final_status, log_snippet, agent, agent_provider, 'GRADLE'
FROM builds`); err != nil {
		return err
	}
	if _, err := tx.Exec(`DROP TABLE builds`); err != nil {
		return err
	}
	if _, err := tx.Exec(`ALTER TABLE builds_v2 RENAME TO builds`); err != nil {
		return err
	}
	if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS builds_start ON builds(start_time)`); err != nil {
		return err
	}
	if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS builds_project ON builds(project_path)`); err != nil {
		return err
	}
	if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS builds_daemon_pid ON builds(daemon_pid)`); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) tableExists(name string) (bool, error) {
	var found string
	err := s.db.QueryRow(
		`SELECT name FROM sqlite_master WHERE type='table' AND name=?`, name,
	).Scan(&found)
	if err == sql.ErrNoRows {
		return false, nil
	}
	return err == nil, err
}

func (s *Store) tableColumns(table string) ([]string, error) {
	rows, err := s.db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]string, 0)
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull, pk int
		var dflt sql.NullString
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			return nil, err
		}
		out = append(out, name)
	}
	return out, rows.Err()
}

func contains(xs []string, want string) bool {
	for _, x := range xs {
		if x == want {
			return true
		}
	}
	return false
}

func (s *Store) InsertSnapshot(snap model.Snapshot) error {
	return s.InsertSnapshotForHost("", snap)
}

func (s *Store) InsertSnapshotForHost(hostID string, snap model.Snapshot) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	stmt, err := tx.Prepare(`
INSERT INTO process_samples(
  timestamp, host_id, pid, parent_pid, process_type, command_line,
	working_directory, project_path, rss_memory_mb, virtual_memory_mb, swap_memory_mb, resident_bytes, virtual_bytes,
	private_committed_bytes, proportional_bytes, physical_footprint_bytes, swap_bytes, peak_resident_bytes,
	proportional_sampled_at_ms, thread_count,
  read_bytes, write_bytes, read_operations, write_operations, minor_page_faults, major_page_faults,
  voluntary_context_switches, involuntary_context_switches, open_file_descriptors, cpu_percent,
  cpu_user_seconds_total, cpu_system_seconds_total, cpu_total_seconds_total, cpu_cores_used, logical_processor_count,
  max_heap_mb, heap_used_mb, heap_committed_mb, heap_max_mb, heap_sampled_at_ms, heap_available,
  status, name, min_heap_mb, gc, metaspace_used_mb, metaspace_committed_mb, young_gc_count,
  young_gc_time_ms, old_gc_count, old_gc_time_ms, full_gc_count, concurrent_gc_count, total_gc_count, java_version, java_runtime_version, java_vendor,
  java_vm_name, java_vm_version, os_name, os_arch, active_processor_count,
  start_time_ms, automated, subtree_rss_memory_mb, subtree_cpu_percent, owner_pid, owner_start_time_ms, jvm_sampled_at_ms,
  jit_present, jit_compiled_count, jit_failed_count, jit_invalid_count, jit_compilation_time_seconds, classes_present, classes_loaded, classes_unloaded_total, classes_loaded_total, classes_time_seconds, young_gc_time_seconds, full_gc_time_seconds, concurrent_gc_time_seconds, total_gc_time_seconds
) VALUES (
 ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
 ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
 ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
 ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
 ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
 ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
 ?, ?, ?, ?, ?, ?, ?, ?, ?,
 ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()

	for _, p := range snap.Processes {
		// History retains heap/GC timestamps and optional JIT/class timestamps when persisted.
		var retainedTimes map[string]int64
		families := []string{"heap", "gc"}
		if p.JIT != nil {
			families = append(families, "jit")
		}
		if p.Classes != nil {
			families = append(families, "classes")
		}
		for _, family := range families {
			if at, ok := p.JVMMeasurementTimes[family]; ok {
				if retainedTimes == nil {
					retainedTimes = make(map[string]int64)
				}
				retainedTimes[family] = at
			}
		}
		timesJSON, err := json.Marshal(retainedTimes)
		if err != nil {
			return err
		}
		automated := 0
		if p.Automated {
			automated = 1
		}
		heapAvail := 0
		if p.HeapAvailable {
			heapAvail = 1
		}
		var ownerPID, ownerStart any
		if p.OwnerPID > 0 {
			ownerPID = p.OwnerPID
		}
		if p.OwnerStartTimeMs > 0 {
			ownerStart = p.OwnerStartTimeMs
		}
		var jit model.JITMetrics
		if p.JIT != nil {
			jit = *p.JIT
		}
		var classes model.ClassMetrics
		if p.Classes != nil {
			classes = *p.Classes
		}
		if _, err := stmt.Exec(
			snap.SampledAtMs, hostID, p.PID, p.ParentPID, p.Type, p.CommandLine,
			p.WorkingDirectory, p.ProjectPath, p.RSSMemoryMB, p.VirtualMemoryMB, p.SwapMemoryMB, p.ResidentBytes,
			p.VirtualBytes, p.PrivateCommittedBytes, p.ProportionalBytes, p.PhysicalFootprintBytes, p.SwapBytes,
			p.PeakResidentBytes, p.ProportionalSampledAtMs, p.ThreadCount,
			p.ReadBytes, p.WriteBytes, p.ReadOperations, p.WriteOperations, p.MinorPageFaults, p.MajorPageFaults,
			p.VoluntaryContextSwitches, p.InvoluntaryContextSwitches, p.OpenFileDescriptors, p.CPUPercent,
			p.CPUUserSecondsTotal, p.CPUSystemSecondsTotal, p.CPUTotalSecondsTotal, p.CPUCoresUsed, p.LogicalProcessorCount,
			p.MaxHeapMB, p.HeapUsedMB, p.HeapCommittedMB, p.HeapMaxMB, p.HeapSampledAtMs, heapAvail,
			p.Status, nullStr(p.Name), p.MinHeapMB, p.GC, p.MetaspaceUsedMB, p.MetaspaceCommittedMB,
			p.YoungGCCount, p.YoungGCTimeMs, p.OldGCCount, p.OldGCTimeMs, p.FullGCCount, p.ConcurrentGCCount, p.TotalGCCount, p.JavaVersion, p.JavaRuntimeVersion,
			p.JavaVendor, p.JavaVMName, p.JavaVMVersion, p.OSName, p.OSArch, p.ActiveProcessorCount, p.StartTimeMs, automated, p.SubtreeRSSMemoryMB, p.SubtreeCPUPercent,
			ownerPID, ownerStart, string(timesJSON),
			p.JIT != nil, jit.CompiledCount, jit.FailedCount, jit.InvalidCount, jit.CompilationTimeSeconds,
			p.Classes != nil, classes.LoadedClasses, classes.UnloadedClasses, classes.LoadedClassesTotal, classes.ClassTimeSeconds,
			p.YoungGCTimeSeconds, p.FullGCTimeSeconds, p.ConcurrentGCTimeSeconds, p.TotalGCTimeSeconds,
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) PurgeOlderThan(retention time.Duration) (int64, error) {
	cutoff := time.Now().Add(-retention).UnixMilli()
	res, err := s.db.Exec(`DELETE FROM process_samples WHERE timestamp < ?`, cutoff)
	if err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, err
	}
	ev, err := s.db.Exec(`DELETE FROM process_events WHERE timestamp_ms < ?`, cutoff)
	if err != nil {
		return n, err
	}
	evN, err := ev.RowsAffected()
	if err != nil {
		return n, err
	}
	inst, err := s.db.Exec(`DELETE FROM process_instances WHERE last_observed_ms < ?`, cutoff)
	if err != nil {
		return n + evN, err
	}
	instN, err := inst.RowsAffected()
	if err != nil {
		return n + evN, err
	}
	if _, err := s.db.Exec(`DELETE FROM bazel_build_details WHERE build_id IN (SELECT build_id FROM builds WHERE start_time < ?)`, cutoff); err != nil {
		return n + evN + instN, err
	}
	buildsRes, err := s.db.Exec(`DELETE FROM builds WHERE start_time < ?`, cutoff)
	if err != nil {
		return n + evN + instN, err
	}
	buildsN, err := buildsRes.RowsAffected()
	if err != nil {
		return n + evN + instN, err
	}
	return n + evN + instN + buildsN, nil
}

// HistoryFilter narrows process_samples queries. Zero values leave a dimension unfiltered.
type HistoryFilter struct {
	SinceMs     int64
	Limit       int
	PID         int64
	StartTimeMs int64
}

func (s *Store) History(sinceMs int64, limit int) ([]model.Process, error) {
	return s.HistoryFiltered(HistoryFilter{SinceMs: sinceMs, Limit: limit})
}

// HistoryFiltered returns up to Limit of the most recent process_samples rows
// matching filter, ordered chronologically ascending.
func (s *Store) HistoryFiltered(filter HistoryFilter) ([]model.Process, error) {
	limit := filter.Limit
	if limit <= 0 || limit > 5000 {
		limit = 500
	}
	var conds []string
	var args []any
	conds = append(conds, "timestamp >= ?")
	args = append(args, filter.SinceMs)
	if filter.PID > 0 {
		conds = append(conds, "pid = ?")
		args = append(args, filter.PID)
	}
	if filter.StartTimeMs > 0 {
		conds = append(conds, "start_time_ms = ?")
		args = append(args, filter.StartTimeMs)
	}
	args = append(args, limit)
	query := `
SELECT timestamp, pid, parent_pid, process_type, COALESCE(name, ''), command_line,
	working_directory, project_path, rss_memory_mb, virtual_memory_mb, swap_memory_mb, resident_bytes, virtual_bytes,
	private_committed_bytes, proportional_bytes, physical_footprint_bytes, swap_bytes, peak_resident_bytes,
	proportional_sampled_at_ms, thread_count,
       read_bytes, write_bytes, read_operations, write_operations, minor_page_faults, major_page_faults,
       voluntary_context_switches, involuntary_context_switches, open_file_descriptors, cpu_percent,
       cpu_user_seconds_total, cpu_system_seconds_total, cpu_total_seconds_total, cpu_cores_used, logical_processor_count,
       max_heap_mb, min_heap_mb, gc, heap_used_mb, heap_committed_mb, heap_max_mb, heap_sampled_at_ms,
       heap_available, metaspace_used_mb, metaspace_committed_mb, young_gc_count, young_gc_time_ms,
       old_gc_count, old_gc_time_ms, full_gc_count, concurrent_gc_count, total_gc_count, java_version, java_runtime_version, java_vendor,
       java_vm_name, java_vm_version, os_name, os_arch, active_processor_count,
       start_time_ms, status, automated, owner_pid, owner_start_time_ms, jvm_sampled_at_ms,
       jit_present, jit_compiled_count, jit_failed_count, jit_invalid_count, jit_compilation_time_seconds, classes_present, classes_loaded, classes_unloaded_total, classes_loaded_total, classes_time_seconds, young_gc_time_seconds, full_gc_time_seconds, concurrent_gc_time_seconds, total_gc_time_seconds
FROM process_samples
WHERE ` + strings.Join(conds, " AND ") + `
ORDER BY timestamp DESC, rowid DESC
LIMIT ?`
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]model.Process, 0, 64)
	for rows.Next() {
		var p model.Process
		var automated int
		var heapAvailable sql.NullBool
		var jitPresent, classesPresent sql.NullBool
		var jit model.JITMetrics
		var classes model.ClassMetrics
		var ownerPID, ownerStart sql.NullInt64
		var timesJSON sql.NullString
		if err := rows.Scan(
			&p.SampledAtMs, &p.PID, &p.ParentPID, &p.Type, &p.Name, &p.CommandLine,
			&p.WorkingDirectory, &p.ProjectPath, &p.RSSMemoryMB, &p.VirtualMemoryMB, &p.SwapMemoryMB, &p.ResidentBytes,
			&p.VirtualBytes, &p.PrivateCommittedBytes, &p.ProportionalBytes, &p.PhysicalFootprintBytes, &p.SwapBytes,
			&p.PeakResidentBytes, &p.ProportionalSampledAtMs, &p.ThreadCount,
			&p.ReadBytes, &p.WriteBytes, &p.ReadOperations, &p.WriteOperations, &p.MinorPageFaults, &p.MajorPageFaults,
			&p.VoluntaryContextSwitches, &p.InvoluntaryContextSwitches, &p.OpenFileDescriptors, &p.CPUPercent,
			&p.CPUUserSecondsTotal, &p.CPUSystemSecondsTotal, &p.CPUTotalSecondsTotal, &p.CPUCoresUsed, &p.LogicalProcessorCount,
			&p.MaxHeapMB, &p.MinHeapMB, &p.GC, &p.HeapUsedMB, &p.HeapCommittedMB, &p.HeapMaxMB, &p.HeapSampledAtMs,
			&heapAvailable, &p.MetaspaceUsedMB, &p.MetaspaceCommittedMB, &p.YoungGCCount, &p.YoungGCTimeMs,
			&p.OldGCCount, &p.OldGCTimeMs, &p.FullGCCount, &p.ConcurrentGCCount, &p.TotalGCCount, &p.JavaVersion, &p.JavaRuntimeVersion, &p.JavaVendor,
			&p.JavaVMName, &p.JavaVMVersion, &p.OSName, &p.OSArch, &p.ActiveProcessorCount,
			&p.StartTimeMs, &p.Status, &automated, &ownerPID, &ownerStart, &timesJSON,
			&jitPresent, &jit.CompiledCount, &jit.FailedCount, &jit.InvalidCount, &jit.CompilationTimeSeconds,
			&classesPresent, &classes.LoadedClasses, &classes.UnloadedClasses, &classes.LoadedClassesTotal, &classes.ClassTimeSeconds,
			&p.YoungGCTimeSeconds, &p.FullGCTimeSeconds, &p.ConcurrentGCTimeSeconds, &p.TotalGCTimeSeconds,
		); err != nil {
			return nil, err
		}
		if jitPresent.Bool {
			p.JIT = &jit
		}
		if classesPresent.Bool {
			p.Classes = &classes
		}
		p.Automated = automated != 0
		if timesJSON.Valid {
			if err := json.Unmarshal([]byte(timesJSON.String), &p.JVMMeasurementTimes); err != nil {
				return nil, fmt.Errorf("decode JVM measurement times: %w", err)
			}
		}
		p.HeapAvailable = heapAvailable.Bool
		if ownerPID.Valid {
			p.OwnerPID = int32(ownerPID.Int64)
		}
		if ownerStart.Valid {
			p.OwnerStartTimeMs = ownerStart.Int64
		}
		out = append(out, p)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, nil
}

func (s *Store) Count() (int64, error) {
	var n int64
	err := s.db.QueryRow(`SELECT COUNT(*) FROM process_samples`).Scan(&n)
	return n, err
}

func (s *Store) SamplesInWindow(pid, startMs, endMs int64) ([]struct {
	RSS int64
	CPU *float64
}, error) {
	rows, err := s.db.Query(`
SELECT COALESCE(subtree_rss_memory_mb, rss_memory_mb),
       CASE WHEN subtree_rss_memory_mb IS NOT NULL THEN subtree_cpu_percent ELSE cpu_percent END
FROM process_samples
WHERE pid = ? AND timestamp >= ? AND timestamp <= ?
ORDER BY timestamp ASC`, pid, startMs, endMs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]struct {
		RSS int64
		CPU *float64
	}, 0)
	for rows.Next() {
		var rss int64
		var cpu sql.NullFloat64
		if err := rows.Scan(&rss, &cpu); err != nil {
			return nil, err
		}
		item := struct {
			RSS int64
			CPU *float64
		}{RSS: rss}
		if cpu.Valid {
			v := cpu.Float64
			item.CPU = &v
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

// ProcessSamplesColumns returns process_samples column names (schema parity tests).
func (s *Store) ProcessSamplesColumns() ([]string, error) {
	return s.tableColumns("process_samples")
}

func DefaultDBPath(dir string) string {
	return fmt.Sprintf("%s/daemonitor-core.sqlite", dir)
}
