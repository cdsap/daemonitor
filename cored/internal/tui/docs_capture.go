package tui

import (
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

// RenderCliDocsScreenshot returns the main TUI process table for README / site PNGs.
// Keep sample paths under /workspace/samples so docs stay privacy-safe.
func RenderCliDocsScreenshot() string {
	now := time.Date(2026, 11, 13, 14, 22, 8, 0, time.UTC)
	cpuB, cpuG, cpuK, cpuW, cpuT := 41.0, 18.4, 6.1, 22.0, 55.0
	xmxB, xmxG := int64(4096), int64(2048)
	heapB, heapG, heapK := int64(1680), int64(940), int64(210)
	cmtB, cmtG := int64(2016), int64(1100)
	metaB, metaG := int64(312), int64(188)
	thrB, thrG := int64(148), int64(96)
	ygct, fgct, cgct, gct := 12.4, 0.8, 1.1, 14.3
	gc := "G1"
	maps := "/workspace/samples/maps-service"
	checkout := "/workspace/samples/checkout-service"
	android := "/workspace/samples/android-app"
	wrapperXmx := int64(64)

	bazel := model.Process{
		PID: 7102, Type: "BAZEL_SERVER", Name: "java", ProjectPath: &maps,
		RSSMemoryMB: 3120, CPUPercent: &cpuB, MaxHeapMB: &xmxB, HeapUsedMB: &heapB,
		HeapCommittedMB: &cmtB, HeapMaxMB: &xmxB, HeapAvailable: true, MetaspaceUsedMB: &metaB,
		ThreadCount: &thrB, GC: &gc,
		YoungGCTimeSeconds: &ygct, FullGCTimeSeconds: &fgct,
		ConcurrentGCTimeSeconds: &cgct, TotalGCTimeSeconds: &gct,
		StartTimeMs: now.Add(-26 * time.Minute).UnixMilli(), SampledAtMs: now.UnixMilli(),
	}
	gradle := model.Process{
		PID: 6120, Type: "GRADLE_DAEMON", Name: "java", ProjectPath: &checkout,
		RSSMemoryMB: 2680, CPUPercent: &cpuG, MaxHeapMB: &xmxG, HeapUsedMB: &heapG,
		HeapCommittedMB: &cmtG, HeapMaxMB: &xmxG, HeapAvailable: true, MetaspaceUsedMB: &metaG,
		ThreadCount: &thrG, GC: &gc,
		StartTimeMs: now.Add(-2*time.Hour - 16*time.Minute).UnixMilli(), SampledAtMs: now.UnixMilli(),
	}

	m := NewModel(Config{Now: func() time.Time { return now }, NoColor: false})
	m.applySnapshot(model.Snapshot{
		SampledAtMs: now.UnixMilli(),
		Processes: []model.Process{
			bazel,
			gradle,
			{
				PID: 6401, Type: "GRADLE_DAEMON", Name: "java", ProjectPath: &android,
				RSSMemoryMB: 1840, CPUPercent: &cpuG, MaxHeapMB: &xmxG, HeapUsedMB: &heapG,
				HeapCommittedMB: &cmtG, HeapMaxMB: &xmxG, HeapAvailable: true, GC: &gc,
				StartTimeMs: now.Add(-55 * time.Minute).UnixMilli(), SampledAtMs: now.UnixMilli(),
			},
			{
				PID: 6188, ParentPID: 6120, Type: "KOTLIN_DAEMON", Name: "java", ProjectPath: &checkout,
				RSSMemoryMB: 920, CPUPercent: &cpuK, HeapUsedMB: &heapK, HeapAvailable: true,
				StartTimeMs: now.Add(-2 * time.Hour).UnixMilli(), SampledAtMs: now.UnixMilli(),
			},
			{
				PID: 7120, ParentPID: 7102, Type: "BAZEL_WORKER", Name: "java", ProjectPath: &maps,
				RSSMemoryMB: 880, CPUPercent: &cpuW,
				StartTimeMs: now.Add(-3 * time.Minute).UnixMilli(), SampledAtMs: now.UnixMilli(),
			},
			{
				PID: 6201, ParentPID: 6120, Type: "GRADLE_WORKER", Name: "java", ProjectPath: &checkout,
				RSSMemoryMB: 640, CPUPercent: &cpuW,
				StartTimeMs: now.Add(-4 * time.Minute).UnixMilli(), SampledAtMs: now.UnixMilli(),
			},
			{
				PID: 7134, ParentPID: 7102, Type: "BAZEL_TEST", Name: "java", ProjectPath: &maps,
				RSSMemoryMB: 640, CPUPercent: &cpuT,
				StartTimeMs: now.Add(-90 * time.Second).UnixMilli(), SampledAtMs: now.UnixMilli(),
			},
			{
				PID: 6302, Type: "GRADLE_WRAPPER", Name: "java", ProjectPath: &android,
				RSSMemoryMB: 180, CPUPercent: &cpuK, MaxHeapMB: &wrapperXmx,
				StartTimeMs: now.Add(-45 * time.Second).UnixMilli(), SampledAtMs: now.UnixMilli(),
			},
		},
	})
	m.connected = true
	m.lastUpdated = now
	m.sampledAt = now.UnixMilli()
	m.width, m.height = 100, 22
	m.detailsOpen = false
	m.groupedView = false
	m.selectedPID = int64(gradle.PID)
	return m.View().Content
}
