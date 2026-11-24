package bep

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"github.com/cdsap/daemonitor/cored/internal/builds"
)

// Match the JSON reader's event limit without pulling in Bazel's generated
// protobuf graph. Unknown fields are skipped using the protobuf wire encoding.
const maxBinaryEventSize = 8 * 1024 * 1024

// ParseBinaryStream reads Bazel's varint-length-delimited BuildEvent stream.
// EOF inside a frame is incomplete, rather than evidence of a build outcome.
func ParseBinaryStream(r io.Reader) ([]builds.Build, error) {
	reader := bufio.NewReader(r)
	state := &partial{}
	var closed []builds.Build
	for {
		size, err := binary.ReadUvarint(reader)
		if err == io.EOF {
			return closed, nil
		}
		if err != nil {
			return closed, err
		}
		if size > maxBinaryEventSize {
			return closed, fmt.Errorf("BEP event exceeds %d bytes", maxBinaryEventSize)
		}
		event := make([]byte, int(size))
		if _, err := io.ReadFull(reader, event); err != nil {
			return closed, err
		}
		b, ok, err := applyBinaryEvent(state, event)
		if err != nil {
			return closed, err
		}
		if ok {
			closed = append(closed, b)
			*state = partial{}
		}
	}
}

// ConsumeBinaryBytes retains incomplete length prefixes and payloads verbatim
// until a later poll. Malformed payloads invalidate the current invocation, but
// their frame boundaries allow subsequent builds to be read safely.
func ConsumeBinaryBytes(state *partial, pending, chunk []byte) (closed []builds.Build, leftover []byte, err error) {
	if state == nil {
		state = &partial{}
	}
	buf := append(append([]byte{}, pending...), chunk...)
	for len(buf) > 0 {
		size, n := binary.Uvarint(buf)
		if n == 0 && len(buf) < binary.MaxVarintLen64 {
			return closed, buf, err
		}
		if n <= 0 || size > maxBinaryEventSize {
			*state = partial{}
			// Keep the bad prefix: without a frame boundary resynchronizing would
			// treat arbitrary payload bytes as build events. A file reset clears it.
			return closed, append([]byte(nil), buf[:min(len(buf), 10)]...), errors.New("invalid BEP frame length")
		}
		if size > uint64(len(buf)-n) {
			return closed, buf, err
		}
		event := buf[n : n+int(size)]
		buf = buf[n+int(size):]
		b, ok, eventErr := applyBinaryEvent(state, event)
		if eventErr != nil {
			*state = partial{}
			if err == nil {
				err = eventErr
			}
			continue
		}
		if ok {
			closed = append(closed, b)
			*state = partial{}
		}
	}
	return closed, nil, err
}

// The field numbers below come from Bazel's build_event_stream.proto:
// https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/buildeventstream/proto/build_event_stream.proto
// The retained Build model currently uses started and finished. Like JSON,
// buildMetrics (24), structuredCommandLine (22), testSummary (9), and aborted
// (4) do not populate OS samples or close builds. In particular an Aborted
// payload may describe just one target, not the invocation's final outcome.
func applyBinaryEvent(state *partial, event []byte) (builds.Build, bool, error) {
	fields, err := decodeWire(event)
	if err != nil {
		return builds.Build{}, false, err
	}
	// BuildEvent.payload is a oneof; use the last payload on the wire.
	var payload wireField
	for _, f := range fields {
		switch f.number {
		case 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 32:
			payload = f
		}
	}
	if payload.number != 5 && payload.number != 14 {
		return builds.Build{}, false, nil
	}
	if payload.kind != 2 {
		return builds.Build{}, false, errors.New("invalid BEP payload wire type")
	}
	msg, err := decodeWire(payload.data)
	if err != nil {
		return builds.Build{}, false, err
	}
	if payload.number == 5 {
		if err := msg.check(map[uint64]byte{1: 2, 2: 0, 5: 2, 6: 2, 7: 2, 8: 0, 9: 2}); err != nil {
			return builds.Build{}, false, err
		}
		start, err := msg.timeMillis(2, 9)
		if err != nil {
			return builds.Build{}, false, err
		}
		*state = partial{BuildID: string(msg.field(1).data), StartTimeMs: start, Command: string(msg.field(5).data), WorkingDirectory: string(msg.field(6).data), ProjectPath: string(msg.field(7).data), DaemonPID: int64(msg.field(8).value)}
		return builds.Build{}, false, nil
	}
	if err := msg.check(map[uint64]byte{1: 0, 2: 0, 3: 2, 5: 2}); err != nil {
		return builds.Build{}, false, err
	}
	end, err := msg.timeMillis(2, 5)
	if err != nil {
		return builds.Build{}, false, err
	}
	var exit *exitCode
	if f := msg.field(3); f.number != 0 {
		code, err := decodeWire(f.data)
		if err != nil {
			return builds.Build{}, false, err
		}
		if err := code.check(map[uint64]byte{1: 2, 2: 0}); err != nil {
			return builds.Build{}, false, err
		}
		// Scalar code=0 is omitted by proto3 serializers. Presence of the
		// ExitCode message, including an empty one, still establishes success.
		value := int(int32(code.field(2).value))
		exit = &exitCode{Name: string(code.field(1).data), Code: &value}
	}
	var overall *bool
	if f := msg.field(1); f.number != 0 {
		value := f.value != 0
		overall = &value
	}
	if state.BuildID == "" {
		return builds.Build{}, false, nil
	}
	state.EndTimeMs = end
	state.Status = statusFromExit(exit, overall)
	state.Finished = true
	return state.toBuild(), true, nil
}

type wireField struct {
	number uint64
	kind   byte
	value  uint64
	data   []byte
}
type wireMessageFields []wireField

func decodeWire(data []byte) (wireMessageFields, error) {
	var fields wireMessageFields
	for len(data) > 0 {
		tag, n := binary.Uvarint(data)
		if n <= 0 || tag>>3 == 0 || tag>>3 > 1<<29-1 {
			return nil, errors.New("invalid protobuf tag")
		}
		data = data[n:]
		f := wireField{number: tag >> 3, kind: byte(tag & 7)}
		switch f.kind {
		case 0:
			value, n := binary.Uvarint(data)
			if n <= 0 {
				return nil, errors.New("invalid protobuf varint")
			}
			f.value = value
			data = data[n:]
		case 1, 5:
			size := 8
			if f.kind == 5 {
				size = 4
			}
			if len(data) < size {
				return nil, io.ErrUnexpectedEOF
			}
			data = data[size:]
		case 2:
			size, n := binary.Uvarint(data)
			if n <= 0 || size > uint64(len(data)-n) {
				return nil, io.ErrUnexpectedEOF
			}
			data = data[n:]
			f.data = data[:int(size)]
			data = data[int(size):]
		default:
			return nil, errors.New("unsupported protobuf wire type")
		}
		fields = append(fields, f)
	}
	return fields, nil
}
func (m wireMessageFields) field(number uint64) wireField {
	for i := len(m) - 1; i >= 0; i-- {
		if m[i].number == number {
			return m[i]
		}
	}
	return wireField{}
}
func (m wireMessageFields) check(types map[uint64]byte) error {
	for _, f := range m {
		if kind, ok := types[f.number]; ok && kind != f.kind {
			return fmt.Errorf("invalid wire type for BEP field %d", f.number)
		}
	}
	return nil
}
func (m wireMessageFields) timeMillis(legacy, timestamp uint64) (int64, error) {
	if f := m.field(timestamp); f.number != 0 {
		ts, err := decodeWire(f.data)
		if err != nil {
			return 0, err
		}
		if err := ts.check(map[uint64]byte{1: 0, 2: 0}); err != nil {
			return 0, err
		}
		seconds, nanos := int64(ts.field(1).value), int64(ts.field(2).value)
		if seconds < -62135596800 || seconds > 253402300799 || nanos < 0 || nanos >= 1000000000 {
			return 0, errors.New("invalid BEP timestamp")
		}
		return seconds*1000 + nanos/1000000, nil
	}
	return int64(m.field(legacy).value), nil
}
