package bep

import (
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
)

func TestRichBEPFixturesJSONAndBinaryParity(t *testing.T) {
	for _, tc := range []struct {
		name            string
		wantStatus      builds.FinalStatus
		wantCmd         string
		wantCreated     int64
		wantExecuted    int64
		wantMisses      int64
		wantWallMs      int64
		wantTestCount   int
		wantAbortReason string
	}{
		{
			name:          "rich_success",
			wantStatus:    builds.StatusSuccess,
			wantCmd:       "bazel test",
			wantCreated:   7,
			wantExecuted:  6,
			wantMisses:    7,
			wantWallMs:    4062,
			wantTestCount: 1,
		},
		{
			name:            "rich_failed",
			wantStatus:      builds.StatusFailed,
			wantCmd:         "bazel build",
			wantWallMs:      449,
			wantAbortReason: "LOADING_FAILURE",
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			jsonBytes, err := os.ReadFile("testdata/" + tc.name + ".ndjson")
			if err != nil {
				t.Fatal(err)
			}
			binBytes, err := os.ReadFile("testdata/" + tc.name + ".bin")
			if err != nil {
				t.Fatal(err)
			}
			jsonBuilds, err := ParseJSONStream(bytes.NewReader(jsonBytes))
			if err != nil || len(jsonBuilds) != 1 {
				t.Fatalf("json builds=%+v err=%v", jsonBuilds, err)
			}
			binBuilds, err := ParseBinaryStream(bytes.NewReader(binBytes))
			if err != nil || len(binBuilds) != 1 {
				t.Fatalf("binary builds=%+v err=%v", binBuilds, err)
			}

			jb, bb := jsonBuilds[0], binBuilds[0]
			if jb.FinalStatus != tc.wantStatus || bb.FinalStatus != tc.wantStatus {
				t.Fatalf("status json=%s bin=%s want=%s", jb.FinalStatus, bb.FinalStatus, tc.wantStatus)
			}
			if jb.CommandLine == nil || *jb.CommandLine != tc.wantCmd || bb.CommandLine == nil || *bb.CommandLine != tc.wantCmd {
				t.Fatalf("command json=%v bin=%v want=%q", jb.CommandLine, bb.CommandLine, tc.wantCmd)
			}
			if jb.BazelDetails == nil || bb.BazelDetails == nil {
				t.Fatalf("missing BazelDetails: json=%+v bin=%+v", jb.BazelDetails, bb.BazelDetails)
			}
			if jb.BazelDetails.SourceFormat != "json" || bb.BazelDetails.SourceFormat != "binary" {
				t.Fatalf("formats json=%q bin=%q", jb.BazelDetails.SourceFormat, bb.BazelDetails.SourceFormat)
			}
			if jb.BazelDetails.CollectionState != builds.CollectionStateSealed || bb.BazelDetails.CollectionState != builds.CollectionStateSealed {
				t.Fatalf("collection state json=%q bin=%q want sealed", jb.BazelDetails.CollectionState, bb.BazelDetails.CollectionState)
			}
			if jb.BazelDetails.BazelVersion != "8.4.2" || bb.BazelDetails.BazelVersion != "8.4.2" {
				t.Fatalf("version json=%q bin=%q", jb.BazelDetails.BazelVersion, bb.BazelDetails.BazelVersion)
			}
			if !reflect.DeepEqual(jb.BazelDetails.Metrics, bb.BazelDetails.Metrics) {
				t.Fatalf("JSON/binary metrics mismatch:\nJSON: %+v\nBIN:  %+v", jb.BazelDetails.Metrics, bb.BazelDetails.Metrics)
			}

			m := jb.BazelDetails.Metrics
			if m.Timing == nil || m.Timing.WallTimeMs == nil || *m.Timing.WallTimeMs != tc.wantWallMs {
				t.Fatalf("timing=%+v", m.Timing)
			}
			if tc.wantCreated > 0 {
				if m.Actions == nil || m.Actions.Created == nil || *m.Actions.Created != tc.wantCreated ||
					m.Actions.Executed == nil || *m.Actions.Executed != tc.wantExecuted ||
					m.Actions.LocalCacheMisses == nil || *m.Actions.LocalCacheMisses != tc.wantMisses {
					t.Fatalf("actions=%+v", m.Actions)
				}
			}
			if tc.wantTestCount > 0 {
				if len(m.Tests) != tc.wantTestCount || m.TestTotals == nil || m.TestTotals.Passed != 1 || m.TestTotals.TotalDurationMs != 224 {
					t.Fatalf("tests=%+v totals=%+v", m.Tests, m.TestTotals)
				}
			}
			if tc.wantAbortReason != "" {
				if m.Diagnostics == nil || m.Diagnostics.AbortReason != tc.wantAbortReason || jb.LogSnippet == "" {
					t.Fatalf("diagnostics=%+v snippet=%q", m.Diagnostics, jb.LogSnippet)
				}
			}
		})
	}
}

func TestRichBEPWorkersMemoryRedactionAndIncrementalPostFinish(t *testing.T) {
	// Exercise post-finished buildMetrics arriving in a second incremental chunk,
	// plus workerMetrics, workerPoolMetrics, heap scalars, structured targets, and secret redaction.
	chunk1 := strings.Join([]string{
		`{"started":{"uuid":"inc-1","startTimeMillis":"1000","buildToolVersion":"8.4.2","command":"build","serverPid":"77"}}`,
		`{"structuredCommandLine":{"commandLineLabel":"original","sections":[{"sectionLabel":"command","chunkList":{"chunk":["build"]}},{"sectionLabel":"command options","optionList":{"option":[{"optionName":"remote_header","optionValue":"Authorization=Bearer secret-token-123"}]}},{"sectionLabel":"residual","chunkList":{"chunk":["//app:all","--remote_password=supersecret"]}}]}}`,
		`{"finished":{"finishTimeMillis":"2500","exitCode":{"name":"SUCCESS","code":0}}}`,
	}, "\n") + "\n"

	chunk2 := `{"lastMessage":true,"buildMetrics":{"memoryMetrics":{"usedHeapSizePostBuild":"536870912","peakPostGcHeapSize":"402653184","peakPostGcTenuredSpaceHeapSize":"268435456","garbageMetrics":[{"type":"G1 Eden Space","garbageCollected":"1048576"}]},"timingMetrics":{"cpuTimeInMs":"3000","wallTimeInMs":"1500","analysisPhaseTimeInMs":"120","executionPhaseTimeInMs":"1200"},"actionSummary":{"actionsCreated":"20","actionsExecuted":"12","actionCacheStatistics":{"hits":8,"misses":12}},"workerMetrics":[{"processId":"9001","mnemonic":"Javac","isMultiplex":true,"isSandbox":true,"isMeasurable":true,"workerIds":[1,2],"actionsExecuted":"9","workerStats":[{"workerMemoryInKb":102400},{"workerMemoryInKb":153600}]}],"workerPoolMetrics":{"workerPoolStats":[{"mnemonic":"Javac","createdCount":2,"destroyedCount":1,"evictedCount":1,"aliveCount":1}]}}}` + "\n"

	dir := t.TempDir()
	path := filepath.Join(dir, "bep.ndjson")
	if err := os.WriteFile(path, []byte(chunk1), 0o600); err != nil {
		t.Fatal(err)
	}
	w := NewWatcher()
	w.ExtraPaths = func() []Sink { return []Sink{{Path: path, Format: FormatJSON}} }

	first, err := w.Poll(t.Context())
	if err != nil || len(first) != 1 {
		t.Fatalf("first poll=%+v err=%v", first, err)
	}
	if first[0].CommandLine == nil || !strings.Contains(*first[0].CommandLine, "//app:all") || strings.Contains(*first[0].CommandLine, "supersecret") || strings.Contains(*first[0].CommandLine, "secret-token-123") {
		t.Fatalf("command redaction failed: %v", first[0].CommandLine)
	}
	if first[0].BazelDetails == nil || first[0].BazelDetails.CollectionState != builds.CollectionStatePartial {
		t.Fatalf("expected partial before lastMessage: %+v", first[0].BazelDetails)
	}

	// Append post-finished buildMetrics + lastMessage in a second poll tick.
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.WriteString(chunk2); err != nil {
		t.Fatal(err)
	}
	_ = f.Close()

	second, err := w.Poll(t.Context())
	if err != nil || len(second) != 1 {
		t.Fatalf("second poll=%+v err=%v", second, err)
	}
	d := second[0].BazelDetails
	if d == nil || d.CollectionState != builds.CollectionStateSealed {
		t.Fatalf("expected sealed details on second poll: %+v", d)
	}
	if d.Metrics.Memory == nil || *d.Metrics.Memory.UsedHeapPostBuildBytes != 536870912 || *d.Metrics.Memory.PeakPostGCHeapBytes != 402653184 || *d.Metrics.Memory.PeakPostGCTenuredBytes != 268435456 {
		t.Fatalf("memory=%+v", d.Metrics.Memory)
	}
	if len(d.Metrics.Workers) != 1 || d.Metrics.Workers[0].Mnemonic != "Javac" || d.Metrics.Workers[0].WorkerIDCount != 2 || *d.Metrics.Workers[0].PeakMemoryKB != 153600 {
		t.Fatalf("workers=%+v", d.Metrics.Workers)
	}
	if len(d.Metrics.WorkerPools) != 1 || d.Metrics.WorkerPools[0].EvictedCount != 1 || d.Metrics.WorkerPools[0].AliveCount != 1 {
		t.Fatalf("workerPools=%+v", d.Metrics.WorkerPools)
	}
	if d.Metrics.Actions == nil || *d.Metrics.Actions.LocalCacheHits != 8 || *d.Metrics.Actions.LocalCacheMisses != 12 {
		t.Fatalf("actions=%+v", d.Metrics.Actions)
	}

	// Third poll with no new bytes must not re-emit sealed build.
	third, err := w.Poll(t.Context())
	if err != nil || len(third) != 0 {
		t.Fatalf("third poll should be empty, got %+v err=%v", third, err)
	}
}

func TestBinaryWorkerMetricsPackedWorkerIDs(t *testing.T) {
	appendVarint := func(dst []byte, field uint64, v uint64) []byte {
		dst = binary.AppendUvarint(dst, field<<3)
		return binary.AppendUvarint(dst, v)
	}
	appendBytes := func(dst []byte, field uint64, b []byte) []byte {
		dst = binary.AppendUvarint(dst, field<<3|2)
		dst = binary.AppendUvarint(dst, uint64(len(b)))
		return append(dst, b...)
	}
	frame := func(msg []byte) []byte {
		out := binary.AppendUvarint(nil, uint64(len(msg)))
		return append(out, msg...)
	}

	started := appendBytes(nil, 1, []byte("bin-worker-1"))
	started = appendVarint(started, 2, 1000)
	started = appendBytes(started, 5, []byte("build"))
	ev1 := appendBytes(nil, 5, started)

	finished := appendVarint(nil, 1, 1)
	finished = appendVarint(finished, 2, 2000)
	ev2 := appendBytes(nil, 14, finished)

	packedIDs := binary.AppendUvarint(nil, 10)
	packedIDs = binary.AppendUvarint(packedIDs, 11)
	packedIDs = binary.AppendUvarint(packedIDs, 12)
	ws := appendVarint(nil, 2, 204800)
	wm := appendVarint(nil, 2, 5555)
	wm = appendBytes(wm, 3, []byte("KotlinCompile"))
	wm = appendVarint(wm, 4, 1)
	wm = appendBytes(wm, 7, ws)
	wm = appendBytes(wm, 8, packedIDs)
	wm = appendVarint(wm, 11, 4)
	bm := appendBytes(nil, 9, wm)
	ev3 := appendBytes(nil, 24, bm)
	ev3 = appendVarint(ev3, 20, 1) // lastMessage=true

	stream := append(frame(ev1), frame(ev2)...)
	stream = append(stream, frame(ev3)...)
	got, err := ParseBinaryStream(bytes.NewReader(stream))
	if err != nil || len(got) != 1 {
		t.Fatalf("got=%+v err=%v", got, err)
	}
	if got[0].BazelDetails == nil || len(got[0].BazelDetails.Metrics.Workers) != 1 {
		t.Fatalf("details=%+v", got[0].BazelDetails)
	}
	w := got[0].BazelDetails.Metrics.Workers[0]
	if w.ProcessID != 5555 || w.Mnemonic != "KotlinCompile" || !w.IsMultiplex || w.WorkerIDCount != 3 || w.PeakMemoryKB == nil || *w.PeakMemoryKB != 204800 {
		t.Fatalf("worker=%+v", w)
	}
}
