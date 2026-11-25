package bep

import (
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/poll"
)

const (
	maxBEPListEntries   = 256
	maxBEPFieldBytes    = 512
	maxBEPSnippetBytes  = 4096
	maxBEPTargetTokens  = 16
)

func sanitizeBEPString(s string, limit int) string {
	if s == "" {
		return ""
	}
	redacted := poll.RedactLogLine(s)
	var b strings.Builder
	b.Grow(min(len(redacted), limit))
	for _, r := range redacted {
		if r < 0x20 && r != '\n' && r != '\t' {
			continue
		}
		if b.Len()+utf8.RuneLen(r) > limit {
			break
		}
		b.WriteRune(r)
	}
	return strings.TrimSpace(b.String())
}

func sanitizeBEPSingleLine(s string, limit int) string {
	cleaned := sanitizeBEPString(s, limit)
	cleaned = strings.ReplaceAll(cleaned, "\n", " ")
	cleaned = strings.ReplaceAll(cleaned, "\t", " ")
	return strings.TrimSpace(cleaned)
}

func sanitizeBEPCommand(cmd string) string {
	if cmd == "" {
		return ""
	}
	redacted := poll.RedactCommandLine(cmd)
	return sanitizeBEPSingleLine(redacted, maxBEPFieldBytes)
}

func (p *partial) recordCommandLine(label string, commandChunks, residualChunks []string) {
	// Skip tool command line; prefer original over canonical.
	if label == "tool" {
		return
	}
	if p.commandLinePriority >= 2 && label != "original" {
		return
	}
	parts := make([]string, 0, 1+len(commandChunks)+min(len(residualChunks), maxBEPTargetTokens))
	parts = append(parts, "bazel")
	for _, chunk := range commandChunks {
		chunk = sanitizeBEPSingleLine(chunk, maxBEPFieldBytes)
		if chunk != "" {
			parts = append(parts, chunk)
		}
	}
	if len(parts) == 1 && p.Command != "" {
		parts = append(parts, sanitizeBEPSingleLine(p.Command, maxBEPFieldBytes))
	}
	for i, chunk := range residualChunks {
		if i >= maxBEPTargetTokens {
			p.truncated = true
			break
		}
		chunk = sanitizeBEPSingleLine(chunk, maxBEPFieldBytes)
		if chunk != "" {
			parts = append(parts, chunk)
		}
	}
	if len(parts) <= 1 {
		return
	}
	p.structuredCommandLine = sanitizeBEPCommand(strings.Join(parts, " "))
	if label == "original" {
		p.commandLinePriority = 2
	} else if p.commandLinePriority < 1 {
		p.commandLinePriority = 1
	}
}

func (p *partial) recordTestSummary(ts builds.BazelTestSummary) {
	ts.Label = sanitizeBEPSingleLine(ts.Label, maxBEPFieldBytes)
	ts.ConfigID = sanitizeBEPSingleLine(ts.ConfigID, 128)
	if ts.OverallStatus == "" {
		ts.OverallStatus = "UNKNOWN"
	}
	if p.testIndex == nil {
		p.testIndex = make(map[string]int)
	}
	key := ts.Label + "\x00" + ts.ConfigID
	if idx, ok := p.testIndex[key]; ok {
		p.tests[idx] = ts
		return
	}
	if len(p.tests) >= maxBEPListEntries {
		p.truncated = true
		return
	}
	p.testIndex[key] = len(p.tests)
	p.tests = append(p.tests, ts)
}

func (p *partial) recordAborted(reason, description string) {
	reason = sanitizeBEPSingleLine(reason, 64)
	desc := sanitizeBEPString(description, maxBEPSnippetBytes)
	if reason == "" && desc == "" {
		return
	}
	if p.diagnostics == nil {
		p.diagnostics = &builds.BazelDiagnostics{}
	}
	if p.diagnostics.AbortReason == "" && reason != "" {
		p.diagnostics.AbortReason = reason
	}
	if p.diagnostics.Summary == "" && desc != "" {
		p.diagnostics.Summary = desc
	}
}

func (p *partial) recordProgressError(stderr string) {
	if stderr == "" || len(p.progressErrors) >= maxBEPSnippetBytes {
		return
	}
	for _, line := range strings.Split(stderr, "\n") {
		trimmed := strings.TrimSpace(line)
		if !strings.HasPrefix(trimmed, "ERROR:") && !strings.HasPrefix(trimmed, "FAILED:") {
			continue
		}
		redacted := sanitizeBEPSingleLine(trimmed, maxBEPFieldBytes)
		if redacted == "" {
			continue
		}
		if p.progressErrors != "" {
			if len(p.progressErrors)+1+len(redacted) > maxBEPSnippetBytes {
				break
			}
			p.progressErrors += "\n" + redacted
		} else {
			p.progressErrors = redacted
		}
	}
}

func (p *partial) hasRichDetails() bool {
	return p.BazelVersion != "" ||
		p.memory != nil ||
		p.timing != nil ||
		p.actions != nil ||
		len(p.workers) > 0 ||
		len(p.workerPools) > 0 ||
		len(p.tests) > 0 ||
		p.diagnostics != nil ||
		p.lastMessage
}

func (p *partial) buildDetails(sourceFormat string) *builds.BazelBuildDetails {
	if !p.hasRichDetails() {
		return nil
	}
	state := builds.CollectionStatePartial
	if p.truncated {
		state = builds.CollectionStateTruncated
	} else if p.lastMessage {
		state = builds.CollectionStateSealed
	}
	updatedAt := p.EndTimeMs
	if updatedAt <= 0 {
		updatedAt = p.StartTimeMs
	}
	if updatedAt <= 0 {
		updatedAt = time.Now().UnixMilli()
	}
	metrics := builds.BazelBuildMetrics{
		Memory:      p.memory,
		Timing:      p.timing,
		Actions:     p.actions,
		Workers:     append([]builds.BazelWorkerMetric(nil), p.workers...),
		WorkerPools: append([]builds.BazelWorkerPoolMetric(nil), p.workerPools...),
		Tests:       append([]builds.BazelTestSummary(nil), p.tests...),
		Diagnostics: p.diagnostics,
	}
	if len(metrics.Tests) > 0 {
		totals := &builds.BazelTestTotals{Total: len(metrics.Tests)}
		for _, ts := range metrics.Tests {
			switch ts.OverallStatus {
			case "PASSED":
				totals.Passed++
			case "FLAKY":
				totals.Flaky++
			case "TIMEOUT":
				totals.Timeout++
			case "FAILED", "FAILED_TO_BUILD", "REMOTE_FAILURE", "INCOMPLETE", "TOOL_HALTED_BEFORE_TESTING":
				totals.Failed++
			}
			if ts.TotalNumCached > 0 {
				totals.Cached++
			}
			if ts.TotalRunDurationMs != nil && *ts.TotalRunDurationMs > 0 {
				totals.TotalDurationMs += *ts.TotalRunDurationMs
			}
		}
		metrics.TestTotals = totals
	}
	return &builds.BazelBuildDetails{
		SchemaVersion:   builds.BazelDetailsSchemaVersion,
		BazelVersion:    sanitizeBEPSingleLine(p.BazelVersion, 128),
		SourceFormat:    sourceFormat,
		CollectionState: state,
		UpdatedAtMs:     updatedAt,
		Metrics:         metrics,
	}
}

func coalesceBuilds(list []builds.Build, next builds.Build) []builds.Build {
	if next.BuildID != "" {
		for i := range list {
			if list[i].BuildID == next.BuildID {
				list[i] = next
				return list
			}
		}
	}
	return append(list, next)
}

func parseJSONDurationMillis(raw string) *int64 {
	raw = strings.TrimSpace(raw)
	if raw == "" || !strings.HasSuffix(raw, "s") {
		return nil
	}
	d, err := time.ParseDuration(raw)
	if err != nil {
		// Fallback for large protobuf duration strings like "123.456s".
		secStr := strings.TrimSuffix(raw, "s")
		f, fErr := strconv.ParseFloat(secStr, 64)
		if fErr != nil || f < 0 {
			return nil
		}
		ms := int64(f * 1000)
		return &ms
	}
	if d < 0 {
		return nil
	}
	ms := d.Milliseconds()
	return &ms
}

func abortedReasonName(code uint64) string {
	switch code {
	case 0:
		return "UNKNOWN"
	case 1:
		return "USER_INTERRUPTED"
	case 2:
		return "NO_ANALYZE"
	case 3:
		return "NO_BUILD"
	case 4:
		return "TIME_OUT"
	case 5:
		return "LOADING_FAILURE"
	case 6:
		return "ANALYSIS_FAILURE"
	case 7:
		return "SKIPPED"
	case 8:
		return "INCOMPLETE"
	case 9:
		return "OUT_OF_MEMORY"
	case 10:
		return "REMOTE_ENVIRONMENT_FAILURE"
	case 11:
		return "INTERNAL"
	case 12:
		return "COMMAND_LINE_EXPANSION_FAILURE"
	default:
		return "UNKNOWN"
	}
}

func testStatusName(code uint64) string {
	switch code {
	case 1:
		return "PASSED"
	case 2:
		return "FLAKY"
	case 3:
		return "TIMEOUT"
	case 4:
		return "FAILED"
	case 5:
		return "INCOMPLETE"
	case 6:
		return "REMOTE_FAILURE"
	case 7:
		return "FAILED_TO_BUILD"
	case 8:
		return "TOOL_HALTED_BEFORE_TESTING"
	default:
		return "UNKNOWN"
	}
}

func ptrI64(v int64) *int64 { return &v }
