package telemetry

import (
	"bytes"
	"encoding/json"
	"testing"
)

func TestReadOnlyOperationsAreStableAndComplete(t *testing.T) {
	want := []Operation{
		ListAvailableMetrics, ListProcessInstances, QueryProcessMetrics,
		QueryHostMetrics, QueryProcessEvents, QueryBuilds,
	}
	got := ReadOnlyOperations()
	if len(got) != len(want) {
		t.Fatalf("operations=%v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("operation[%d]=%q want %q", i, got[i], want[i])
		}
	}
}

func TestQueryRequiresMetricSetAndBoundedWindow(t *testing.T) {
	base := Query{Operation: QueryProcessMetrics, FromMs: 1, ToMs: 2, Metrics: []string{"process.rss"}}
	for name, query := range map[string]Query{
		"missing metric set": {Operation: QueryProcessMetrics, FromMs: 1, ToMs: 2},
		"missing window":     {Operation: QueryBuilds},
		"too wide":           {Operation: QueryBuilds, FromMs: 1, ToMs: MaxWindowMs + 2},
		"too many rows":      {Operation: QueryBuilds, FromMs: 1, ToMs: 2, Limit: MaxQueryRows + 1},
		"diagnosis":          {Operation: "diagnose_process", FromMs: 1, ToMs: 2},
	} {
		if err := query.Validate(); err == nil {
			t.Errorf("%s: expected validation error", name)
		}
	}
	if err := base.Validate(); err != nil {
		t.Fatalf("valid query rejected: %v", err)
	}
	if err := (Query{Operation: ListAvailableMetrics}).Validate(); err != nil {
		t.Fatalf("list operation rejected: %v", err)
	}
}

func TestRecordCarriesVersionAndAssociationEvidence(t *testing.T) {
	record, err := NewRecord("process_metric", 10, AssociationInferred, json.RawMessage(`{"pid":42}`))
	if err != nil {
		t.Fatal(err)
	}
	if record.SchemaVersion != SchemaVersion || record.Association != AssociationInferred {
		t.Fatalf("record=%+v", record)
	}
	if _, err := NewRecord("event", 10, "guessed", json.RawMessage(`{}`)); err == nil {
		t.Fatal("expected invalid association to be rejected")
	}
}

func TestWriteJSONLRejectsMixedSchemaVersions(t *testing.T) {
	record, err := NewRecord("host_metric", 10, AssociationConfirmed, json.RawMessage(`{"cpu":0.5}`))
	if err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := WriteJSONL(&output, []Record{record}); err != nil {
		t.Fatal(err)
	}
	if !bytes.HasSuffix(output.Bytes(), []byte("\n")) || !bytes.Contains(output.Bytes(), []byte(`"schema_version":1`)) {
		t.Fatalf("output=%q", output.String())
	}
	record.SchemaVersion++
	if err := WriteJSONL(&output, []Record{record}); err == nil {
		t.Fatal("expected schema mismatch to be rejected")
	}
}
