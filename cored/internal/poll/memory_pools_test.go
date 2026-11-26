package poll

import (
	"encoding/json"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestCodeCacheJSONSeparatesCapacityAndPeak(t *testing.T) {
	pools, err := heap.ParseJcmdCodeCache("CodeHeap 'non-profiled nmethods': size=120048Kb used=9433Kb max_used=10000Kb free=110615Kb")
	if err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(memoryPools(pools))
	if err != nil {
		t.Fatal(err)
	}
	var rows []map[string]json.RawMessage
	if err := json.Unmarshal(data, &rows); err != nil {
		t.Fatal(err)
	}
	if _, ok := rows[0]["committed"]; ok {
		t.Fatalf("unavailable committed memory should be omitted: %s", data)
	}
	for field, want := range map[string]float64{"used": 9433, "max": 120048, "peak": 10000} {
		var got model.JVMRawValue
		if err := json.Unmarshal(rows[0][field], &got); err != nil {
			t.Fatal(err)
		}
		if got.Value != want || got.Unit != "Kb" {
			t.Errorf("%s=%+v, want %v Kb", field, got, want)
		}
	}
}

func TestMemoryPoolJSONPreservesMeasuredZeroCommitted(t *testing.T) {
	pools := memoryPools([]heap.MemoryPool{{Name: "Eden", Committed: heap.RawValue{Unit: "KB"}}})
	data, err := json.Marshal(pools)
	if err != nil {
		t.Fatal(err)
	}
	var rows []map[string]json.RawMessage
	if err := json.Unmarshal(data, &rows); err != nil {
		t.Fatal(err)
	}
	if _, ok := rows[0]["committed"]; !ok {
		t.Fatalf("measured zero committed memory should be retained: %s", data)
	}
	if _, ok := rows[0]["peak"]; ok {
		t.Fatalf("unavailable peak should be omitted: %s", data)
	}
}
