package platform

// normalizeWindowsProcessMetrics aligns collected values with Windows capabilities:
// no fabricated minor/major fault split, and handle counts instead of POSIX FDs.
func normalizeWindowsProcessMetrics(m ProcessPlatformMetrics) ProcessPlatformMetrics {
	// GetProcessIoCounters reports transfer bytes and operation counts, not a
	// separate storage-I/O family. Keep the latter null instead of exposing
	// gopsutil's zero-value fields as real counters.
	m.DiskReadBytesTotal = IntUnavailable(Unsupported, "GetProcessIoCounters")
	m.DiskWriteBytesTotal = IntUnavailable(Unsupported, "GetProcessIoCounters")
	m.MinorPageFaultsTotal = IntUnavailable(Unsupported, sourceGopsutil)
	m.MajorPageFaultsTotal = IntUnavailable(Unsupported, sourceGopsutil)
	if m.OpenDescriptorsCount.Availability == Available && m.OpenDescriptorsCount.Value != nil {
		m.HandlesCount = IntAvailable(*m.OpenDescriptorsCount.Value, m.SampledAtMs, sourceGopsutil)
	} else if m.HandlesCount.Availability != Available {
		m.HandlesCount = IntUnavailable(NotCollected, "GetProcessHandleCount")
	}
	m.OpenDescriptorsCount = IntUnavailable(Unsupported, sourceGopsutil)
	return m
}
