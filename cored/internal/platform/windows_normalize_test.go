package platform

import "testing"

func TestNormalizeWindowsProcessMetrics(t *testing.T) {
	in := ProcessPlatformMetrics{
		SampledAtMs:          100,
		MinorPageFaultsTotal: IntAvailable(3, 100, sourceGopsutil),
		MajorPageFaultsTotal: IntAvailable(0, 100, sourceGopsutil),
		PageFaultsTotal:      IntAvailable(3, 100, sourceGopsutil),
		OpenDescriptorsCount: IntAvailable(42, 100, sourceGopsutil),
	}
	out := normalizeWindowsProcessMetrics(in)
	if out.MinorPageFaultsTotal.Availability != Unsupported || out.MinorPageFaultsTotal.Value != nil {
		t.Fatalf("minor=%+v", out.MinorPageFaultsTotal)
	}
	if out.MajorPageFaultsTotal.Availability != Unsupported || out.MajorPageFaultsTotal.Value != nil {
		t.Fatalf("major=%+v", out.MajorPageFaultsTotal)
	}
	if out.PageFaultsTotal.Availability != Available || out.PageFaultsTotal.Value == nil || *out.PageFaultsTotal.Value != 3 {
		t.Fatalf("total=%+v", out.PageFaultsTotal)
	}
	if out.OpenDescriptorsCount.Availability != Unsupported || out.OpenDescriptorsCount.Value != nil {
		t.Fatalf("open_descriptors=%+v", out.OpenDescriptorsCount)
	}
	if out.DiskReadBytesTotal.Availability != Unsupported || out.DiskReadBytesTotal.Value != nil {
		t.Fatalf("disk_read=%+v", out.DiskReadBytesTotal)
	}
	if out.DiskWriteBytesTotal.Availability != Unsupported || out.DiskWriteBytesTotal.Value != nil {
		t.Fatalf("disk_write=%+v", out.DiskWriteBytesTotal)
	}
	if out.HandlesCount.Availability != Available || out.HandlesCount.Value == nil || *out.HandlesCount.Value != 42 {
		t.Fatalf("handles=%+v", out.HandlesCount)
	}
}
