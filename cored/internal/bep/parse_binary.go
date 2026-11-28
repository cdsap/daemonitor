package bep

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/ptr"
)

// Match the JSON reader's event limit without pulling in Bazel's generated
// protobuf graph. Unknown fields are skipped using the protobuf wire encoding.
const maxBinaryEventSize = 8 * 1024 * 1024

// ParseBinaryStream reads Bazel's varint-length-delimited BuildEvent stream,
// retaining post-finished events such as buildMetrics through lastMessage/EOF.
// EOF inside a frame is incomplete, rather than evidence of a build outcome.
func ParseBinaryStream(r io.Reader) ([]builds.Build, error) {
	reader := bufio.NewReader(r)
	state := &partial{sourceFormat: string(FormatBinary)}
	var closed []builds.Build
	for {
		size, err := binary.ReadUvarint(reader)
		if errors.Is(err, io.EOF) {
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
			closed = coalesceBuilds(closed, b)
		}
		if state.lastMessage {
			*state = partial{ambientEnvNames: state.ambientEnvNames, sourceFormat: string(FormatBinary)}
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
	state.sourceFormat = string(FormatBinary)
	buf := append(append([]byte{}, pending...), chunk...)
	for len(buf) > 0 {
		size, n := binary.Uvarint(buf)
		if n == 0 && len(buf) < binary.MaxVarintLen64 {
			return closed, buf, err
		}
		if n <= 0 || size > maxBinaryEventSize {
			*state = partial{ambientEnvNames: state.ambientEnvNames, sourceFormat: string(FormatBinary)}
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
			*state = partial{ambientEnvNames: state.ambientEnvNames, sourceFormat: string(FormatBinary)}
			if err == nil {
				err = eventErr
			}
			continue
		}
		if ok {
			closed = coalesceBuilds(closed, b)
		}
		if state.lastMessage {
			*state = partial{ambientEnvNames: state.ambientEnvNames, sourceFormat: string(FormatBinary)}
		}
	}
	return closed, nil, err
}

// The field numbers below are pinned to Bazel 8.4.2 build_event_stream.proto
// (verified in docs/plans/2026-11-24-bazel-rich-bep-spike.md):
//
//	id(1), progress(3), aborted(4), started(5), test_summary(9), finished(14),
//	last_message(20), structured_command_line(22), build_metrics(24).
func applyBinaryEvent(state *partial, event []byte) (builds.Build, bool, error) {
	fields, err := decodeWire(event)
	if err != nil {
		return builds.Build{}, false, err
	}
	if state.sourceFormat == "" {
		state.sourceFormat = string(FormatBinary)
	}
	var idField wireField
	var lastMsg bool
	// BuildEvent.payload is a oneof; use the last payload on the wire.
	var payload wireField
	for _, f := range fields {
		switch f.number {
		case 1:
			if f.kind == 2 {
				idField = f
			}
		case 20:
			if f.kind == 0 {
				lastMsg = f.value != 0
			}
		case 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 32:
			payload = f
		}
	}

	switch payload.number {
	case 5: // started
		if payload.kind != 2 {
			return builds.Build{}, false, errors.New("invalid BEP payload wire type")
		}
		msg, err := decodeWire(payload.data)
		if err != nil {
			return builds.Build{}, false, err
		}
		if err := msg.check(map[uint64]byte{1: 2, 2: 0, 3: 2, 5: 2, 6: 2, 7: 2, 8: 0, 9: 2}); err != nil {
			return builds.Build{}, false, err
		}
		start, err := msg.timeMillis(2, 9)
		if err != nil {
			return builds.Build{}, false, err
		}
		*state = partial{
			ambientEnvNames:  state.ambientEnvNames,
			sourceFormat:     string(FormatBinary),
			BuildID:          string(msg.field(1).data),
			BazelVersion:     string(msg.field(3).data),
			StartTimeMs:      start,
			Command:          string(msg.field(5).data),
			WorkingDirectory: string(msg.field(6).data),
			ProjectPath:      string(msg.field(7).data),
			DaemonPID:        int64(msg.field(8).value),
		}
		return builds.Build{}, false, nil

	case 14: // finished
		if payload.kind != 2 {
			return builds.Build{}, false, errors.New("invalid BEP payload wire type")
		}
		msg, err := decodeWire(payload.data)
		if err != nil {
			return builds.Build{}, false, err
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
		if lastMsg {
			state.lastMessage = true
		}
		return state.toBuild(), true, nil
	}

	enriched := false
	switch payload.number {
	case 3: // progress
		if payload.kind == 2 {
			if msg, err := decodeWire(payload.data); err == nil {
				if stderr := msg.field(2); stderr.number != 0 && stderr.kind == 2 {
					state.recordProgressError(string(stderr.data))
				}
			}
		}
	case 4: // aborted
		if payload.kind == 2 {
			if msg, err := decodeWire(payload.data); err == nil {
				reason := abortedReasonName(msg.field(1).value)
				desc := string(msg.field(2).data)
				state.recordAborted(reason, desc)
				enriched = true
			}
		}
	case 9: // testSummary
		if payload.kind == 2 {
			if applyBinaryTestSummary(state, idField.data, payload.data) {
				enriched = true
			}
		}
	case 22: // structuredCommandLine
		if payload.kind == 2 {
			applyBinaryStructuredCommandLine(state, payload.data)
		}
	case 24: // buildMetrics
		if payload.kind == 2 {
			if applyBinaryBuildMetrics(state, payload.data) {
				enriched = true
			}
		}
	}

	if lastMsg {
		state.lastMessage = true
	}
	if state.Finished && state.BuildID != "" && (enriched || lastMsg) {
		return state.toBuild(), true, nil
	}
	return builds.Build{}, false, nil
}

func applyBinaryStructuredCommandLine(state *partial, data []byte) {
	msg, err := decodeWire(data)
	if err != nil {
		return
	}
	label := string(msg.field(1).data)
	var cmdChunks, residualChunks []string
	for _, secField := range msg {
		if secField.number != 2 || secField.kind != 2 {
			continue
		}
		sec, err := decodeWire(secField.data)
		if err != nil {
			continue
		}
		secLabel := string(sec.field(1).data)
		if secLabel != "command" && secLabel != "residual" {
			continue
		}
		clField := sec.field(2)
		if clField.number == 0 || clField.kind != 2 {
			continue
		}
		cl, err := decodeWire(clField.data)
		if err != nil {
			continue
		}
		for _, chunkField := range cl {
			if chunkField.number != 1 || chunkField.kind != 2 {
				continue
			}
			if secLabel == "command" {
				cmdChunks = append(cmdChunks, string(chunkField.data))
			} else {
				residualChunks = append(residualChunks, string(chunkField.data))
			}
		}
	}
	state.recordCommandLine(label, cmdChunks, residualChunks)
}

func applyBinaryTestSummary(state *partial, idData, data []byte) bool {
	msg, err := decodeWire(data)
	if err != nil {
		return false
	}
	label, configID := "", ""
	if len(idData) > 0 {
		if idMsg, err := decodeWire(idData); err == nil {
			if tsIDField := idMsg.field(7); tsIDField.number != 0 && tsIDField.kind == 2 {
				if tsID, err := decodeWire(tsIDField.data); err == nil {
					label = string(tsID.field(1).data)
					if cfgField := tsID.field(2); cfgField.number != 0 && cfgField.kind == 2 {
						if cfg, err := decodeWire(cfgField.data); err == nil {
							configID = string(cfg.field(1).data)
						}
					}
				}
			}
		}
	}
	var durMs *int64
	if durField := msg.field(12); durField.number != 0 && durField.kind == 2 {
		if d, ok := decodeProtoDurationMillis(durField.data); ok {
			durMs = &d
		}
	}
	if durMs == nil {
		if f := msg.field(9); f.number != 0 && f.kind == 0 {
			v := int64(f.value)
			if v >= 0 {
				durMs = &v
			}
		}
	}
	state.recordTestSummary(builds.BazelTestSummary{
		Label:              label,
		ConfigID:           configID,
		OverallStatus:      testStatusName(msg.field(5).value),
		TotalRunCount:      int64(msg.field(1).value),
		RunCount:           int64(msg.field(10).value),
		ShardCount:         int64(msg.field(11).value),
		AttemptCount:       int64(msg.field(15).value),
		TotalNumCached:     int64(msg.field(6).value),
		TotalRunDurationMs: durMs,
	})
	return true
}

func applyBinaryBuildMetrics(state *partial, data []byte) bool {
	msg, err := decodeWire(data)
	if err != nil {
		return false
	}
	if f := msg.field(1); f.number != 0 && f.kind == 2 {
		if asMsg, err := decodeWire(f.data); err == nil {
			as := &builds.BazelActionSummary{
				Created:                    asMsg.optionalNonNeg(1),
				Executed:                   asMsg.optionalNonNeg(2),
				CreatedNotIncludingAspects: asMsg.optionalNonNeg(3),
				RemoteCacheHits:            asMsg.optionalNonNeg(5),
			}
			if acsField := asMsg.field(7); acsField.number != 0 && acsField.kind == 2 {
				if acs, err := decodeWire(acsField.data); err == nil {
					hits := int64(acs.field(3).value)
					misses := int64(acs.field(4).value)
					if hits >= 0 {
						as.LocalCacheHits = &hits
					}
					if misses >= 0 {
						as.LocalCacheMisses = &misses
					}
				}
			}
			for _, rcField := range asMsg {
				if rcField.number != 6 || rcField.kind != 2 {
					continue
				}
				if len(as.Runners) >= maxBEPListEntries {
					state.truncated = true
					break
				}
				rc, err := decodeWire(rcField.data)
				if err != nil {
					continue
				}
				as.Runners = append(as.Runners, builds.BazelRunnerCount{
					Name:     sanitizeBEPSingleLine(string(rc.field(1).data), 128),
					Count:    int64(int32(rc.field(2).value)),
					ExecKind: sanitizeBEPSingleLine(string(rc.field(3).data), 64),
				})
			}
			state.actions = as
		}
	}

	if f := msg.field(2); f.number != 0 && f.kind == 2 {
		if mmMsg, err := decodeWire(f.data); err == nil {
			mm := &builds.BazelMemoryMetrics{
				UsedHeapPostBuildBytes: mmMsg.optionalNonNeg(1),
				PeakPostGCHeapBytes:    mmMsg.optionalNonNeg(2),
				PeakPostGCTenuredBytes: mmMsg.optionalNonNeg(4),
			}
			for _, gmField := range mmMsg {
				if gmField.number != 3 || gmField.kind != 2 {
					continue
				}
				if len(mm.Garbage) >= maxBEPListEntries {
					state.truncated = true
					break
				}
				gm, err := decodeWire(gmField.data)
				if err != nil {
					continue
				}
				collected := int64(gm.field(2).value)
				if collected < 0 {
					continue
				}
				mm.Garbage = append(mm.Garbage, builds.BazelGarbageMetric{
					Type:                  sanitizeBEPSingleLine(string(gm.field(1).data), 128),
					GarbageCollectedBytes: collected,
				})
			}
			state.memory = mm
		}
	}

	if f := msg.field(5); f.number != 0 && f.kind == 2 {
		if tmMsg, err := decodeWire(f.data); err == nil {
			state.timing = &builds.BazelTimingMetrics{
				CPUTimeMs:               tmMsg.optionalNonNeg(1),
				WallTimeMs:              tmMsg.optionalNonNeg(2),
				AnalysisPhaseMs:         tmMsg.optionalNonNeg(3),
				ExecutionPhaseMs:        tmMsg.optionalNonNeg(4),
				ActionsExecutionStartMs: tmMsg.optionalNonNeg(5),
			}
		}
	}

	var workers []builds.BazelWorkerMetric
	for _, wmField := range msg {
		if wmField.number != 9 || wmField.kind != 2 {
			continue
		}
		if len(workers) >= maxBEPListEntries {
			state.truncated = true
			break
		}
		wm, err := decodeWire(wmField.data)
		if err != nil {
			continue
		}
		idCount := 0
		for _, idF := range wm {
			if idF.number != 8 {
				continue
			}
			if idF.kind == 0 {
				idCount++
			} else if idF.kind == 2 {
				idCount += countPackedVarints(idF.data)
			}
		}
		entry := builds.BazelWorkerMetric{
			ProcessID:       int64(wm.field(2).value),
			Mnemonic:        sanitizeBEPSingleLine(string(wm.field(3).data), 128),
			IsMultiplex:     wm.field(4).value != 0,
			IsSandbox:       wm.field(5).value != 0,
			IsMeasurable:    wm.field(6).value != 0,
			ActionsExecuted: int64(wm.field(11).value),
			WorkerIDCount:   idCount,
		}
		for _, wsField := range wm {
			if wsField.number != 7 || wsField.kind != 2 {
				continue
			}
			ws, err := decodeWire(wsField.data)
			if err != nil {
				continue
			}
			if memF := ws.field(2); memF.number != 0 && memF.kind == 0 {
				kb := int64(int32(memF.value))
				if kb < 0 {
					continue
				}
				entry.LastMemoryKB = ptr.Int64(kb)
				if entry.PeakMemoryKB == nil || kb > *entry.PeakMemoryKB {
					entry.PeakMemoryKB = ptr.Int64(kb)
				}
			}
		}
		workers = append(workers, entry)
	}
	if len(workers) > 0 {
		state.workers = workers
	}

	if wpField := msg.field(11); wpField.number != 0 && wpField.kind == 2 {
		if wpMsg, err := decodeWire(wpField.data); err == nil {
			var pools []builds.BazelWorkerPoolMetric
			for _, psField := range wpMsg {
				if psField.number != 1 || psField.kind != 2 {
					continue
				}
				if len(pools) >= maxBEPListEntries {
					state.truncated = true
					break
				}
				ps, err := decodeWire(psField.data)
				if err != nil {
					continue
				}
				pools = append(pools, builds.BazelWorkerPoolMetric{
					Mnemonic:       sanitizeBEPSingleLine(string(ps.field(2).data), 128),
					CreatedCount:   int64(int32(ps.field(3).value)),
					DestroyedCount: int64(int32(ps.field(4).value)),
					EvictedCount:   int64(int32(ps.field(5).value)),
					AliveCount:     int64(int32(ps.field(10).value)),
				})
			}
			if len(pools) > 0 {
				state.workerPools = pools
			}
		}
	}
	return true
}

func decodeProtoDurationMillis(data []byte) (int64, bool) {
	msg, err := decodeWire(data)
	if err != nil {
		return 0, false
	}
	seconds := int64(msg.field(1).value)
	nanos := int64(int32(msg.field(2).value))
	if seconds < 0 || nanos < 0 || nanos >= 1_000_000_000 {
		return 0, false
	}
	return seconds*1000 + nanos/1_000_000, true
}

func countPackedVarints(data []byte) int {
	count := 0
	for len(data) > 0 {
		_, n := binary.Uvarint(data)
		if n <= 0 {
			break
		}
		data = data[n:]
		count++
	}
	return count
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
func (m wireMessageFields) optionalNonNeg(number uint64) *int64 {
	f := m.field(number)
	if f.number == 0 || f.kind != 0 {
		return nil
	}
	v := int64(f.value)
	if v < 0 {
		return nil
	}
	return &v
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
