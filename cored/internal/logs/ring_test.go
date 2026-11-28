package logs

import (
	"strings"
	"testing"
)

func TestRingWrapsWithoutRetainingEvictedLines(t *testing.T) {
	r := newRing(3)
	for _, line := range []string{"old-a", "old-b", "old-c", "d", "e", "f", "g"} {
		r.push(line)
	}
	got := r.slice()
	if strings.Join(got, ",") != "e,f,g" {
		t.Fatalf("tail=%v", got)
	}
	got[0] = "changed"
	if r.slice()[0] != "e" {
		t.Fatal("slice aliases ring")
	}
	for _, line := range r.data[:cap(r.data)] {
		if strings.HasPrefix(line, "old-") {
			t.Fatal("evicted string retained")
		}
	}
	empty := newRing(0)
	empty.push("discard")
	if len(empty.slice()) != 0 {
		t.Fatal("zero-capacity ring retained data")
	}
}
