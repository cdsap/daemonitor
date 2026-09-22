package store

import (
	"database/sql"
	"fmt"
	"time"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/model"
	_ "modernc.org/sqlite"
)

// Store persists process samples with a simple retention window.
type Store struct {
	db *sql.DB
}

func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

func (s *Store) migrate() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS process_samples (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp_ms INTEGER NOT NULL,
  pid INTEGER NOT NULL,
  parent_pid INTEGER NOT NULL DEFAULT 0,
  process_type TEXT NOT NULL,
  name TEXT NOT NULL,
  command_line TEXT NOT NULL,
  working_directory TEXT,
  project_path TEXT,
  rss_memory_mb INTEGER NOT NULL,
  cpu_percent REAL,
  max_heap_mb INTEGER,
  min_heap_mb INTEGER,
  gc TEXT,
  start_time_ms INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'RUNNING',
  automated INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS process_samples_ts ON process_samples(timestamp_ms);
CREATE INDEX IF NOT EXISTS process_samples_pid_ts ON process_samples(pid, timestamp_ms);
CREATE TABLE IF NOT EXISTS builds (
  build_id TEXT PRIMARY KEY,
  daemon_pid INTEGER NOT NULL,
  daemon_identity TEXT,
  working_directory TEXT,
  project_path TEXT,
  start_time_ms INTEGER NOT NULL,
  end_time_ms INTEGER,
  duration_seconds REAL,
  peak_memory_mb INTEGER,
  avg_memory_mb INTEGER,
  peak_cpu_percent REAL,
  inferred_source TEXT NOT NULL,
  final_status TEXT NOT NULL,
  log_snippet TEXT,
  agent TEXT,
  agent_provider TEXT
);
CREATE INDEX IF NOT EXISTS builds_start ON builds(start_time_ms);
`)
	return err
}

func (s *Store) InsertSnapshot(snap model.Snapshot) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	stmt, err := tx.Prepare(`
INSERT INTO process_samples(
  timestamp_ms, pid, parent_pid, process_type, name, command_line,
  working_directory, project_path, rss_memory_mb, cpu_percent,
  max_heap_mb, min_heap_mb, gc, start_time_ms, status, automated
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()

	for _, p := range snap.Processes {
		automated := 0
		if p.Automated {
			automated = 1
		}
		if _, err := stmt.Exec(
			snap.SampledAtMs, p.PID, p.ParentPID, p.Type, p.Name, p.CommandLine,
			p.WorkingDirectory, p.ProjectPath, p.RSSMemoryMB, p.CPUPercent,
			p.MaxHeapMB, p.MinHeapMB, p.GC, p.StartTimeMs, p.Status, automated,
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) PurgeOlderThan(retention time.Duration) (int64, error) {
	cutoff := time.Now().Add(-retention).UnixMilli()
	res, err := s.db.Exec(`DELETE FROM process_samples WHERE timestamp_ms < ?`, cutoff)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

func (s *Store) History(sinceMs int64, limit int) ([]model.Process, error) {
	if limit <= 0 || limit > 5000 {
		limit = 500
	}
	rows, err := s.db.Query(`
SELECT timestamp_ms, pid, parent_pid, process_type, name, command_line,
       working_directory, project_path, rss_memory_mb, cpu_percent,
       max_heap_mb, min_heap_mb, gc, start_time_ms, status, automated
FROM process_samples
WHERE timestamp_ms >= ?
ORDER BY timestamp_ms ASC
LIMIT ?`, sinceMs, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]model.Process, 0, 64)
	for rows.Next() {
		var p model.Process
		var automated int
		if err := rows.Scan(
			&p.SampledAtMs, &p.PID, &p.ParentPID, &p.Type, &p.Name, &p.CommandLine,
			&p.WorkingDirectory, &p.ProjectPath, &p.RSSMemoryMB, &p.CPUPercent,
			&p.MaxHeapMB, &p.MinHeapMB, &p.GC, &p.StartTimeMs, &p.Status, &automated,
		); err != nil {
			return nil, err
		}
		p.Automated = automated != 0
		out = append(out, p)
	}
	return out, rows.Err()
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
SELECT rss_memory_mb, cpu_percent
FROM process_samples
WHERE pid = ? AND timestamp_ms >= ? AND timestamp_ms <= ?
ORDER BY timestamp_ms ASC`, pid, startMs, endMs)
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

func DefaultDBPath(dir string) string {
	return fmt.Sprintf("%s/daemonitor-core.sqlite", dir)
}
