package heap_test

import (
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/heap"
)

const jstatGCFixture = `
    S0C         S1C         S0U         S1U          EC           EU           OC           OU          MC         MU       CCSC      CCSU     YGC     YGCT     FGC    FGCT     CGC    CGCT       GCT   
        0.0         0.0         0.0         0.0      24576.0          0.0     237568.0      34932.2      256.0       67.7     128.0       3.2      0     0.000     0     0.000     0     0.000     0.000
`

const jcmdHeapInfoFixture = `63124:
 garbage-first heap   total reserved 262144K, committed 262144K, used 36447K [0x00000007f0000000, 0x0000000800000000)
  region size 1024K, 2 young (2048K), 0 survivors (0K)
 Metaspace       used 80K, committed 320K, reserved 1114112K
  class space    used 5K, committed 128K, reserved 1048576K
`

func TestParseJstatGC(t *testing.T) {
	s, err := heap.ParseJstatGC(jstatGCFixture)
	if err != nil {
		t.Fatal(err)
	}
	// used = 0+0+0+34932.2 KB → 34 MB; committed = 0+0+24576+237568 = 262144 KB → 256 MB
	if s.UsedMB != 34 {
		t.Fatalf("used MB: got %d want 34", s.UsedMB)
	}
	if s.CommittedMB != 256 {
		t.Fatalf("committed MB: got %d want 256", s.CommittedMB)
	}
	if s.Source != "jstat" {
		t.Fatalf("source: %q", s.Source)
	}
}

func TestParseJcmdHeapInfo(t *testing.T) {
	s, err := heap.ParseJcmdHeapInfo(jcmdHeapInfoFixture)
	if err != nil {
		t.Fatal(err)
	}
	// used 36447K → 35 MB; committed 262144K → 256 MB
	if s.UsedMB != 35 {
		t.Fatalf("used MB: got %d want 35", s.UsedMB)
	}
	if s.CommittedMB != 256 {
		t.Fatalf("committed MB: got %d want 256", s.CommittedMB)
	}
	if s.Source != "jcmd" {
		t.Fatalf("source: %q", s.Source)
	}
}

func TestParseJcmdIgnoresMetaspace(t *testing.T) {
	// Only metaspace lines — should fail (no garbage-first / heap committed+used pair we accept).
	_, err := heap.ParseJcmdHeapInfo("Metaspace       used 80K, committed 320K, reserved 1114112K\n")
	if err == nil {
		t.Fatal("expected error for metaspace-only output")
	}
	if !strings.Contains(err.Error(), "no heap") {
		t.Fatalf("unexpected error: %v", err)
	}
}
