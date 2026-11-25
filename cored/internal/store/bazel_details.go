package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/builds"
)

const maxBazelDetailsJSONBytes = 1048576

func (s *Store) ensureBazelBuildDetails() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS bazel_build_details (
  build_id TEXT PRIMARY KEY NOT NULL REFERENCES builds(build_id) ON DELETE CASCADE,
  schema_version INTEGER NOT NULL CHECK (schema_version = 1),
  bazel_version TEXT,
  source_format TEXT NOT NULL CHECK (source_format IN ('json', 'binary')),
  collection_state TEXT NOT NULL CHECK (collection_state IN ('partial', 'sealed', 'truncated', 'invalid')),
  updated_at_ms INTEGER NOT NULL,
  metrics_json TEXT NOT NULL CHECK (length(CAST(metrics_json AS BLOB)) <= 1048576)
);
`)
	return err
}

// UpsertBazelBuildDetails stores or updates the versioned rich BEP telemetry
// row for buildID. A sealed detail row is never downgraded to partial.
func (s *Store) UpsertBazelBuildDetails(buildID string, details *builds.BazelBuildDetails) error {
	if buildID == "" || details == nil {
		return nil
	}
	schemaVer := details.SchemaVersion
	if schemaVer == 0 {
		schemaVer = builds.BazelDetailsSchemaVersion
	}
	format := details.SourceFormat
	if format != "json" && format != "binary" {
		format = "json"
	}
	state := details.CollectionState
	switch state {
	case builds.CollectionStatePartial, builds.CollectionStateSealed, builds.CollectionStateTruncated, builds.CollectionStateInvalid:
	default:
		state = builds.CollectionStatePartial
	}
	updatedAt := details.UpdatedAtMs
	if updatedAt <= 0 {
		updatedAt = time.Now().UnixMilli()
	}
	encoded, err := json.Marshal(details.Metrics)
	if err != nil {
		return err
	}
	if len(encoded) > maxBazelDetailsJSONBytes {
		return fmt.Errorf("bazel build details exceed %d bytes (%d)", maxBazelDetailsJSONBytes, len(encoded))
	}
	_, err = s.db.Exec(`
INSERT INTO bazel_build_details(
  build_id, schema_version, bazel_version, source_format, collection_state, updated_at_ms, metrics_json
) VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(build_id) DO UPDATE SET
  schema_version = excluded.schema_version,
  bazel_version = COALESCE(NULLIF(excluded.bazel_version, ''), bazel_build_details.bazel_version),
  source_format = excluded.source_format,
  collection_state = CASE
    WHEN bazel_build_details.collection_state = 'sealed' AND excluded.collection_state = 'partial'
      THEN bazel_build_details.collection_state
    ELSE excluded.collection_state
  END,
  updated_at_ms = excluded.updated_at_ms,
  metrics_json = CASE
    WHEN bazel_build_details.collection_state = 'sealed' AND excluded.collection_state = 'partial'
      THEN bazel_build_details.metrics_json
    ELSE excluded.metrics_json
  END
`, buildID, schemaVer, nullStr(details.BazelVersion), format, state, updatedAt, string(encoded))
	return err
}

// GetBazelBuildDetailsBatch fetches rich BEP details for buildIDs in a single query.
func (s *Store) GetBazelBuildDetailsBatch(buildIDs []string) (map[string]*builds.BazelBuildDetails, error) {
	out := make(map[string]*builds.BazelBuildDetails)
	if len(buildIDs) == 0 {
		return out, nil
	}
	args := make([]any, 0, len(buildIDs))
	placeholders := make([]string, 0, len(buildIDs))
	seen := make(map[string]struct{}, len(buildIDs))
	for _, id := range buildIDs {
		if id == "" {
			continue
		}
		if _, dup := seen[id]; dup {
			continue
		}
		seen[id] = struct{}{}
		args = append(args, id)
		placeholders = append(placeholders, "?")
	}
	if len(args) == 0 {
		return out, nil
	}
	query := `
SELECT build_id, schema_version, bazel_version, source_format, collection_state, updated_at_ms, metrics_json
FROM bazel_build_details
WHERE build_id IN (` + strings.Join(placeholders, ",") + `)`
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	for rows.Next() {
		var buildID, format, state, metricsJSON string
		var bazelVersion sql.NullString
		var schemaVer int
		var updatedAt int64
		if err := rows.Scan(&buildID, &schemaVer, &bazelVersion, &format, &state, &updatedAt, &metricsJSON); err != nil {
			return nil, err
		}
		var metrics builds.BazelBuildMetrics
		if err := json.Unmarshal([]byte(metricsJSON), &metrics); err != nil {
			return nil, err
		}
		out[buildID] = &builds.BazelBuildDetails{
			SchemaVersion:   schemaVer,
			BazelVersion:    bazelVersion.String,
			SourceFormat:    format,
			CollectionState: state,
			UpdatedAtMs:     updatedAt,
			Metrics:         metrics,
		}
	}
	return out, rows.Err()
}
