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

// Wire fixtures use Bazel's build_event_stream.proto field numbers. These
// encoders are test-only; no protobuf runtime or generated code is needed.
func wireInt(field int, value uint64) []byte {
	b := binary.AppendUvarint(nil, uint64(field<<3))
	return binary.AppendUvarint(b, value)
}
func wireBytes(field int, value []byte) []byte {
	b := binary.AppendUvarint(nil, uint64(field<<3|2))
	b = binary.AppendUvarint(b, uint64(len(value)))
	return append(b, value...)
}
func wireString(field int, value string) []byte { return wireBytes(field, []byte(value)) }
func wireMessage(fields ...[]byte) []byte       { return bytes.Join(fields, nil) }
func wireFrame(event []byte) []byte {
	return append(binary.AppendUvarint(nil, uint64(len(event))), event...)
}
func binaryStart(id string) []byte {
	return wireFrame(wireBytes(5, wireMessage(wireString(1, id), wireInt(2, 1000), wireString(5, "test"), wireString(6, "/ws"), wireString(7, "/ws"), wireInt(8, 42))))
}
func binaryFinish(code uint64) []byte {
	// Proto3 omits zero scalars: an empty ExitCode message means code=0.
	var exit []byte
	if code != 0 {
		exit = wireMessage(wireString(1, "INTERRUPTED"), wireInt(2, code))
	}
	return wireFrame(wireBytes(14, wireMessage(wireInt(2, 2500), wireBytes(3, exit))))
}

func TestBinaryJSONParity(t *testing.T) {
	for _, tc := range []struct {
		name, finish string
		payload      []byte
	}{
		{"success", `{"finishTimeMillis":"2500","exitCode":{"code":0}}`, binaryFinish(0)},
		{"interrupted", `{"finishTimeMillis":"2500","exitCode":{"name":"INTERRUPTED","code":8}}`, binaryFinish(8)},
		{"unknown", `{}`, wireFrame(wireBytes(14, nil))},
	} {
		t.Run(tc.name, func(t *testing.T) {
			jsonStream := `{"started":{"uuid":"parity","startTimeMillis":"1000","command":"test","workingDirectory":"/ws","workspaceDirectory":"/ws","serverPid":"42"}}` + "\n" +
				`{"structuredCommandLine":{"commandLineLabel":"canonical"}}` + "\n" +
				`{"testSummary":{"overallStatus":"FAILED"}}` + "\n" +
				`{"aborted":{"reason":"USER_INTERRUPTED"}}` + "\n" +
				`{"finished":` + tc.finish + "}\n" + `{"buildMetrics":{"memoryMetrics":{"peakPostGcHeapSize":"1024"}}}` + "\n"
			// Auxiliary payloads do not close an invocation or populate OS samples,
			// matching the settled JSON rows (Bazel heap is not sampled process RSS).
			stream := wireMessage(binaryStart("parity"), wireFrame(wireBytes(22, wireString(1, "canonical"))), wireFrame(wireBytes(9, wireInt(5, 4))), wireFrame(wireBytes(4, wireInt(1, 1))), tc.payload, wireFrame(wireBytes(24, wireBytes(2, wireInt(1, 1024)))))
			want, err := ParseJSONStream(strings.NewReader(jsonStream))
			if err != nil {
				t.Fatal(err)
			}
			got, err := ParseBinaryStream(bytes.NewReader(stream))
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("binary=%+v JSON=%+v", got, want)
			}
		})
	}
}

func TestBinaryEverySplit(t *testing.T) {
	// Force a multi-byte length prefix, then exercise every prefix and payload
	// boundary, including the finish event and a second build in the same file.
	first := binaryStart(strings.Repeat("x", 200))
	stream := wireMessage(first, binaryFinish(0), binaryStart("next"), binaryFinish(8))
	for split := 0; split <= len(stream); split++ {
		state := &partial{}
		got, left, err := ConsumeBinaryBytes(state, nil, stream[:split])
		if err != nil {
			t.Fatal(err)
		}
		if split < len(first)+len(binaryFinish(0)) && len(got) != 0 {
			t.Fatalf("premature close at %d", split)
		}
		rest, left, err := ConsumeBinaryBytes(state, left, stream[split:])
		got = append(got, rest...)
		if err != nil || len(left) != 0 || len(got) != 2 || got[0].FinalStatus != builds.StatusSuccess || got[1].BuildID != "next" || got[1].FinalStatus != builds.StatusInterrupted {
			t.Fatalf("split %d: got=%v left=%x err=%v", split, got, left, err)
		}
	}
}

func TestWatcherBinaryAppendAndRewrite(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bep.bin")
	stream := wireMessage(binaryStart("first"), binaryFinish(0))
	w := NewWatcher()
	w.ExtraPaths = func() []Sink { return []Sink{{Path: path, Format: FormatBinary}} }
	poll := func(want string) {
		t.Helper()
		got, err := w.Poll(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		if want == "" && len(got) == 0 {
			return
		}
		if len(got) != 1 || got[0].BuildID != want {
			t.Fatalf("got=%v want=%q", got, want)
		}
	}
	if err := os.WriteFile(path, stream[:len(stream)-1], 0600); err != nil {
		t.Fatal(err)
	}
	poll("")
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.Write(stream[len(stream)-1:]); err != nil {
		t.Fatal(err)
	}
	f.Close()
	poll("first")
	poll("")
	// Bazel reuses sink paths. A truncate and rewrite can finish between polls,
	// even with a larger file, so size alone cannot identify the new stream.
	if err := os.WriteFile(path, wireMessage(binaryStart("second-longer"), binaryFinish(0)), 0600); err != nil {
		t.Fatal(err)
	}
	poll("second-longer")
	if err := os.WriteFile(path, []byte{0x80}, 0600); err != nil {
		t.Fatal(err)
	}
	poll("")
	if err := os.WriteFile(path, wireMessage(binaryStart("third"), binaryFinish(8)), 0600); err != nil {
		t.Fatal(err)
	}
	poll("third")
}

func TestBinaryMalformedPayloadRecovery(t *testing.T) {
	for name, bad := range map[string][]byte{
		"invalid tag":              {0},
		"truncated nested message": wireBytes(14, []byte{0x1a, 0x02, 0x08}),
		"wrong exit wire type":     wireBytes(14, wireBytes(3, wireBytes(2, nil))),
		"truncated fixed64":        {0x79, 0},
		"truncated fixed32":        {0x7d, 0},
	} {
		t.Run(name, func(t *testing.T) {
			state := &partial{}
			stream := wireMessage(binaryStart("before"), binaryFinish(0), binaryStart("invalid"), wireFrame(bad), binaryFinish(0), binaryStart("after"), binaryFinish(8))
			got, left, err := ConsumeBinaryBytes(state, nil, stream)
			if err == nil || len(left) != 0 || len(got) != 2 || got[0].BuildID != "before" || got[1].BuildID != "after" {
				t.Fatalf("got=%v left=%x err=%v", got, left, err)
			}
		})
	}
}

func TestBinaryInvalidLength(t *testing.T) {
	for name, prefix := range map[string][]byte{
		"overflow":  bytes.Repeat([]byte{0xff}, 10),
		"too large": binary.AppendUvarint(nil, maxBinaryEventSize+1),
	} {
		t.Run(name, func(t *testing.T) {
			state := &partial{BuildID: "incomplete"}
			got, left, err := ConsumeBinaryBytes(state, nil, prefix)
			if err == nil || len(got) != 0 || state.BuildID != "" {
				t.Fatalf("got=%v state=%+v err=%v", got, state, err)
			}
			// An invalid length cannot safely resynchronize inside arbitrary payloads.
			// It also must not retain unbounded bytes from later polls.
			got, left, err = ConsumeBinaryBytes(state, left, wireMessage(binaryStart("untrusted"), binaryFinish(0)))
			if err == nil || len(got) != 0 || len(left) > 10 {
				t.Fatalf("got=%v left=%x err=%v", got, left, err)
			}
		})
	}
}

func TestBinaryTruncatedStream(t *testing.T) {
	start := binaryStart("truncated")
	stream := wireMessage(start, binaryFinish(0))
	for cut := len(start); cut < len(stream); cut++ {
		got, err := ParseBinaryStream(bytes.NewReader(stream[:cut]))
		if len(got) != 0 {
			t.Fatalf("cut %d emitted %v (err %v)", cut, got, err)
		}
	}
}

func TestBinaryTimestampAndUnknownFields(t *testing.T) {
	started := wireBytes(5, wireMessage(
		wireString(1, "timestamp"), wireInt(2, 1),
		wireBytes(9, wireMessage(wireInt(1, 1700000000), wireInt(2, 123000000))),
	))
	start := wireFrame(wireMessage(started, wireInt(99, 1),
		[]byte{0xa5, 0x06, 0, 0, 0, 0},             // Unknown fixed32 field 100.
		[]byte{0xa9, 0x06, 0, 0, 0, 0, 0, 0, 0, 0}, // Unknown fixed64 field 101.
	))
	finish := wireFrame(wireBytes(14, wireMessage(wireInt(2, 2), wireBytes(5, wireMessage(wireInt(1, 1700000001), wireInt(2, 623000000))), wireBytes(3, nil))))
	got, err := ParseBinaryStream(bytes.NewReader(wireMessage(start, finish)))
	want, jsonErr := ParseJSONStream(strings.NewReader(`{"started":{"uuid":"timestamp","startTimeMillis":"1","startTime":"2023-11-14T22:13:20.123Z"}}` + "\n" + `{"finished":{"finishTimeMillis":"2","finishTime":"2023-11-14T22:13:21.623Z","exitCode":{"code":0}}}` + "\n"))
	if err != nil || jsonErr != nil || !reflect.DeepEqual(got, want) || len(got) != 1 || *got[0].DurationSeconds != 1.5 {
		t.Fatalf("got=%v want=%v err=%v JSON err=%v", got, want, err, jsonErr)
	}
}

func TestBinaryNewStartReplacesIncompleteBuild(t *testing.T) {
	stream := wireMessage(binaryStart("abandoned"), binaryStart("next"), binaryFinish(0))
	got, err := ParseBinaryStream(bytes.NewReader(stream))
	if err != nil || len(got) != 1 || got[0].BuildID != "next" {
		t.Fatalf("got=%v err=%v", got, err)
	}
}

func TestWatcherBinaryMalformedEventKeepsOtherBuilds(t *testing.T) {
	path := filepath.Join(t.TempDir(), "events.bep")
	stream := wireMessage(binaryStart("before"), binaryFinish(0), wireFrame([]byte{0}), binaryStart("after"), binaryFinish(8))
	if err := os.WriteFile(path, stream, 0600); err != nil {
		t.Fatal(err)
	}
	w := NewWatcher()
	w.ExtraPaths = func() []Sink { return []Sink{{Path: path, Format: FormatBinary}} }
	got, err := w.Poll(t.Context())
	if err != nil || len(got) != 2 || got[0].BuildID != "before" || got[1].BuildID != "after" {
		t.Fatalf("got=%v err=%v", got, err)
	}
}

func TestWatcherBinaryAndJSONDeduplicate(t *testing.T) {
	w := NewWatcher()
	w.ExtraPaths = func() []Sink {
		var sinks []Sink
		for _, fixture := range []struct {
			path   string
			format Format
		}{
			{"testdata/build_success.ndjson", FormatJSON},
			{"testdata/build_success.bep", FormatBinary},
		} {
			path, err := filepath.Abs(fixture.path)
			if err != nil {
				t.Fatal(err)
			}
			sinks = append(sinks, Sink{Path: path, Format: fixture.format})
		}
		return sinks
	}
	got, err := w.Poll(t.Context())
	if err != nil || len(got) != 1 || got[0].BuildID != "11111111-aaaa-bbbb-cccc-000000000001" {
		t.Fatalf("got=%v err=%v", got, err)
	}
	got, err = w.Poll(t.Context())
	if err != nil || len(got) != 0 {
		t.Fatalf("duplicate: got=%v err=%v", got, err)
	}
}
