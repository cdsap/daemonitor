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

const jcmdParallelHeapInfoFixture = `63124:
 PSYoungGen      total 76288K, used 49152K [0x0000000710000000, 0x0000000760000000)
 ParOldGen       total 175104K, used 0K [0x0000000660000000, 0x0000000710000000)
 Metaspace       used 80K, committed 320K, reserved 1114112K
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
	if s.YoungGCCount == nil || *s.YoungGCCount != 0 || s.YoungGCTimeMs == nil || *s.YoungGCTimeMs != 0 {
		t.Fatalf("young GC metrics: count=%v time=%v", s.YoungGCCount, s.YoungGCTimeMs)
	}
	if s.OldGCCount == nil || *s.OldGCCount != 0 || s.OldGCTimeMs == nil || *s.OldGCTimeMs != 0 {
		t.Fatalf("old GC metrics: count=%v time=%v", s.OldGCCount, s.OldGCTimeMs)
	}
	if s.MetaspaceUsedMB == nil || *s.MetaspaceUsedMB != 0 || s.MetaspaceCommittedMB == nil || *s.MetaspaceCommittedMB != 0 {
		t.Fatalf("metaspace metrics: used=%v committed=%v", s.MetaspaceUsedMB, s.MetaspaceCommittedMB)
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
	if s.GC == nil || *s.GC != "G1" {
		t.Fatalf("gc: %v", s.GC)
	}
	if s.MetaspaceUsedMB == nil || *s.MetaspaceUsedMB != 0 || s.MetaspaceCommittedMB == nil || *s.MetaspaceCommittedMB != 0 {
		t.Fatalf("metaspace metrics: used=%v committed=%v", s.MetaspaceUsedMB, s.MetaspaceCommittedMB)
	}
}

func TestParseJcmdParallelHeapInfo(t *testing.T) {
	s, err := heap.ParseJcmdHeapInfo(jcmdParallelHeapInfoFixture)
	if err != nil {
		t.Fatal(err)
	}
	if s.GC == nil || *s.GC != "Parallel" {
		t.Fatalf("gc: %v", s.GC)
	}
	if s.UsedMB != 48 {
		t.Fatalf("used MB: got %d want 48", s.UsedMB)
	}
	if s.CommittedMB != 245 {
		t.Fatalf("committed MB: got %d want 245", s.CommittedMB)
	}
}

func TestParseJcmdVMInfo(t *testing.T) {
	version, vendor, processors := heap.ParseJcmdVMInfo(`openjdk version "21.0.1" 2023-10-17
OpenJDK Runtime Environment
available processors: 8`)
	if version == nil || *version != "21.0.1" || vendor == nil || *vendor != "openjdk" {
		t.Fatalf("identity: version=%v vendor=%v", version, vendor)
	}
	if processors == nil || *processors != 8 {
		t.Fatalf("processors=%v", processors)
	}
}

func TestParseJinfoFlags(t *testing.T) {
	s := heap.ParseJinfoFlags(`-XX:ActiveProcessorCount=8
-XX:+UseG1GC
-XX:MaxHeapSize=4294967296
`)
	if s == nil || *s != "G1" {
		t.Fatalf("gc=%v", s)
	}
}

func TestParseJinfoFlagsIgnoresPropertiesAndDisabledFlags(t *testing.T) {
	s := heap.ParseJinfoFlags(`-Dfile.encoding=UTF-8
-XX:-UseG1GC
-XX:+UseParallelGCX
`)
	if s != nil {
		t.Fatalf("gc=%v", *s)
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

func TestParseJinfoFlagsGC(t *testing.T) {
	tests := []struct {
		name string
		out  string
		want string
	}{
		{name: "g1", out: "63124:\nNon-default VM flags: -XX:+UseG1GC -XX:MaxGCPauseMillis=100\n", want: "G1"},
		{name: "parallel", out: "VM Flags:\n-XX:+UseParallelGC -XX:+UseCompressedOops\n", want: "Parallel"},
		{name: "serial", out: "VM Flags:\n-XX:+UseSerialGC\n", want: "Serial"},
		{name: "shenandoah", out: "VM Flags:\n-XX:+UseShenandoahGC\n", want: "Shenandoah"},
		{name: "zgc", out: "VM Flags:\n-XX:+UseZGC\n", want: "ZGC"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := heap.ParseJinfoFlagsGC(tt.out)
			if err != nil {
				t.Fatal(err)
			}
			if got == nil || *got != tt.want {
				t.Fatalf("gc=%v want %q", got, tt.want)
			}
		})
	}
}

func TestParseJinfoFlagsGCRejectsUnsupportedOutput(t *testing.T) {
	for _, out := range []string{"", "VM Flags:\n-XX:+UseEpsilonGC\n", "permission denied"} {
		if _, err := heap.ParseJinfoFlagsGC(out); err == nil {
			t.Fatalf("expected unsupported output error for %q", out)
		}
	}
}
