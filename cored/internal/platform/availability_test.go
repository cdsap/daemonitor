package platform_test

import (
	"encoding/json"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/platform"
)

func TestAvailabilityValid(t *testing.T) {
	valid := []platform.Availability{
		platform.Available, platform.Unsupported, platform.PermissionDenied,
		platform.ToolUnavailable, platform.ProbeFailed, platform.Timeout,
		platform.Stale, platform.NotCollected,
	}
	for _, a := range valid {
		if !a.Valid() {
			t.Fatalf("%q should be valid", a)
		}
	}
	if platform.Availability("zero").Valid() {
		t.Fatal("unknown availability must be invalid")
	}
}

func TestUnavailableMetricsSerializeAsNull(t *testing.T) {
	m := platform.IntUnavailable(platform.Unsupported, "test")
	raw, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded["availability"] != string(platform.Unsupported) {
		t.Fatalf("availability=%v", decoded["availability"])
	}
	if decoded["value"] != nil {
		t.Fatalf("value must be null, got %#v", decoded["value"])
	}
}

func TestAvailableIntKeepsValue(t *testing.T) {
	m := platform.IntAvailable(42, 100, "test")
	if m.Value == nil || *m.Value != 42 || m.Availability != platform.Available {
		t.Fatalf("%+v", m)
	}
}

func TestNewCollectorCapabilities(t *testing.T) {
	c := platform.New()
	caps := c.Capabilities()
	if len(caps) == 0 {
		t.Fatal("expected capabilities")
	}
	seen := map[string]platform.Availability{}
	for _, cap := range caps {
		if !cap.Availability.Valid() {
			t.Fatalf("invalid availability for %s: %q", cap.MetricName, cap.Availability)
		}
		seen[cap.MetricName] = cap.Availability
	}
	if _, ok := seen["process.memory.resident_bytes"]; !ok {
		t.Fatal("missing resident_bytes capability")
	}
}
