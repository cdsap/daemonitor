// Package telemetry defines the stable, read-only contract shared by local
// telemetry exports and a future native MCP adapter.
package telemetry

import (
	"encoding/json"
	"fmt"
	"io"
)

const (
	// SchemaVersion is incremented when the export shape changes incompatibly.
	SchemaVersion = 1
	MaxQueryRows  = 5000
	MaxWindowMs   = 7 * 24 * 60 * 60 * 1000
)

// Operation is an allowlisted read-only telemetry operation. Keep this list
// deliberately separate from diagnostic or remediation functionality.
type Operation string

const (
	ListAvailableMetrics Operation = "list_available_metrics"
	ListProcessInstances Operation = "list_process_instances"
	QueryProcessMetrics  Operation = "query_process_metrics"
	QueryHostMetrics     Operation = "query_host_metrics"
	QueryProcessEvents   Operation = "query_process_events"
	QueryBuilds          Operation = "query_builds"
)

var readOnlyOperations = map[Operation]struct{}{
	ListAvailableMetrics: {},
	ListProcessInstances: {},
	QueryProcessMetrics:  {},
	QueryHostMetrics:     {},
	QueryProcessEvents:   {},
	QueryBuilds:          {},
}

// Query is the common bounded part of telemetry queries. List operations may
// omit the time window; query operations must provide both endpoints.
type Query struct {
	Operation Operation `json:"operation"`
	Metrics   []string  `json:"metrics,omitempty"`
	FromMs    int64     `json:"from_ms,omitempty"`
	ToMs      int64     `json:"to_ms,omitempty"`
	Limit     int       `json:"limit,omitempty"`
}

// Validate enforces the v1 query safety boundary before a backend is called.
func (q Query) Validate() error {
	if _, ok := readOnlyOperations[q.Operation]; !ok {
		return fmt.Errorf("unsupported telemetry operation %q", q.Operation)
	}
	if q.Limit < 0 || q.Limit > MaxQueryRows {
		return fmt.Errorf("limit must be between 0 and %d", MaxQueryRows)
	}
	if q.Operation == ListAvailableMetrics || q.Operation == ListProcessInstances {
		return nil
	}
	if q.FromMs <= 0 || q.ToMs <= 0 || q.ToMs < q.FromMs {
		return fmt.Errorf("query operations require an ordered positive from_ms/to_ms window")
	}
	if q.ToMs-q.FromMs > MaxWindowMs {
		return fmt.Errorf("query window must not exceed %dms", MaxWindowMs)
	}
	if q.Operation == QueryProcessMetrics && len(q.Metrics) == 0 {
		return fmt.Errorf("query_process_metrics requires at least one metric")
	}
	return nil
}

// Association records the evidence strength of an external correlation.
type Association string

const (
	AssociationConfirmed Association = "confirmed"
	AssociationInferred  Association = "inferred_temporal_overlap"
)

// Record is one JSONL export row. Payload is operation-specific JSON and is
// kept opaque so the contract can evolve without coupling the adapter to the
// collector implementation.
type Record struct {
	SchemaVersion int             `json:"schema_version"`
	Kind          string          `json:"kind"`
	CollectedAtMs int64           `json:"collected_at_ms"`
	Association   Association     `json:"association,omitempty"`
	Correlation   json.RawMessage `json:"correlation,omitempty"`
	Payload       json.RawMessage `json:"payload"`
}

// NewRecord creates a versioned export row and rejects an invalid association.
func NewRecord(kind string, collectedAtMs int64, association Association, payload json.RawMessage) (Record, error) {
	if kind == "" {
		return Record{}, fmt.Errorf("record kind must not be empty")
	}
	if collectedAtMs <= 0 {
		return Record{}, fmt.Errorf("collected_at_ms must be positive")
	}
	if association != "" && association != AssociationConfirmed && association != AssociationInferred {
		return Record{}, fmt.Errorf("unsupported association %q", association)
	}
	if !json.Valid(payload) {
		return Record{}, fmt.Errorf("payload must be valid JSON")
	}
	return Record{SchemaVersion: SchemaVersion, Kind: kind, CollectedAtMs: collectedAtMs, Association: association, Payload: payload}, nil
}

// ReadOnlyOperations returns a copy suitable for adapter discovery.
func ReadOnlyOperations() []Operation {
	return []Operation{
		ListAvailableMetrics,
		ListProcessInstances,
		QueryProcessMetrics,
		QueryHostMetrics,
		QueryProcessEvents,
		QueryBuilds,
	}
}

// WriteJSONL writes a local export after checking that every row belongs to
// this contract version. It never opens a network destination or starts a
// producer, so callers retain explicit control over collection and file path.
func WriteJSONL(w io.Writer, records []Record) error {
	encoder := json.NewEncoder(w)
	for _, record := range records {
		if record.SchemaVersion != SchemaVersion {
			return fmt.Errorf("record schema version %d is not supported; want %d", record.SchemaVersion, SchemaVersion)
		}
		if _, err := NewRecord(record.Kind, record.CollectedAtMs, record.Association, record.Payload); err != nil {
			return err
		}
		if err := encoder.Encode(record); err != nil {
			return fmt.Errorf("write telemetry export: %w", err)
		}
	}
	return nil
}
