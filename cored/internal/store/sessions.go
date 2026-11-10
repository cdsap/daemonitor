package store

import (
	"database/sql"
	"encoding/json"
	"fmt"

	"github.com/cdsap/daemonitor/cored/internal/sessions"
)

func (s *Store) ensureObservationSessions() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS observation_sessions (
  name TEXT PRIMARY KEY NOT NULL,
  host_id TEXT NOT NULL,
  association TEXT NOT NULL,
  opened_at_ms INTEGER NOT NULL,
  closed_at_ms INTEGER,
  response_json TEXT
);
CREATE TABLE IF NOT EXISTS observation_session_processes (
  session_name TEXT NOT NULL,
  host_id TEXT NOT NULL,
  pid INTEGER NOT NULL,
  start_time_ms INTEGER NOT NULL,
  PRIMARY KEY(session_name, host_id, pid, start_time_ms),
  FOREIGN KEY(session_name) REFERENCES observation_sessions(name)
);
CREATE TABLE IF NOT EXISTS observation_session_process_summaries (
  session_name TEXT NOT NULL,
  host_id TEXT NOT NULL,
  pid INTEGER NOT NULL,
  start_time_ms INTEGER NOT NULL,
  sample_count INTEGER NOT NULL,
  first_rss_mb INTEGER NOT NULL,
  peak_rss_mb INTEGER NOT NULL,
  last_rss_mb INTEGER NOT NULL,
  delta_rss_mb INTEGER NOT NULL,
  association TEXT NOT NULL,
  PRIMARY KEY(session_name, host_id, pid, start_time_ms),
  FOREIGN KEY(session_name) REFERENCES observation_sessions(name)
);
`)
	return err
}

func (s *Store) OpenObservationSession(req sessions.OpenRequest, openedAtMs int64) (sessions.Response, bool, error) {
	var responseJSON sql.NullString
	var existingHost, existingAssociation string
	var existingOpenedAt int64
	var existing sessions.Response
	err := s.db.QueryRow(`SELECT host_id, association, opened_at_ms, response_json FROM observation_sessions WHERE name=?`, req.Name).Scan(&existingHost, &existingAssociation, &existingOpenedAt, &responseJSON)
	if err == nil {
		if responseJSON.Valid {
			if err := json.Unmarshal([]byte(responseJSON.String), &existing); err != nil {
				return sessions.Response{}, true, fmt.Errorf("decode session %q: %w", req.Name, err)
			}
			return existing, true, nil
		}
		return sessions.Response{Name: req.Name, HostID: existingHost, Association: existingAssociation, OpenedAtMs: existingOpenedAt, Processes: []sessions.RSSSummary{}, Builds: sessions.BuildTotals{}}, true, nil
	}
	if err != sql.ErrNoRows {
		return sessions.Response{}, false, err
	}
	if _, err := s.db.Exec(`INSERT INTO observation_sessions(name, host_id, association, opened_at_ms) VALUES (?, ?, ?, ?)`, req.Name, req.HostID, req.Association, openedAtMs); err != nil {
		return sessions.Response{}, false, err
	}
	for _, ref := range req.Processes {
		if _, err := s.db.Exec(`INSERT INTO observation_session_processes(session_name, host_id, pid, start_time_ms) VALUES (?, ?, ?, ?)`, req.Name, ref.HostID, ref.PID, ref.StartTimeMs); err != nil {
			return sessions.Response{}, false, err
		}
	}
	return sessions.Response{Name: req.Name, Association: req.Association, HostID: req.HostID, OpenedAtMs: openedAtMs, Processes: []sessions.RSSSummary{}, Builds: sessions.BuildTotals{}}, false, nil
}

func (s *Store) CloseObservationSession(name string, closedAtMs int64) (sessions.Response, error) {
	var responseJSON sql.NullString
	var hostID, association string
	var openedAt int64
	if err := s.db.QueryRow(`SELECT host_id, association, opened_at_ms, response_json FROM observation_sessions WHERE name=?`, name).Scan(&hostID, &association, &openedAt, &responseJSON); err != nil {
		return sessions.Response{}, err
	}
	if responseJSON.Valid {
		var response sessions.Response
		if err := json.Unmarshal([]byte(responseJSON.String), &response); err != nil {
			return sessions.Response{}, fmt.Errorf("decode session %q: %w", name, err)
		}
		return response, nil
	}
	rows, err := s.db.Query(`SELECT host_id, pid, start_time_ms FROM observation_session_processes WHERE session_name=? ORDER BY host_id, pid, start_time_ms`, name)
	if err != nil {
		return sessions.Response{}, err
	}
	defer rows.Close()
	response := sessions.Response{Name: name, HostID: hostID, Association: association, OpenedAtMs: openedAt, Processes: []sessions.RSSSummary{}, Builds: sessions.BuildTotals{}}
	refs := make([]sessions.ProcessRef, 0)
	for rows.Next() {
		var ref sessions.ProcessRef
		if err := rows.Scan(&ref.HostID, &ref.PID, &ref.StartTimeMs); err != nil {
			return sessions.Response{}, err
		}
		refs = append(refs, ref)
	}
	if err := rows.Err(); err != nil {
		return sessions.Response{}, err
	}
	if err := rows.Close(); err != nil {
		return sessions.Response{}, err
	}
	for _, ref := range refs {
		samples, err := s.sessionSamples(ref, openedAt, closedAtMs)
		if err != nil {
			return sessions.Response{}, err
		}
		if summary, ok := sessions.SummarizeRSS(ref, association, samples); ok {
			response.Processes = append(response.Processes, summary)
		}
	}
	var total, success, failure int
	if err := s.db.QueryRow(`SELECT COUNT(*), COALESCE(SUM(CASE WHEN final_status='SUCCESS' THEN 1 ELSE 0 END), 0), COALESCE(SUM(CASE WHEN final_status='FAILED' THEN 1 ELSE 0 END), 0) FROM builds WHERE start_time >= ? AND start_time <= ?`, openedAt, closedAtMs).Scan(&total, &success, &failure); err != nil {
		return sessions.Response{}, err
	}
	response.Builds = sessions.BuildTotals{Count: total, SuccessCount: success, FailureCount: failure}
	response.ClosedAtMs = &closedAtMs
	for _, summary := range response.Processes {
		if _, err := s.db.Exec(`INSERT INTO observation_session_process_summaries(session_name, host_id, pid, start_time_ms, sample_count, first_rss_mb, peak_rss_mb, last_rss_mb, delta_rss_mb, association) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, name, summary.HostID, summary.PID, summary.StartTimeMs, summary.SampleCount, summary.FirstRSSMB, summary.PeakRSSMB, summary.LastRSSMB, summary.DeltaRSSMB, summary.Association); err != nil {
			return sessions.Response{}, err
		}
	}
	encoded, err := json.Marshal(response)
	if err != nil {
		return sessions.Response{}, err
	}
	if _, err := s.db.Exec(`UPDATE observation_sessions SET closed_at_ms=?, response_json=? WHERE name=? AND response_json IS NULL`, closedAtMs, string(encoded), name); err != nil {
		return sessions.Response{}, err
	}
	return response, nil
}

func (s *Store) sessionSamples(ref sessions.ProcessRef, fromMs, toMs int64) ([]sessions.Sample, error) {
	rows, err := s.db.Query(`SELECT timestamp, rss_memory_mb FROM process_samples WHERE host_id=? AND pid=? AND start_time_ms=? AND timestamp >= ? AND timestamp <= ? ORDER BY timestamp ASC`, ref.HostID, ref.PID, ref.StartTimeMs, fromMs, toMs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]sessions.Sample, 0)
	for rows.Next() {
		var sample sessions.Sample
		if err := rows.Scan(&sample.AtMs, &sample.RSS); err != nil {
			return nil, err
		}
		result = append(result, sample)
	}
	return result, rows.Err()
}
