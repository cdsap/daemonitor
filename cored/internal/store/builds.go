package store

import (
	"database/sql"

	"github.com/cdsap/daemonitor/cored/internal/builds"
)

// InsertBuild upserts a confirmed build record into the app-aligned builds table.
func (s *Store) InsertBuild(b builds.Build) error {
	var commandLine any
	if b.CommandLine != nil {
		commandLine = nullStr(*b.CommandLine)
	}
	_, err := s.db.Exec(`
INSERT INTO builds(
  build_id, daemon_pid, daemon_identity, command_line, working_directory, project_path,
  start_time, end_time, duration_seconds, peak_memory_mb, avg_memory_mb,
  peak_cpu_percent, inferred_source, final_status, log_snippet, agent, agent_provider
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(build_id) DO UPDATE SET
  command_line=excluded.command_line,
  end_time=excluded.end_time,
  duration_seconds=excluded.duration_seconds,
  peak_memory_mb=excluded.peak_memory_mb,
  avg_memory_mb=excluded.avg_memory_mb,
  peak_cpu_percent=excluded.peak_cpu_percent,
  final_status=excluded.final_status,
  log_snippet=excluded.log_snippet,
  agent=excluded.agent,
  agent_provider=excluded.agent_provider
`,
		b.BuildID, b.DaemonPID, nullStr(b.DaemonIdentity), commandLine,
		nullStr(b.WorkingDirectory), nullStr(b.ProjectPath),
		b.StartTimeMs, b.EndTimeMs, b.DurationSeconds, b.PeakMemoryMB, b.AvgMemoryMB,
		b.PeakCPUPercent, string(b.InferredSource), string(b.FinalStatus), nullStr(b.LogSnippet),
		nullStr(b.Agent), nullStr(b.AgentProvider),
	)
	return err
}

// ListBuilds returns recent builds newest-first.
func (s *Store) ListBuilds(limit int) ([]builds.Build, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.db.Query(`
SELECT build_id, daemon_pid, daemon_identity, command_line, working_directory, project_path,
       start_time, end_time, duration_seconds, peak_memory_mb, avg_memory_mb,
       peak_cpu_percent, inferred_source, final_status, log_snippet, agent, agent_provider
FROM builds
ORDER BY start_time DESC
LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]builds.Build, 0)
	for rows.Next() {
		var b builds.Build
		var identity, workDir, project, snippet, agent, provider, cmd sql.NullString
		var endMs sql.NullInt64
		var dur, peakCPU sql.NullFloat64
		var peakMem, avgMem sql.NullInt64
		if err := rows.Scan(
			&b.BuildID, &b.DaemonPID, &identity, &cmd, &workDir, &project,
			&b.StartTimeMs, &endMs, &dur, &peakMem, &avgMem,
			&peakCPU, &b.InferredSource, &b.FinalStatus, &snippet, &agent, &provider,
		); err != nil {
			return nil, err
		}
		b.DaemonIdentity = identity.String
		b.WorkingDirectory = workDir.String
		b.ProjectPath = project.String
		b.LogSnippet = snippet.String
		b.Agent = agent.String
		b.AgentProvider = provider.String
		if cmd.Valid {
			v := cmd.String
			b.CommandLine = &v
		}
		if endMs.Valid {
			v := endMs.Int64
			b.EndTimeMs = &v
		}
		if dur.Valid {
			v := dur.Float64
			b.DurationSeconds = &v
		}
		if peakMem.Valid {
			v := peakMem.Int64
			b.PeakMemoryMB = &v
		}
		if avgMem.Valid {
			v := avgMem.Int64
			b.AvgMemoryMB = &v
		}
		if peakCPU.Valid {
			v := peakCPU.Float64
			b.PeakCPUPercent = &v
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

// SamplesAsBuildSamples maps DB rows into builds.Sample for the aggregator.
func (s *Store) SamplesAsBuildSamples(pid, startMs, endMs int64) []builds.Sample {
	rows, err := s.SamplesInWindow(pid, startMs, endMs)
	if err != nil {
		return nil
	}
	out := make([]builds.Sample, 0, len(rows))
	for _, r := range rows {
		out = append(out, builds.Sample{RSSMemoryMB: r.RSS, CPUPercent: r.CPU})
	}
	return out
}

// BuildsColumns returns the builds table column names (for schema parity tests).
func (s *Store) BuildsColumns() ([]string, error) {
	return s.tableColumns("builds")
}

func nullStr(s string) any {
	if s == "" {
		return nil
	}
	return s
}
