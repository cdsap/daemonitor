package store

import (
	"database/sql"
	"encoding/json"
	"strings"

	"github.com/cdsap/daemonitor/cored/internal/builds"
)

// BuildFilters are applied by the core before rows cross the IPC boundary.
type BuildFilters struct {
	PID               int64
	DaemonIdentity    string
	StartTimeMs       int64
	DaemonStartTimeMs int64
	Project           string
	Status            string
	Tool              string
}

type BuildSummary struct {
	Count                  int      `json:"count"`
	FailureCount           int      `json:"failure_count"`
	AverageDurationSeconds *float64 `json:"average_duration_seconds"`
	LatestStatus           string   `json:"latest_status"`
}

// InsertBuild upserts a confirmed build record into the app-aligned builds table.
func (s *Store) InsertBuild(b builds.Build) error {
	var commandLine any
	if b.CommandLine != nil {
		commandLine = nullStr(*b.CommandLine)
	}
	tool := string(b.Tool)
	if tool == "" {
		tool = string(builds.ToolGradle)
	}
	var profile any
	if b.BazelProfile != nil {
		data, err := json.Marshal(b.BazelProfile)
		if err != nil {
			return err
		}
		profile = string(data)
	}
	var windowSummaries any
	if len(b.WindowSummaries) > 0 {
		data, err := json.Marshal(b.WindowSummaries)
		if err != nil {
			return err
		}
		windowSummaries = string(data)
	}
	conflict := `ON CONFLICT(build_id) DO UPDATE SET
  command_line=excluded.command_line,
  end_time=excluded.end_time,
  duration_seconds=excluded.duration_seconds,
  peak_memory_mb=excluded.peak_memory_mb,
  avg_memory_mb=excluded.avg_memory_mb,
  peak_cpu_percent=excluded.peak_cpu_percent,
  final_status=excluded.final_status,
  log_snippet=excluded.log_snippet,
  agent=excluded.agent,
  agent_provider=excluded.agent_provider,
  tool=excluded.tool,
  window_summaries=COALESCE(excluded.window_summaries,builds.window_summaries)`
	if b.Tool == builds.ToolBazel {
		// BEP is authoritative, regardless of arrival order. Missing BEP fields
		// retain profile data; a later profile may enrich but cannot downgrade BEP.
		conflict = `ON CONFLICT(build_id) DO UPDATE SET
 bazel_profile=COALESCE(excluded.bazel_profile,builds.bazel_profile),
 daemon_pid=CASE WHEN excluded.daemon_pid>0 AND (excluded.inferred_source='BEP' OR builds.daemon_pid=0) THEN excluded.daemon_pid ELSE builds.daemon_pid END,
 daemon_identity=COALESCE(excluded.daemon_identity,builds.daemon_identity),
 start_time=CASE WHEN excluded.inferred_source='BEP' AND excluded.start_time>0 THEN excluded.start_time ELSE builds.start_time END,
 inferred_source=CASE WHEN builds.inferred_source='BEP' THEN builds.inferred_source ELSE excluded.inferred_source END,
 final_status=CASE WHEN excluded.final_status='COMPLETED_NO_OUTCOME' OR (builds.inferred_source='BEP' AND excluded.inferred_source='PROFILE' AND builds.final_status!='COMPLETED_NO_OUTCOME') THEN builds.final_status ELSE excluded.final_status END,
 tool=excluded.tool`
		for _, col := range []string{"command_line", "working_directory", "project_path", "end_time", "duration_seconds", "peak_memory_mb", "avg_memory_mb", "peak_cpu_percent", "log_snippet", "agent", "agent_provider", "window_summaries"} {
			conflict += ", " + col + "=CASE WHEN builds.inferred_source='BEP' AND excluded.inferred_source='PROFILE' THEN COALESCE(builds." + col + ",excluded." + col + ") ELSE COALESCE(excluded." + col + ",builds." + col + ") END"
		}
	}
	_, err := s.db.Exec(`
INSERT INTO builds(
  build_id, daemon_pid, daemon_identity, command_line, working_directory, project_path,
  start_time, end_time, duration_seconds, peak_memory_mb, avg_memory_mb,
  peak_cpu_percent, inferred_source, final_status, log_snippet, agent, agent_provider, tool, bazel_profile, window_summaries
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
`+conflict+`
`,
		b.BuildID, b.DaemonPID, nullStr(b.DaemonIdentity), commandLine,
		nullStr(b.WorkingDirectory), nullStr(b.ProjectPath),
		b.StartTimeMs, b.EndTimeMs, b.DurationSeconds, b.PeakMemoryMB, b.AvgMemoryMB,
		b.PeakCPUPercent, string(b.InferredSource), string(b.FinalStatus), nullStr(b.LogSnippet),
		nullStr(b.Agent), nullStr(b.AgentProvider), tool, profile, windowSummaries,
	)
	return err
}

// ListBuilds returns recent builds newest-first.
func (s *Store) ListBuilds(limit int) ([]builds.Build, error) {
	return s.ListBuildsFiltered(BuildFilters{}, limit)
}

// ListBuildsForDaemon returns builds correlated to one daemon incarnation.
// PID alone is deliberately insufficient because operating systems can reuse it.
func (s *Store) ListBuildsForDaemon(pid int64, identity string, limit int) ([]builds.Build, error) {
	if pid <= 0 || identity == "" {
		return []builds.Build{}, nil
	}
	return s.ListBuildsFiltered(BuildFilters{PID: pid, DaemonIdentity: identity}, limit)
}

// ListBuildsForDaemonSince returns builds from one daemon lifetime.
func (s *Store) ListBuildsForDaemonSince(pid, daemonStartMs int64, limit int) ([]builds.Build, error) {
	if pid <= 0 || daemonStartMs <= 0 {
		return []builds.Build{}, nil
	}
	return s.ListBuildsFiltered(BuildFilters{PID: pid, DaemonStartTimeMs: daemonStartMs}, limit)
}

// ListBuildsFiltered returns recent builds newest-first, applying filters in SQLite.
func (s *Store) ListBuildsFiltered(filters BuildFilters, limit int) ([]builds.Build, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	query := `
SELECT build_id, daemon_pid, daemon_identity, command_line, working_directory, project_path,
       start_time, end_time, duration_seconds, peak_memory_mb, avg_memory_mb,
       peak_cpu_percent, inferred_source, final_status, log_snippet, agent, agent_provider,
       COALESCE(tool, 'GRADLE'), bazel_profile, window_summaries
FROM builds
	WHERE 1 = 1`
	args := make([]any, 0, 5)
	if filters.PID > 0 {
		query += " AND daemon_pid = ?"
		args = append(args, filters.PID)
	}
	if filters.DaemonIdentity != "" {
		query += " AND daemon_identity = ?"
		args = append(args, filters.DaemonIdentity)
	}
	if filters.DaemonStartTimeMs > 0 {
		query += " AND (start_time >= ? OR (tool = 'BAZEL' AND end_time >= ?))"
		args = append(args, filters.DaemonStartTimeMs, filters.DaemonStartTimeMs)
	}
	if filters.StartTimeMs > 0 {
		query += " AND start_time >= ?"
		args = append(args, filters.StartTimeMs)
	}
	if filters.Project != "" {
		query += " AND LOWER(COALESCE(project_path, '')) LIKE ? ESCAPE '\\'"
		args = append(args, "%"+escapeLike(strings.ToLower(filters.Project))+"%")
	}
	if filters.Status != "" {
		query += " AND LOWER(final_status) = ?"
		args = append(args, strings.ToLower(filters.Status))
	}
	if filters.Tool != "" {
		query += " AND UPPER(COALESCE(tool, 'GRADLE')) = ?"
		args = append(args, strings.ToUpper(filters.Tool))
	}
	query += " ORDER BY start_time DESC LIMIT ?"
	args = append(args, limit)
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]builds.Build, 0)
	for rows.Next() {
		var b builds.Build
		var identity, workDir, project, snippet, agent, provider, cmd, tool, profile, windowSummaries sql.NullString
		var endMs sql.NullInt64
		var dur, peakCPU sql.NullFloat64
		var peakMem, avgMem sql.NullInt64
		if err := rows.Scan(
			&b.BuildID, &b.DaemonPID, &identity, &cmd, &workDir, &project,
			&b.StartTimeMs, &endMs, &dur, &peakMem, &avgMem,
			&peakCPU, &b.InferredSource, &b.FinalStatus, &snippet, &agent, &provider, &tool, &profile, &windowSummaries,
		); err != nil {
			return nil, err
		}
		if profile.Valid {
			if err := json.Unmarshal([]byte(profile.String), &b.BazelProfile); err != nil {
				return nil, err
			}
		}
		if windowSummaries.Valid {
			if err := json.Unmarshal([]byte(windowSummaries.String), &b.WindowSummaries); err != nil {
				return nil, err
			}
		}
		b.DaemonIdentity = identity.String
		b.WorkingDirectory = workDir.String
		b.ProjectPath = project.String
		b.LogSnippet = snippet.String
		b.Agent = agent.String
		b.AgentProvider = provider.String
		if tool.String != "" {
			b.Tool = builds.Tool(tool.String)
		} else {
			b.Tool = builds.ToolGradle
		}
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

func (s *Store) SummarizeBuilds(filters BuildFilters) (BuildSummary, error) {
	where, args := buildFiltersWhere(filters)
	var summary BuildSummary
	var average sql.NullFloat64
	if err := s.db.QueryRow(`SELECT COUNT(*), COALESCE(SUM(CASE WHEN final_status = 'FAILED' THEN 1 ELSE 0 END), 0), AVG(duration_seconds) FROM builds`+where, args...).Scan(&summary.Count, &summary.FailureCount, &average); err != nil {
		return summary, err
	}
	if average.Valid {
		value := average.Float64
		summary.AverageDurationSeconds = &value
	}
	if err := s.db.QueryRow(`SELECT final_status FROM builds`+where+` ORDER BY start_time DESC LIMIT 1`, args...).Scan(&summary.LatestStatus); err != nil && err != sql.ErrNoRows {
		return summary, err
	}
	return summary, nil
}

func buildFiltersWhere(filters BuildFilters) (string, []any) {
	clauses := make([]string, 0, 5)
	args := make([]any, 0, 5)
	if filters.PID > 0 {
		clauses = append(clauses, "daemon_pid = ?")
		args = append(args, filters.PID)
	}
	if filters.DaemonIdentity != "" {
		clauses = append(clauses, "daemon_identity = ?")
		args = append(args, filters.DaemonIdentity)
	}
	if filters.DaemonStartTimeMs > 0 {
		clauses = append(clauses, "(start_time >= ? OR (tool = 'BAZEL' AND end_time >= ?))")
		args = append(args, filters.DaemonStartTimeMs, filters.DaemonStartTimeMs)
	}
	if filters.StartTimeMs > 0 {
		clauses = append(clauses, "start_time >= ?")
		args = append(args, filters.StartTimeMs)
	}
	if filters.Project != "" {
		clauses = append(clauses, "LOWER(COALESCE(project_path, '')) LIKE ? ESCAPE '\\'")
		args = append(args, "%"+escapeLike(strings.ToLower(filters.Project))+"%")
	}
	if filters.Status != "" {
		clauses = append(clauses, "LOWER(final_status) = ?")
		args = append(args, strings.ToLower(filters.Status))
	}
	if filters.Tool != "" {
		clauses = append(clauses, "UPPER(COALESCE(tool, 'GRADLE')) = ?")
		args = append(args, strings.ToUpper(filters.Tool))
	}
	if len(clauses) == 0 {
		return "", args
	}
	return " WHERE " + strings.Join(clauses, " AND "), args
}

func escapeLike(value string) string {
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, "%", `\%`)
	return strings.ReplaceAll(value, "_", `\_`)
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
