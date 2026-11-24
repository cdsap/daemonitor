package bep

// The #581 wire prototype stays test-only; Watcher still ingests JSON only.
import (
	"bufio"
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"testing"
)

const spikeFrameLimit = 8 * 1024 * 1024

type spikeField struct {
	number, kind, value uint64
	data                []byte
}

func spikeFrames(r io.Reader) ([][]byte, error) {
	br := bufio.NewReader(r)
	var out [][]byte
	for {
		size, err := binary.ReadUvarint(br)
		if err == io.EOF {
			return out, nil
		}
		if err != nil {
			return nil, err
		}
		if size == 0 || size > spikeFrameLimit {
			return nil, fmt.Errorf("invalid frame size %d", size)
		}
		frame := make([]byte, int(size))
		if _, err := io.ReadFull(br, frame); err != nil {
			return nil, err
		}
		out = append(out, frame)
	}
}

func spikeFields(data []byte) ([]spikeField, error) {
	var out []spikeField
	for len(data) > 0 {
		tag, n := binary.Uvarint(data)
		if n <= 0 || tag>>3 == 0 || tag>>3 > (1<<29)-1 {
			return nil, fmt.Errorf("invalid tag")
		}
		data = data[n:]
		f := spikeField{number: tag >> 3, kind: tag & 7}
		switch f.kind {
		case 0:
			f.value, n = binary.Uvarint(data)
			if n <= 0 {
				return nil, fmt.Errorf("invalid varint")
			}
			data = data[n:]
		case 1, 5:
			size := 8
			if f.kind == 5 {
				size = 4
			}
			if len(data) < size {
				return nil, io.ErrUnexpectedEOF
			}
			f.data, data = data[:size], data[size:]
		case 2:
			size, n := binary.Uvarint(data)
			if n <= 0 || size > uint64(len(data)-n) {
				return nil, io.ErrUnexpectedEOF
			}
			data = data[n:]
			f.data, data = data[:int(size)], data[int(size):]
		default:
			return nil, fmt.Errorf("unsupported wire type %d", f.kind)
		}
		out = append(out, f)
	}
	return out, nil
}

type spikeMapping struct {
	number   uint64
	name     string
	children []spikeMapping
}

func spikeCompare(t *testing.T, data []byte, object map[string]any, mapping []spikeMapping) {
	t.Helper()
	fields, err := spikeFields(data)
	if err != nil {
		t.Fatal(err)
	}
	for _, m := range mapping {
		raw, present := object[m.name]
		if !present {
			for _, f := range fields {
				if f.number == m.number && (m.children != nil || f.value != 0 || len(f.data) != 0) {
					t.Fatalf("%s: nondefault wire field absent from JSON", m.name)
				}
			}
			continue
		} // Proto3 JSON omits default scalars.
		values, repeated := raw.([]any)
		if !repeated {
			values = []any{raw}
		}
		var matched []spikeField
		for _, f := range fields {
			if f.number == m.number {
				matched = append(matched, f)
			}
		}
		if len(values) != len(matched) {
			t.Fatalf("%s field %d: JSON count %d != wire count %d", m.name, m.number, len(values), len(matched))
		}
		for i, want := range values {
			f := matched[i]
			if m.children != nil {
				if f.kind != 2 {
					t.Fatalf("%s: expected message", m.name)
				}
				spikeCompare(t, f.data, want.(map[string]any), m.children)
			} else if f.kind == 0 {
				expected := fmt.Sprint(want)
				if expected == "true" || expected == "PASSED" {
					expected = "1"
				}
				if expected == "LOADING_FAILURE" {
					expected = "5"
				}
				if strconv.FormatUint(f.value, 10) != expected {
					t.Fatalf("%s: wire=%d JSON=%v", m.name, f.value, want)
				}
			} else if f.kind != 2 || string(f.data) != fmt.Sprint(want) {
				t.Fatalf("%s: wire string %q != JSON %v", m.name, f.data, want)
			}
		}
	}
}

func TestRichSpikeJSONBinaryParity(t *testing.T) {
	mapping := []spikeMapping{
		{1, "id", []spikeMapping{{7, "testSummary", []spikeMapping{{1, "label", nil}, {2, "configuration", []spikeMapping{{1, "id", nil}}}}}}},
		{5, "started", []spikeMapping{{1, "uuid", nil}, {2, "startTimeMillis", nil}, {3, "buildToolVersion", nil}, {5, "command", nil}, {8, "serverPid", nil}}},
		{14, "finished", []spikeMapping{{2, "finishTimeMillis", nil}, {3, "exitCode", []spikeMapping{{1, "name", nil}, {2, "code", nil}}}}},
		{24, "buildMetrics", []spikeMapping{
			{1, "actionSummary", []spikeMapping{{1, "actionsCreated", nil}, {2, "actionsExecuted", nil}, {6, "runnerCount", []spikeMapping{{1, "name", nil}, {2, "count", nil}, {3, "execKind", nil}}}, {7, "actionCacheStatistics", []spikeMapping{{3, "hits", nil}, {4, "misses", nil}}}}},
			{2, "memoryMetrics", []spikeMapping{{1, "usedHeapSizePostBuild", nil}, {2, "peakPostGcHeapSize", nil}, {3, "garbageMetrics", []spikeMapping{{1, "type", nil}, {2, "garbageCollected", nil}}}}},
			{5, "timingMetrics", []spikeMapping{{1, "cpuTimeInMs", nil}, {2, "wallTimeInMs", nil}, {3, "analysisPhaseTimeInMs", nil}, {4, "executionPhaseTimeInMs", nil}}},
			{11, "workerPoolMetrics", []spikeMapping{}},
		}},
		{22, "structuredCommandLine", []spikeMapping{{1, "commandLineLabel", nil}, {2, "sections", []spikeMapping{{1, "sectionLabel", nil}, {2, "chunkList", []spikeMapping{{1, "chunk", nil}}}}}}},
		{9, "testSummary", []spikeMapping{{1, "totalRunCount", nil}, {5, "overallStatus", nil}, {6, "totalNumCached", nil}, {9, "totalRunDurationMillis", nil}, {10, "runCount", nil}, {15, "attemptCount", nil}}},
		{4, "aborted", []spikeMapping{{1, "reason", nil}, {2, "description", nil}}},
		{3, "progress", []spikeMapping{{1, "stdout", nil}, {2, "stderr", nil}}},
		{20, "lastMessage", nil},
	}
	for _, name := range []string{"rich_success", "rich_failed"} {
		t.Run(name, func(t *testing.T) {
			data, err := os.ReadFile("testdata/" + name + ".bin")
			if err != nil {
				t.Fatal(err)
			}
			frames, err := spikeFrames(bytes.NewReader(data))
			if err != nil {
				t.Fatal(err)
			}
			ndjson, err := os.ReadFile("testdata/" + name + ".ndjson")
			if err != nil {
				t.Fatal(err)
			}
			lines := strings.Split(strings.TrimSpace(string(ndjson)), "\n")
			if len(frames) != len(lines) {
				t.Fatalf("frames=%d lines=%d", len(frames), len(lines))
			}
			finish, metrics, tests, aborted := -1, -1, 0, 0
			seen := map[string]bool{}
			for i, line := range lines {
				var object map[string]any
				if err := json.Unmarshal([]byte(line), &object); err != nil {
					t.Fatal(err)
				}
				spikeCompare(t, frames[i], object, mapping)
				for k := range object {
					seen[k] = true
				}
				if _, ok := object["finished"]; ok {
					finish = i
				}
				if _, ok := object["buildMetrics"]; ok {
					metrics = i
				}
				if _, ok := object["testSummary"]; ok {
					tests++
				}
				if _, ok := object["aborted"]; ok {
					aborted++
				}
			}
			if finish < 0 || metrics <= finish || !seen["started"] || !seen["structuredCommandLine"] || !seen["lastMessage"] {
				t.Fatalf("missing lifecycle events or late metrics: %v finish=%d metrics=%d", seen, finish, metrics)
			}
			if name == "rich_success" && tests != 1 {
				t.Fatalf("test summaries=%d", tests)
			}
			if name == "rich_failed" && aborted != 1 {
				t.Fatalf("aborted=%d", aborted)
			}
			builds, err := ParseJSONStream(bytes.NewReader(ndjson))
			if err != nil || len(builds) != 1 {
				t.Fatalf("existing JSON parser: builds=%v err=%v", builds, err)
			}
			want := "SUCCESS"
			if name == "rich_failed" {
				want = "FAILED"
			}
			if string(builds[0].FinalStatus) != want {
				t.Fatalf("status=%s want=%s", builds[0].FinalStatus, want)
			}
		})
	}
}

func TestRichSpikeWireBounds(t *testing.T) {
	for _, data := range [][]byte{{0x80}, bytes.Repeat([]byte{0xff}, 11), {0}, {3, 0x08}, binary.AppendUvarint(nil, spikeFrameLimit+1)} {
		if _, err := spikeFrames(bytes.NewReader(data)); err == nil {
			t.Fatalf("accepted malformed frame %x", data)
		}
	}
	if _, err := spikeFrames(bytes.NewReader(binary.AppendUvarint(nil, spikeFrameLimit+1))); err == nil || !strings.Contains(err.Error(), "invalid frame size") {
		t.Fatalf("oversized frame must fail before reading or allocating payload: %v", err)
	}
	for _, data := range [][]byte{{0}, {0x08, 0x80}, {0x12, 0x05, 0x01}, {0x09, 0x01}, {0x0d, 0x01}, {0x0b}, {0x0f}} {
		if _, err := spikeFields(data); err == nil {
			t.Fatalf("accepted malformed field %x", data)
		}
	}
	data := []byte{0x08, 1, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0x1a, 1, 'x', 0x25, 0, 0, 0, 0}
	if fields, err := spikeFields(data); err != nil || len(fields) != 4 {
		t.Fatalf("unknown fields: %v %v", fields, err)
	}
}
