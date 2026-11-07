package poll

import "testing"

func TestProportionalMemoryUsesSlowerCadence(t *testing.T) {
	if !shouldSampleProportional(1_000, 0) {
		t.Fatal("first PSS sample must be collected")
	}
	if shouldSampleProportional(1_000+proportionalMemoryInterval.Milliseconds()-1, 1_000) {
		t.Fatal("PSS must not run on every RSS poll")
	}
	if !shouldSampleProportional(1_000+proportionalMemoryInterval.Milliseconds(), 1_000) {
		t.Fatal("PSS must be refreshed after the configured interval")
	}
}
