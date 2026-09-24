package heap

import (
	"fmt"
	"strconv"
	"strings"
)

// Sample is live heap usage in megabytes (floor division from KB tool output).
type Sample struct {
	UsedMB      int64
	CommittedMB int64
	Source      string // "jcmd" | "jstat"
}

// ParseJcmdHeapInfo parses `jcmd <pid> GC.heap_info` output.
// Example line:
//
//	garbage-first heap   total reserved 262144K, committed 262144K, used 36447K [...]
func ParseJcmdHeapInfo(out string) (Sample, error) {
	for _, line := range strings.Split(out, "\n") {
		lower := strings.ToLower(line)
		if !strings.Contains(lower, "committed") || !strings.Contains(lower, "used") {
			continue
		}
		if !strings.Contains(lower, "heap") {
			continue
		}
		committedKB, okC := findKBToken(line, "committed")
		usedKB, okU := findKBToken(line, "used")
		if !okC || !okU {
			continue
		}
		return Sample{
			UsedMB:      kbToMB(usedKB),
			CommittedMB: kbToMB(committedKB),
			Source:      "jcmd",
		}, nil
	}
	return Sample{}, fmt.Errorf("jcmd GC.heap_info: no heap used/committed line")
}

// ParseJstatGC parses one sample of `jstat -gc <pid>` (header + data row).
// Used ≈ S0U+S1U+EU+OU; committed ≈ S0C+S1C+EC+OC (all KB). Missing columns treated as 0.
func ParseJstatGC(out string) (Sample, error) {
	lines := nonEmptyLines(out)
	if len(lines) < 2 {
		return Sample{}, fmt.Errorf("jstat -gc: need header and data row")
	}
	headers := strings.Fields(lines[0])
	values := strings.Fields(lines[len(lines)-1])
	if len(values) < len(headers) {
		return Sample{}, fmt.Errorf("jstat -gc: column count mismatch (hdr=%d val=%d)", len(headers), len(values))
	}
	idx := map[string]int{}
	for i, h := range headers {
		idx[h] = i
	}
	get := func(name string) float64 {
		i, ok := idx[name]
		if !ok || i >= len(values) {
			return 0
		}
		v, err := strconv.ParseFloat(values[i], 64)
		if err != nil {
			return 0
		}
		return v
	}
	usedKB := get("S0U") + get("S1U") + get("EU") + get("OU")
	committedKB := get("S0C") + get("S1C") + get("EC") + get("OC")
	if committedKB <= 0 && usedKB <= 0 {
		return Sample{}, fmt.Errorf("jstat -gc: zero used and committed")
	}
	return Sample{
		UsedMB:      kbToMB(usedKB),
		CommittedMB: kbToMB(committedKB),
		Source:      "jstat",
	}, nil
}

func kbToMB(kb float64) int64 {
	if kb < 0 {
		return 0
	}
	return int64(kb) / 1024
}

func findKBToken(line, label string) (float64, bool) {
	// Match "committed 262144K" / "used 36447K" (case-insensitive label).
	lower := strings.ToLower(line)
	key := strings.ToLower(label)
	pos := strings.Index(lower, key)
	if pos < 0 {
		return 0, false
	}
	rest := line[pos+len(label):]
	rest = strings.TrimLeft(rest, " \t:")
	fields := strings.Fields(rest)
	if len(fields) == 0 {
		return 0, false
	}
	tok := fields[0]
	tok = strings.TrimSuffix(tok, ",")
	if len(tok) < 2 || (tok[len(tok)-1] != 'K' && tok[len(tok)-1] != 'k') {
		return 0, false
	}
	v, err := strconv.ParseFloat(tok[:len(tok)-1], 64)
	if err != nil {
		return 0, false
	}
	return v, true
}

func nonEmptyLines(s string) []string {
	raw := strings.Split(s, "\n")
	out := make([]string, 0, len(raw))
	for _, line := range raw {
		line = strings.TrimSpace(line)
		if line != "" {
			out = append(out, line)
		}
	}
	return out
}
