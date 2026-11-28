package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
)

func (s *Store) ensureBuildObservationSessions() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS build_observation_sessions (
  session_id TEXT PRIMARY KEY NOT NULL,
  ecosystem TEXT NOT NULL DEFAULT '',
  mode TEXT NOT NULL DEFAULT 'explicit',
  root_project_path TEXT NOT NULL DEFAULT '',
  command_line TEXT,
  build_id TEXT,
  ci_job_id TEXT,
  host_id TEXT NOT NULL DEFAULT '',
  anchor_pid INTEGER NOT NULL DEFAULT 0,
  anchor_start_time_ms INTEGER NOT NULL DEFAULT 0,
  started_at_ms INTEGER NOT NULL,
  ended_at_ms INTEGER,
  outcome TEXT NOT NULL DEFAULT '',
  concurrent_overlap INTEGER NOT NULL DEFAULT 0,
  total_peak_rss_mb INTEGER NOT NULL DEFAULT 0,
  total_peak_footprint_bytes INTEGER,
  total_cpu_seconds REAL,
  max_parallelism INTEGER NOT NULL DEFAULT 0,
  summary_json TEXT
);
CREATE INDEX IF NOT EXISTS build_observation_sessions_started ON build_observation_sessions(started_at_ms);
CREATE INDEX IF NOT EXISTS build_observation_sessions_ecosystem ON build_observation_sessions(ecosystem, started_at_ms);
CREATE TABLE IF NOT EXISTS build_session_process_links (
  session_id TEXT NOT NULL,
  pid INTEGER NOT NULL,
  start_time_ms INTEGER NOT NULL DEFAULT 0,
  process_type TEXT NOT NULL,
  name TEXT,
  association TEXT NOT NULL,
  first_rss_mb INTEGER,
  peak_rss_mb INTEGER,
  last_rss_mb INTEGER,
  peak_footprint_bytes INTEGER,
  cpu_seconds REAL,
  disk_read_bytes INTEGER,
  disk_write_bytes INTEGER,
  sample_count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (session_id, pid, start_time_ms)
);
CREATE INDEX IF NOT EXISTS build_session_process_links_session ON build_session_process_links(session_id);
`)
	return err
}

// SaveBuildSessionOpen persists a newly opened build observation session.
func (s *Store) SaveBuildSessionOpen(sess sessions.BuildSession) error {
	_, err := s.db.Exec(`
INSERT INTO build_observation_sessions(
  session_id, ecosystem, mode, root_project_path, command_line, build_id, ci_job_id,
  host_id, anchor_pid, anchor_start_time_ms, started_at_ms, outcome
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(session_id) DO UPDATE SET
  ecosystem=excluded.ecosystem,
  mode=excluded.mode,
  root_project_path=excluded.root_project_path,
  command_line=excluded.command_line,
  outcome=excluded.outcome`,
		sess.SessionID, sess.Ecosystem, sess.Mode, sess.RootProjectPath, nullStr(sess.CommandLine),
		nullStr(sess.BuildID), nullStr(sess.CIJobID), sess.HostID, sess.AnchorPID, sess.AnchorStartTimeMs,
		sess.StartedAtMs, "running",
	)
	return err
}

// SaveBuildSessionSummary persists a closed build session and its attributed process links atomically.
func (s *Store) SaveBuildSessionSummary(sess sessions.BuildSession, summary sessions.BuildSessionSummary) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	overlap := 0
	if summary.ConcurrentOverlap {
		overlap = 1
	}
	ended := summary.EndedAtMs
	summaryJSON := marshalBuildSummaryJSON(summary)
	if _, err := tx.Exec(`
INSERT INTO build_observation_sessions(
  session_id, ecosystem, mode, root_project_path, command_line, build_id, ci_job_id,
  host_id, anchor_pid, anchor_start_time_ms, started_at_ms, ended_at_ms, outcome,
  concurrent_overlap, total_peak_rss_mb, total_peak_footprint_bytes, total_cpu_seconds,
  max_parallelism, summary_json
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(session_id) DO UPDATE SET
  ecosystem=excluded.ecosystem,
  mode=excluded.mode,
  root_project_path=excluded.root_project_path,
  command_line=excluded.command_line,
  ended_at_ms=excluded.ended_at_ms,
  outcome=excluded.outcome,
  concurrent_overlap=excluded.concurrent_overlap,
  total_peak_rss_mb=excluded.total_peak_rss_mb,
  total_peak_footprint_bytes=excluded.total_peak_footprint_bytes,
  total_cpu_seconds=excluded.total_cpu_seconds,
  max_parallelism=excluded.max_parallelism,
  summary_json=excluded.summary_json`,
		summary.SessionID, summary.Ecosystem, summary.Mode, summary.RootProjectPath, nullStr(summary.CommandLine),
		nullStr(summary.BuildID), nullStr(summary.CIJobID), sess.HostID, sess.AnchorPID, sess.AnchorStartTimeMs,
		summary.StartedAtMs, &ended, summary.Outcome, overlap, summary.TotalPeakRSSMB,
		summary.TotalPeakFootprintBytes, summary.TotalCPUSeconds, summary.MaxParallelism, nullStr(summaryJSON),
	); err != nil {
		return err
	}

	if _, err := tx.Exec(`DELETE FROM build_session_process_links WHERE session_id = ?`, summary.SessionID); err != nil {
		return err
	}
	if len(summary.Processes) > 0 {
		stmt, err := tx.Prepare(`
INSERT INTO build_session_process_links(
  session_id, pid, start_time_ms, process_type, name, association,
  first_rss_mb, peak_rss_mb, last_rss_mb, peak_footprint_bytes,
  cpu_seconds, disk_read_bytes, disk_write_bytes, sample_count
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
		if err != nil {
			return err
		}
		defer stmt.Close()
		for _, p := range summary.Processes {
			if _, err := stmt.Exec(
				summary.SessionID, p.PID, p.StartTimeMs, p.Type, nullStr(p.Name), p.Association,
				p.FirstRSSMB, p.PeakRSSMB, p.LastRSSMB, p.PeakFootprintBytes,
				p.CPUSeconds, p.DiskReadBytes, p.DiskWriteBytes, p.SampleCount,
			); err != nil {
				return err
			}
		}
	}
	return tx.Commit()
}

// ListBuildSessions returns persisted build observation sessions newest-first.
func (s *Store) ListBuildSessions(ecosystem string, limit int) ([]sessions.BuildSessionSummary, error) {
	if limit <= 0 || limit > 500 {
		limit = 50
	}
	query := `
SELECT session_id, ecosystem, mode, root_project_path, COALESCE(command_line, ''),
       COALESCE(build_id, ''), COALESCE(ci_job_id, ''), started_at_ms, ended_at_ms,
       outcome, concurrent_overlap, total_peak_rss_mb, total_peak_footprint_bytes,
       total_cpu_seconds, max_parallelism, COALESCE(summary_json, '')
FROM build_observation_sessions`
	var args []any
	if strings.TrimSpace(ecosystem) != "" {
		query += ` WHERE LOWER(ecosystem) = ?`
		args = append(args, strings.ToLower(strings.TrimSpace(ecosystem)))
	}
	query += ` ORDER BY started_at_ms DESC LIMIT ?`
	args = append(args, limit)
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]sessions.BuildSessionSummary, 0, 16)
	for rows.Next() {
		sum, err := scanBuildSessionSummary(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, sum)
	}
	return out, rows.Err()
}

// GetBuildSession returns one persisted build observation session by ID, or nil when not found.
func (s *Store) GetBuildSession(sessionID string) (*sessions.BuildSessionSummary, error) {
	row := s.db.QueryRow(`
SELECT session_id, ecosystem, mode, root_project_path, COALESCE(command_line, ''),
       COALESCE(build_id, ''), COALESCE(ci_job_id, ''), started_at_ms, ended_at_ms,
       outcome, concurrent_overlap, total_peak_rss_mb, total_peak_footprint_bytes,
       total_cpu_seconds, max_parallelism, COALESCE(summary_json, '')
FROM build_observation_sessions
WHERE session_id = ?`, sessionID)
	sum, err := scanBuildSessionSummary(row)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &sum, nil
}

type buildSessionScanner interface {
	Scan(dest ...any) error
}

func scanBuildSessionSummary(sc buildSessionScanner) (sessions.BuildSessionSummary, error) {
	var (
		sum         sessions.BuildSessionSummary
		ended       sql.NullInt64
		overlap     int
		peakFP      sql.NullInt64
		totalCPU    sql.NullFloat64
		summaryJSON string
	)
	if err := sc.Scan(
		&sum.SessionID, &sum.Ecosystem, &sum.Mode, &sum.RootProjectPath, &sum.CommandLine,
		&sum.BuildID, &sum.CIJobID, &sum.StartedAtMs, &ended,
		&sum.Outcome, &overlap, &sum.TotalPeakRSSMB, &peakFP,
		&totalCPU, &sum.MaxParallelism, &summaryJSON,
	); err != nil {
		return sessions.BuildSessionSummary{}, err
	}
	if strings.TrimSpace(summaryJSON) != "" {
		var decoded sessions.BuildSessionSummary
		if err := json.Unmarshal([]byte(summaryJSON), &decoded); err == nil && decoded.SessionID != "" {
			return decoded, nil
		}
	}
	sum.SchemaVersion = "daemonitor.observation.v1"
	sum.ConcurrentOverlap = overlap != 0
	if ended.Valid {
		sum.EndedAtMs = ended.Int64
	}
	if peakFP.Valid {
		v := peakFP.Int64
		sum.TotalPeakFootprintBytes = &v
	}
	if totalCPU.Valid {
		v := totalCPU.Float64
		sum.TotalCPUSeconds = &v
	}
	sum.Processes = []sessions.BuildSessionProcessSummary{}
	return sum, nil
}

// SamplesInWindowDetailed returns full process samples inside [startMs, endMs]
// ordered chronologically for incarnation-scoped build session attribution and rollups.
func (s *Store) SamplesInWindowDetailed(startMs, endMs int64) ([]model.Process, error) {
	rows, err := s.db.Query(`
SELECT ps.timestamp, ps.pid, ps.parent_pid, ps.process_type, COALESCE(ps.name, ''),
       COALESCE((SELECT command_line FROM process_command_lines WHERE id = ps.command_line_id), NULLIF(ps.command_line, ''), pi.command_line, ''),
       ps.working_directory, ps.project_path, ps.rss_memory_mb, ps.physical_footprint_bytes,
       ps.peak_resident_bytes, ps.cpu_percent, ps.cpu_user_seconds_total, ps.cpu_system_seconds_total,
       ps.cpu_total_seconds_total, ps.disk_read_bytes, ps.disk_write_bytes, ps.read_bytes, ps.write_bytes,
       ps.start_time_ms, ps.status
FROM process_samples ps
LEFT JOIN process_instances pi
  ON pi.host_id = ps.host_id AND pi.pid = ps.pid AND pi.create_time_ms = ps.start_time_ms AND ps.start_time_ms > 0
WHERE ps.timestamp >= ? AND ps.timestamp <= ?
ORDER BY ps.timestamp ASC, ps.id ASC`, startMs, endMs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]model.Process, 0, 64)
	for rows.Next() {
		var p model.Process
		if err := rows.Scan(
			&p.SampledAtMs, &p.PID, &p.ParentPID, &p.Type, &p.Name, &p.CommandLine,
			&p.WorkingDirectory, &p.ProjectPath, &p.RSSMemoryMB, &p.PhysicalFootprintBytes,
			&p.PeakResidentBytes, &p.CPUPercent, &p.CPUUserSecondsTotal, &p.CPUSystemSecondsTotal,
			&p.CPUTotalSecondsTotal, &p.DiskReadBytes, &p.DiskWriteBytes, &p.ReadBytes, &p.WriteBytes,
			&p.StartTimeMs, &p.Status,
		); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func marshalBuildSummaryJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return fmt.Sprintf(`{"error":%q}`, err.Error())
	}
	return string(b)
}
