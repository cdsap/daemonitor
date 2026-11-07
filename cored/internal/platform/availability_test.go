package platform_test

import (
	"context"
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
	for _, name := range []string{
		"host.cpu.usage_percent", "container.cpu.quota_cores", "container.memory.limit_bytes",
		"job.cpu.time_seconds_total", "job.memory.usage_bytes", "job.process_count",
	} {
		if _, ok := seen[name]; !ok {
			t.Fatalf("missing %s capability", name)
		}
	}
}

func TestHostAndResourceScopesNeverFabricateUnavailableValues(t *testing.T) {
	host := platform.New().CollectHost(context.Background())
	if host.Scope != platform.ScopeHost {
		t.Fatalf("host scope=%q", host.Scope)
	}
	if host.PSICPU.SomeAvg10.Value != nil && host.PSICPU.SomeAvg10.Availability != platform.Available {
		t.Fatalf("host PSI value has inconsistent availability: %+v", host.PSICPU.SomeAvg10)
	}
	process := platform.New().CollectProcess(context.Background(), platform.ProcessIdentity{PID: -1})
	if process.ResourceScope != platform.ScopeProcess {
		t.Fatalf("process scope=%q", process.ResourceScope)
	}
	if process.Resource.Scope != platform.ScopeLinuxCgroup && process.Resource.Scope != platform.ScopeProcess {
		t.Fatalf("resource scope=%q", process.Resource.Scope)
	}
	if process.Resource.MemoryLimitBytes.Value != nil && process.Resource.MemoryLimitBytes.Availability != platform.Available {
		t.Fatalf("resource value has inconsistent availability: %+v", process.Resource.MemoryLimitBytes)
	}
}
