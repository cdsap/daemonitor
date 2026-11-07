package store

import (
	"database/sql"
	"fmt"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

// Lifecycle event types persisted in process_events.
const (
	EventFirstObserved           = "process.first_observed"
	EventDisappearanceDetected   = "process.disappearance_detected"
	EventConfigurationObserved   = "process.configuration_observed"
	EventSourceProcessPoll       = "process_poll"
	ProcessEventSchemaVersion    = 1
)

// ProcessInstanceID builds a durable incarnation key from host + PID + OS create time.
func ProcessInstanceID(hostID string, pid int32, createTimeMs int64) string {
	return fmt.Sprintf("%s:%d:%d", hostID, pid, createTimeMs)
}

func (s *Store) ensureProcessInstancesAndEvents() error {
	if _, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS process_instances (
  id TEXT PRIMARY KEY NOT NULL,
  host_id TEXT NOT NULL DEFAULT '',
  pid INTEGER NOT NULL,
  create_time_ms INTEGER NOT NULL,
  process_type TEXT NOT NULL,
  name TEXT,
  command_line TEXT NOT NULL DEFAULT '',
  first_observed_ms INTEGER NOT NULL,
  last_observed_ms INTEGER NOT NULL,
  disappeared_at_ms INTEGER,
  UNIQUE(host_id, pid, create_time_ms)
)`); err != nil {
		return err
	}
	if _, err := s.db.Exec(`CREATE INDEX IF NOT EXISTS process_instances_last_obs ON process_instances(last_observed_ms)`); err != nil {
		return err
	}
	if _, err := s.db.Exec(`CREATE INDEX IF NOT EXISTS process_instances_alive ON process_instances(disappeared_at_ms)`); err != nil {
		return err
	}
	if _, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS process_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  schema_version INTEGER NOT NULL DEFAULT 1,
  event_type TEXT NOT NULL,
  timestamp_ms INTEGER NOT NULL,
  process_instance_id TEXT NOT NULL,
  pid INTEGER NOT NULL,
  process_type TEXT,
  source TEXT NOT NULL DEFAULT 'process_poll',
  metadata_json TEXT
)`); err != nil {
		return err
	}
	if _, err := s.db.Exec(`CREATE INDEX IF NOT EXISTS process_events_ts ON process_events(timestamp_ms)`); err != nil {
		return err
	}
	if _, err := s.db.Exec(`CREATE INDEX IF NOT EXISTS process_events_instance_ts ON process_events(process_instance_id, timestamp_ms)`); err != nil {
		return err
	}
	return nil
}

// ObserveProcesses upserts process instances, updates last_observed, and emits lifecycle events.
// It never fabricates exit codes or exact exit timestamps for processes Daemonitor does not own.
func (s *Store) ObserveProcesses(hostID string, sampledAtMs int64, processes []model.Process) error {
	if sampledAtMs <= 0 {
		sampledAtMs = time.Now().UnixMilli()
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	aliveRows, err := tx.Query(`
SELECT id, process_type, command_line
FROM process_instances
WHERE disappeared_at_ms IS NULL`)
	if err != nil {
		return err
	}
	type aliveRow struct {
		id, processType, commandLine string
	}
	alive := make(map[string]aliveRow)
	for aliveRows.Next() {
		var row aliveRow
		if err := aliveRows.Scan(&row.id, &row.processType, &row.commandLine); err != nil {
			aliveRows.Close()
			return err
		}
		alive[row.id] = row
	}
	if err := aliveRows.Err(); err != nil {
		aliveRows.Close()
		return err
	}
	aliveRows.Close()

	seen := make(map[string]struct{}, len(processes))
	for _, p := range processes {
		// Without a real OS create time, PID reuse cannot be distinguished — skip identity.
		if p.StartTimeMs <= 0 {
			continue
		}
		id := ProcessInstanceID(hostID, p.PID, p.StartTimeMs)
		seen[id] = struct{}{}
		existing, wasAlive := alive[id]
		if wasAlive {
			if _, err := tx.Exec(`
UPDATE process_instances
SET process_type=?, name=?, command_line=?, last_observed_ms=?
WHERE id=?`, p.Type, nullStr(p.Name), p.CommandLine, sampledAtMs, id); err != nil {
				return err
			}
			if existing.processType != p.Type || existing.commandLine != p.CommandLine {
				if err := insertProcessEvent(tx, EventConfigurationObserved, sampledAtMs, id, p.PID, p.Type); err != nil {
					return err
				}
			}
			continue
		}

		var disappearedAt sql.NullInt64
		err := tx.QueryRow(`SELECT disappeared_at_ms FROM process_instances WHERE id=?`, id).Scan(&disappearedAt)
		switch {
		case err == sql.ErrNoRows:
			if _, err := tx.Exec(`
INSERT INTO process_instances(
  id, host_id, pid, create_time_ms, process_type, name, command_line,
  first_observed_ms, last_observed_ms, disappeared_at_ms
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
				id, hostID, p.PID, p.StartTimeMs, p.Type, nullStr(p.Name), p.CommandLine, sampledAtMs, sampledAtMs); err != nil {
				return err
			}
			if err := insertProcessEvent(tx, EventFirstObserved, sampledAtMs, id, p.PID, p.Type); err != nil {
				return err
			}
		case err != nil:
			return err
		default:
			// Same incarnation returned after a transient disappearance — clear the
			// flag but do not re-emit first_observed.
			var oldType, oldCmd string
			if err := tx.QueryRow(
				`SELECT process_type, command_line FROM process_instances WHERE id=?`, id,
			).Scan(&oldType, &oldCmd); err != nil {
				return err
			}
			if _, err := tx.Exec(`
UPDATE process_instances
SET process_type=?, name=?, command_line=?, last_observed_ms=?, disappeared_at_ms=NULL
WHERE id=?`, p.Type, nullStr(p.Name), p.CommandLine, sampledAtMs, id); err != nil {
				return err
			}
			if oldType != p.Type || oldCmd != p.CommandLine {
				if err := insertProcessEvent(tx, EventConfigurationObserved, sampledAtMs, id, p.PID, p.Type); err != nil {
					return err
				}
			}
		}
	}

	for id := range alive {
		if _, ok := seen[id]; ok {
			continue
		}
		var pid int32
		var processType string
		if err := tx.QueryRow(`SELECT pid, process_type FROM process_instances WHERE id=?`, id).Scan(&pid, &processType); err != nil {
			return err
		}
		if _, err := tx.Exec(`
UPDATE process_instances
SET disappeared_at_ms=?
WHERE id=? AND disappeared_at_ms IS NULL`, sampledAtMs, id); err != nil {
			return err
		}
		if err := insertProcessEvent(tx, EventDisappearanceDetected, sampledAtMs, id, pid, processType); err != nil {
			return err
		}
	}

	return tx.Commit()
}

func insertProcessEvent(tx *sql.Tx, eventType string, timestampMs int64, instanceID string, pid int32, processType string) error {
	_, err := tx.Exec(`
INSERT INTO process_events(
  schema_version, event_type, timestamp_ms, process_instance_id, pid, process_type, source
) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		ProcessEventSchemaVersion, eventType, timestampMs, instanceID, pid, nullStr(processType), EventSourceProcessPoll,
	)
	return err
}

// ListEvents returns lifecycle events at or after sinceMs, oldest first.
func (s *Store) ListEvents(sinceMs int64, limit int) ([]model.ProcessEvent, error) {
	if limit <= 0 || limit > 5000 {
		limit = 500
	}
	rows, err := s.db.Query(`
SELECT schema_version, event_type, timestamp_ms, process_instance_id, pid, COALESCE(process_type, ''), source
FROM process_events
WHERE timestamp_ms >= ?
ORDER BY timestamp_ms ASC, id ASC
LIMIT ?`, sinceMs, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]model.ProcessEvent, 0, 32)
	for rows.Next() {
		var ev model.ProcessEvent
		if err := rows.Scan(
			&ev.SchemaVersion, &ev.EventType, &ev.TimestampMs,
			&ev.ProcessInstanceID, &ev.PID, &ev.ProcessType, &ev.Source,
		); err != nil {
			return nil, err
		}
		out = append(out, ev)
	}
	return out, rows.Err()
}

// CountInstances returns how many process_instances rows exist.
func (s *Store) CountInstances() (int64, error) {
	var n int64
	err := s.db.QueryRow(`SELECT COUNT(*) FROM process_instances`).Scan(&n)
	return n, err
}

// CountEvents returns how many process_events rows exist.
func (s *Store) CountEvents() (int64, error) {
	var n int64
	err := s.db.QueryRow(`SELECT COUNT(*) FROM process_events`).Scan(&n)
	return n, err
}
