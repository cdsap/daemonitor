package heap

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

var jinfoCollectorFlag = regexp.MustCompile(`-XX:\+Use([A-Za-z0-9]+)GC(?:\s|$)`)

// Sample is live heap usage in megabytes (floor division from KB tool output).
type Sample struct {
	UsedMB               int64
	CommittedMB          int64
	Source               string // "jcmd" | "jstat"
	GC                   *string
	MetaspaceUsedMB      *int64
	MetaspaceCommittedMB *int64
	YoungGCCount         *int64
	YoungGCTimeMs        *int64
	OldGCCount           *int64
	OldGCTimeMs          *int64
	JavaVersion          *string
	JavaVendor           *string
	ActiveProcessorCount *int64
}

// ParseJcmdVMInfo extracts optional JVM identity and processor information.
func ParseJcmdVMInfo(out string) (version, vendor *string, processors *int64) {
	for _, line := range strings.Split(out, "\n") {
		trimmed := strings.TrimSpace(line)
		lower := strings.ToLower(trimmed)
		if strings.Contains(lower, "version") && strings.Contains(trimmed, `"`) {
			if start := strings.Index(trimmed, `"`); start >= 0 {
				if end := strings.Index(trimmed[start+1:], `"`); end >= 0 {
					v := trimmed[start+1 : start+1+end]
					version = &v
				}
			}
			fields := strings.Fields(trimmed)
			if len(fields) > 0 {
				v := fields[0]
				vendor = &v
			}
		}
		if idx := strings.Index(lower, "available processors:"); idx >= 0 {
			fields := strings.Fields(trimmed[idx+len("available processors:"):])
			if len(fields) > 0 {
				if n, err := strconv.ParseInt(fields[0], 10, 64); err == nil {
					processors = &n
				}
			}
		}
	}
	return
}

// ParseJinfoFlagsGC parses the selected collector from `jinfo -flags` output.
func ParseJinfoFlagsGC(out string) (*string, error) {
	for _, match := range jinfoCollectorFlag.FindAllStringSubmatch(out, -1) {
		name, ok := map[string]string{
			"G1": "G1", "Parallel": "Parallel", "Serial": "Serial",
			"Shenandoah": "Shenandoah", "Z": "ZGC",
		}[match[1]]
		if ok {
			return ptrString(name), nil
		}
	}
	return nil, fmt.Errorf("jinfo -flags: supported collector flag not found")
}

// ParseJcmdHeapInfo parses `jcmd <pid> GC.heap_info` output.
// Example line:
//
//	garbage-first heap   total reserved 262144K, committed 262144K, used 36447K [...]
func ParseJcmdHeapInfo(out string) (Sample, error) {
	var metaspaceUsed, metaspaceCommitted *int64
	var heapUsed, heapCommitted *int64
	var gc *string
	var generationUsedKB, generationTotalKB float64
	for _, line := range strings.Split(out, "\n") {
		lower := strings.ToLower(line)
		if strings.Contains(lower, "garbage-first heap") {
			gc = ptrString("G1")
		}
		if strings.Contains(lower, "psyounggen") || strings.Contains(lower, "paroldgen") {
			gc = ptrString("Parallel")
			if total, ok := findKBToken(line, "total"); ok {
				generationTotalKB += total
			}
			if used, ok := findKBToken(line, "used"); ok {
				generationUsedKB += used
			}
		}
		if strings.Contains(lower, "def new generation") || strings.Contains(lower, "tenured generation") {
			gc = ptrString("Serial")
			if total, ok := findKBToken(line, "total"); ok {
				generationTotalKB += total
			}
			if used, ok := findKBToken(line, "used"); ok {
				generationUsedKB += used
			}
		}
		if strings.Contains(lower, "shenandoah") {
			gc = ptrString("Shenandoah")
		}
		if strings.Contains(lower, "zheap") {
			gc = ptrString("ZGC")
		}
		if strings.Contains(lower, "metaspace") {
			metaspaceUsed = parseJcmdMetaspace(line, "used")
			metaspaceCommitted = parseJcmdMetaspace(line, "committed")
		}
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
		heapUsed, heapCommitted = ptrInt64(kbToMB(usedKB)), ptrInt64(kbToMB(committedKB))
	}
	if generationTotalKB > 0 {
		heapUsed, heapCommitted = ptrInt64(kbToMB(generationUsedKB)), ptrInt64(kbToMB(generationTotalKB))
	}
	if heapUsed == nil || heapCommitted == nil {
		return Sample{}, fmt.Errorf("jcmd GC.heap_info: no heap used/committed line")
	}
	return Sample{UsedMB: *heapUsed, CommittedMB: *heapCommitted, Source: "jcmd", GC: gc, MetaspaceUsedMB: metaspaceUsed, MetaspaceCommittedMB: metaspaceCommitted}, nil
}

func ptrInt64(v int64) *int64 { return &v }

func ptrString(v string) *string { return &v }

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
	getInt := func(name string) (int64, bool) {
		i, ok := idx[name]
		if !ok || i >= len(values) {
			return 0, false
		}
		v, err := strconv.ParseInt(values[i], 10, 64)
		return v, err == nil
	}
	getMillis := func(name string) (int64, bool) {
		v, ok := getValue(name, idx, values)
		if !ok {
			return 0, false
		}
		return int64(v * 1000), true
	}
	getMB := func(name string) (int64, bool) {
		v, ok := getValue(name, idx, values)
		if !ok {
			return 0, false
		}
		return kbToMB(v), true
	}
	usedKB := get("S0U") + get("S1U") + get("EU") + get("OU")
	committedKB := get("S0C") + get("S1C") + get("EC") + get("OC")
	if committedKB <= 0 && usedKB <= 0 {
		return Sample{}, fmt.Errorf("jstat -gc: zero used and committed")
	}
	var youngCount, oldCount *int64
	var youngTime, oldTime *int64
	if v, ok := getInt("YGC"); ok {
		youngCount = &v
	}
	if v, ok := getMillis("YGCT"); ok {
		youngTime = &v
	}
	if v, ok := getInt("FGC"); ok {
		oldCount = &v
	}
	if v, ok := getMillis("FGCT"); ok {
		oldTime = &v
	}
	var metaspaceUsed, metaspaceCommitted *int64
	if v, ok := getMB("MU"); ok {
		metaspaceUsed = &v
	}
	if v, ok := getMB("MC"); ok {
		metaspaceCommitted = &v
	}
	return Sample{
		UsedMB:          kbToMB(usedKB),
		CommittedMB:     kbToMB(committedKB),
		Source:          "jstat",
		MetaspaceUsedMB: metaspaceUsed, MetaspaceCommittedMB: metaspaceCommitted,
		YoungGCCount: youngCount, YoungGCTimeMs: youngTime,
		OldGCCount: oldCount, OldGCTimeMs: oldTime,
	}, nil
}

func parseJcmdMetaspace(line, label string) *int64 {
	if !strings.Contains(strings.ToLower(line), "metaspace") {
		return nil
	}
	v, ok := findKBToken(line, label)
	if !ok {
		return nil
	}
	result := kbToMB(v)
	return &result
}

func kbToMB(kb float64) int64 {
	if kb < 0 {
		return 0
	}
	return int64(kb) / 1024
}

func getValue(name string, idx map[string]int, values []string) (float64, bool) {
	i, ok := idx[name]
	if !ok || i >= len(values) {
		return 0, false
	}
	v, err := strconv.ParseFloat(values[i], 64)
	return v, err == nil
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
