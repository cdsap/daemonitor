//go:build linux

package platform

import "testing"

func TestCgroupMetricParsers(t *testing.T) {
	if got := microsMetric("2500000", 42); got.Value == nil || *got.Value != 2.5 {
		t.Fatalf("microsMetric=%+v", got)
	}
	if got := parseIntMetric("max", 42, "test"); got.Value != nil || got.Availability != ProbeFailed {
		t.Fatalf("invalid integer=%+v", got)
	}
	if got := parseKeyIntMetric(map[string]string{"oom_kill": "3"}, "oom", 42); got.Value != nil || got.Availability != NotCollected {
		t.Fatalf("missing key=%+v", got)
	}
}
