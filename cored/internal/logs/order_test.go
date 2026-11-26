package logs

import (
	"encoding/json"
	"reflect"
	"testing"
	"time"
)

func TestSortLogTieBreakersAndWireFormat(t *testing.T) {
	want := []DaemonLog{
		{PID: 1},
		{PID: 2},
		{PID: 2, Tool: "bazel", Source: "command.log", Path: "/a"},
		{PID: 2, Tool: "bazel", Source: "command.log", Path: "/b"},
		{PID: 2, Tool: "bazel", Source: "server/jvm.out"},
	}
	// Equal activity and timestamps must fall back to every identity component.
	got := make([]DaemonLog, len(want))
	for i := range want {
		want[i].active = true
		want[i].modified = time.Unix(1700000000, 0)
		got[len(want)-1-i] = want[i]
	}
	Sort(got)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v, want %+v", got, want)
	}
	encoded, err := json.Marshal(got[0])
	if err != nil {
		t.Fatal(err)
	}
	if string(encoded) != `{"pid":1,"gradle_version":"","path":""}` {
		t.Fatalf("ordering metadata changed wire format: %s", encoded)
	}
	Sort(nil)
	Sort([]DaemonLog{})
}
