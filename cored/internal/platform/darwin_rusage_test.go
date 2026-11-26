//go:build darwin

package platform

import (
	"context"
	"os"
	"testing"
	"unsafe"
)

func TestDarwinRusageInfoV4ABIOffsets(t *testing.T) {
	var s darwinRusageInfoV4Struct
	if got := unsafe.Sizeof(s); got != 296 {
		t.Fatalf("sizeof(rusage_info_v4) = %d, want 296", got)
	}
	checks := []struct {
		name string
		got  uintptr
		want uintptr
	}{
		{"ri_phys_footprint", unsafe.Offsetof(s.PhysFootprint), 72},
		{"ri_diskio_bytesread", unsafe.Offsetof(s.DiskIOBytesRead), 144},
		{"ri_diskio_byteswritten", unsafe.Offsetof(s.DiskIOBytesWritten), 152},
		{"ri_lifetime_max_phys_footprint", unsafe.Offsetof(s.LifetimeMaxPhysFootprint), 240},
	}
	for _, tc := range checks {
		if tc.got != tc.want {
			t.Errorf("offsetof(%s) = %d, want %d", tc.name, tc.got, tc.want)
		}
	}
}

func TestDarwinCollectProcessPopulatesRusageMetricsWithoutCGO(t *testing.T) {
	metrics := New().CollectProcess(context.Background(), ProcessIdentity{PID: int32(os.Getpid())})
	if metrics.PhysicalFootprintBytes.Availability != Available || metrics.PhysicalFootprintBytes.Value == nil || *metrics.PhysicalFootprintBytes.Value <= 0 {
		t.Fatalf("PhysicalFootprintBytes = %+v, want Available > 0", metrics.PhysicalFootprintBytes)
	}
	if metrics.PeakResidentBytes.Availability != Available || metrics.PeakResidentBytes.Value == nil || *metrics.PeakResidentBytes.Value < *metrics.PhysicalFootprintBytes.Value {
		t.Fatalf("PeakResidentBytes = %+v, want Available >= footprint (%d)", metrics.PeakResidentBytes, *metrics.PhysicalFootprintBytes.Value)
	}
	if metrics.DiskReadBytesTotal.Availability != Available || metrics.DiskReadBytesTotal.Value == nil {
		t.Fatalf("DiskReadBytesTotal = %+v, want Available", metrics.DiskReadBytesTotal)
	}
	if metrics.DiskWriteBytesTotal.Availability != Available || metrics.DiskWriteBytesTotal.Value == nil {
		t.Fatalf("DiskWriteBytesTotal = %+v, want Available", metrics.DiskWriteBytesTotal)
	}
}

func TestDarwinCapabilitiesAdvertisePhysicalFootprintAndPeakResident(t *testing.T) {
	caps := New().Capabilities()
	byName := map[string]Availability{}
	for _, c := range caps {
		byName[c.MetricName] = c.Availability
	}
	for _, name := range []string{
		"process.memory.physical_footprint_bytes",
		"process.memory.peak_resident_bytes",
		"process.io.disk_read_bytes_total",
		"process.io.disk_write_bytes_total",
	} {
		if byName[name] != Available {
			t.Errorf("capability %s = %q, want %q", name, byName[name], Available)
		}
	}
}
