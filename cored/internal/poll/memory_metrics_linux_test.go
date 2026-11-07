//go:build linux

package poll

import (
	"strings"
	"testing"
)

func TestParseSmapsRollupConvertsPSSFromKBToBytes(t *testing.T) {
	got, err := parseSmapsRollup(strings.NewReader("" +
		"Pss:                 1234 kB\n" +
		"Private_Clean:         12 kB\n" +
		"VmFlags: rd mr mw\n"))
	if err != nil {
		t.Fatal(err)
	}
	if got["Pss"] == nil || *got["Pss"] != 1234*1024 {
		t.Fatalf("Pss=%v, want %d bytes", got["Pss"], 1234*1024)
	}
	if got["Private_Clean"] == nil || *got["Private_Clean"] != 12*1024 {
		t.Fatalf("Private_Clean=%v, want %d bytes", got["Private_Clean"], 12*1024)
	}
}
