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
  process_type TEXT NOT NULL,
  name TEXT NOT NULL,
  command_line TEXT NOT NULL,
  rss_memory_mb REAL NOT NULL,
  cpu_percent REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS process_samples_ts ON process_samples(timestamp_ms);
CREATE INDEX IF NOT EXISTS process_samples_pid_ts ON process_samples(pid, timestamp_ms);
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
  timestamp_ms, pid, process_type, name, command_line, rss_memory_mb, cpu_percent
) VALUES (?, ?, ?, ?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()

	for _, p := range snap.Processes {
		if _, err := stmt.Exec(
			snap.SampledAtMs, p.PID, p.Type, p.Name, p.CommandLine, p.RSSMemoryMB, p.CPUPercent,
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
SELECT timestamp_ms, pid, process_type, name, command_line, rss_memory_mb, cpu_percent
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
		if err := rows.Scan(
			&p.SampledAtMs, &p.PID, &p.Type, &p.Name, &p.CommandLine, &p.RSSMemoryMB, &p.CPUPercent,
		); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) Count() (int64, error) {
	var n int64
	err := s.db.QueryRow(`SELECT COUNT(*) FROM process_samples`).Scan(&n)
	return n, err
}

func DefaultDBPath(dir string) string {
	return fmt.Sprintf("%s/daemonitor-core.sqlite", dir)
}
