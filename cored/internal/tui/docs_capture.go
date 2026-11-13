package tui

import (
	"time"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

// RenderCliDocsScreenshot returns a synthetic details View for README / site PNGs.
// Keep sample paths under /workspace/samples so docs stay privacy-safe.
func RenderCliDocsScreenshot() string {
	now := time.Date(2026, 11, 13, 14, 22, 8, 0, time.UTC)
	cpu := 41.0
	xmx := int64(4096)
	used := int64(1680)
	committed := int64(2016)
	heapMax := int64(4096)
	metaspace := int64(312)
	threads := int64(148)
	openFiles := int64(96)
	virt := int64(8420)
	gc := "G1"
	javaVersion := "21.0.5"
	javaVendor := "Eclipse Adoptium"
	durationOK := 96.4
	durationFail := 42.1
	workspace := "/workspace/samples/maps-service"
	checkout := "/workspace/samples/checkout-service"
	minHeap := int64(256)

	bazel := model.Process{
		PID:                 7102,
		Type:                "BAZEL_SERVER",
		Name:                "java",
		CommandLine:         "java -Xmx4096m -jar /workspace/samples/bazel/A-server.jar --product_name=Bazel --workspace_directory=/workspace/samples/maps-service",
		WorkingDirectory:    &workspace,
		ProjectPath:         &workspace,
		RSSMemoryMB:         3120,
		VirtualMemoryMB:     &virt,
		CPUPercent:          &cpu,
		MaxHeapMB:           &xmx,
		MinHeapMB:           &minHeap,
		GC:                  &gc,
		HeapUsedMB:          &used,
		HeapCommittedMB:     &committed,
		HeapMaxMB:           &heapMax,
		HeapAvailable:       true,
		MetaspaceUsedMB:     &metaspace,
		ThreadCount:         &threads,
		OpenFileDescriptors: &openFiles,
		JavaVersion:         &javaVersion,
		JavaVendor:          &javaVendor,
		StartTimeMs:         now.Add(-26 * time.Minute).UnixMilli(),
		SampledAtMs:         now.UnixMilli(),
		MemoryPools: []model.JVMMemoryPool{
			{Name: "G1 Eden Space", Used: model.JVMRawValue{Value: 420, Unit: "M"}, Committed: model.JVMRawValue{Value: 640, Unit: "M"}},
			{Name: "G1 Old Gen", Used: model.JVMRawValue{Value: 980, Unit: "M"}, Committed: model.JVMRawValue{Value: 1400, Unit: "M"}},
		},
		CodeCache: []model.JVMMemoryPool{
			{Name: "non-nmethods", Used: model.JVMRawValue{Value: 12, Unit: "M"}, Committed: model.JVMRawValue{Value: 24, Unit: "M"}, Max: model.JVMRawValue{Value: 48, Unit: "M"}},
		},
	}

	m := NewModel(Config{Now: func() time.Time { return now }, NoColor: true})
	m.applySnapshot(model.Snapshot{
		SampledAtMs: now.UnixMilli(),
		Processes: []model.Process{
			bazel,
			{
				PID: 7120, ParentPID: 7102, Type: "BAZEL_WORKER", RSSMemoryMB: 880,
				ProjectPath: &workspace, StartTimeMs: now.Add(-3 * time.Minute).UnixMilli(),
			},
			{
				PID: 7134, ParentPID: 7102, Type: "BAZEL_TEST", RSSMemoryMB: 640,
				ProjectPath: &workspace, StartTimeMs: now.Add(-90 * time.Second).UnixMilli(),
			},
			{
				PID: 6120, Type: "GRADLE_DAEMON", RSSMemoryMB: 2680,
				ProjectPath: &checkout, StartTimeMs: now.Add(-18 * time.Minute).UnixMilli(),
			},
		},
	})
	m.connected = true
	m.lastUpdated = now
	m.sampledAt = now.UnixMilli()
	m.width, m.height = 120, 30
	m.detailPane = detailPaneBuilds
	m.detailsOpen = true
	m.selectedPID = int64(bazel.PID)
	m.detailProcess = &bazel
	m.detailBuilds = client.BuildsPayload{
		Count: 2,
		Builds: []client.BuildRecord{
			{
				BuildID: "bep-2201", DaemonPID: 7102, StartTimeMs: now.Add(-12 * time.Minute).UnixMilli(),
				FinalStatus: "SUCCESS", InferredSource: "BEP", Tool: "BAZEL",
				ProjectPath: workspace, DurationSeconds: &durationOK,
			},
			{
				BuildID: "bep-2198", DaemonPID: 7102, StartTimeMs: now.Add(-40 * time.Minute).UnixMilli(),
				FinalStatus: "FAILED", InferredSource: "BEP", Tool: "BAZEL",
				ProjectPath: workspace, DurationSeconds: &durationFail,
			},
		},
	}
	return m.View().Content
}
