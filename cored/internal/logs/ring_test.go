package logs

import (
	"slices"
	"testing"
)

func TestRingCircularBufferDoesNotRetainEvictedStrings(t *testing.T) {
	r := newRing(3)
	if got := r.slice(); len(got) != 0 {
		t.Fatalf("empty ring=%v", got)
	}

	r.push("a")
	r.push("b")
	if got := r.slice(); !slices.Equal(got, []string{"a", "b"}) {
		t.Fatalf("partial ring=%v", got)
	}

	r.push("c")
	r.push("d")
	r.push("e")
	if got := r.slice(); !slices.Equal(got, []string{"c", "d", "e"}) {
		t.Fatalf("wrapped ring=%v", got)
	}
	if len(r.data) != 3 || cap(r.data) != 3 {
		t.Fatalf("backing slice grew: len=%d cap=%d", len(r.data), cap(r.data))
	}
	if slices.Contains(r.data, "a") || slices.Contains(r.data, "b") {
		t.Fatalf("evicted entries still retained in backing array: %v", r.data)
	}

	zero := newRing(0)
	zero.push("ignored")
	if got := zero.slice(); len(got) != 0 {
		t.Fatalf("zero ring=%v", got)
	}
}
