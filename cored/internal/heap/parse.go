package heap

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

var jinfoGCFlag = regexp.MustCompile(`(?:^|\s)-XX:\+Use([[:alnum:]]+)GC(?:\s|$)`)

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
	// jstat reports cumulative GC times in seconds.
	YoungGCTimeSeconds      *float64
	FullGCTimeSeconds       *float64
	ConcurrentGCTimeSeconds *float64
	TotalGCTimeSeconds      *float64
	OldGCCount              *int64
	OldGCTimeMs             *int64
	FullGCCount             *int64
	ConcurrentGCCount       *int64
	TotalGCCount            *int64
	MemoryPools             []MemoryPool
	JIT                     *JITMetrics
	Classes                 *ClassMetrics
	Safepoints              *SafepointMetrics
	CodeCache               []MemoryPool
	JavaVersion             *string
	JavaVendor              *string
	ActiveProcessorCount    *int64
	JavaRuntimeVersion      *string
	JavaVMName              *string
	JavaVMVersion           *string
	OSName                  *string
	OSArch                  *string
	Diagnostics             []Diagnostic
}

// RawValue retains the unit emitted by a HotSpot tool. Values are converted
// only by consumers that explicitly need a common unit.
type RawValue struct {
	Value float64
	Unit  string
}

type MemoryPool struct {
	Name      string
	Source    string
	Used      RawValue
	Committed RawValue
	Max       RawValue
}

type JITMetrics struct {
	CompiledCount          *int64
	FailedCount            *int64
	InvalidCount           *int64
	CompilationTimeSeconds *float64
}

type ClassMetrics struct {
	LoadedClasses      *int64
	UnloadedClasses    *int64
	LoadedBytes        *RawValue
	UnloadedBytes      *RawValue
	ClassTimeSeconds   *float64
	LoadedClassesTotal *int64 // Loaded + cumulative unloaded; nil when either source is absent.
}

type SafepointMetrics struct {
	SafepointCount      *int64
	SafepointTimeNs     *int64
	SafepointSyncTimeNs *int64
	ApplicationTimeNs   *int64
}

// ParseJinfoSysprops extracts only the explicitly allowlisted diagnostic
// properties from `jinfo -sysprops`. The complete property map is never
// retained, logged, or returned.
func ParseJinfoSysprops(out string) (version, runtimeVersion, vendor, vmName, vmVersion, osName, osArch *string) {
	allowlisted := map[string]**string{
		"java.version":         &version,
		"java.runtime.version": &runtimeVersion,
		"java.vendor":          &vendor,
		"java.vm.name":         &vmName,
		"java.vm.version":      &vmVersion,
		"os.name":              &osName,
		"os.arch":              &osArch,
	}
	for _, line := range strings.Split(out, "\n") {
		key, value, ok := parseSyspropLine(line)
		if !ok {
			continue
		}
		target, ok := allowlisted[key]
		if !ok {
			continue
		}
		decoded, ok := decodeSyspropValue(value)
		if ok {
			*target = ptrString(decoded)
		}
	}
	return
}

func parseSyspropLine(line string) (key, value string, ok bool) {
	line = strings.TrimSpace(line)
	if line == "" || strings.HasPrefix(line, "#") {
		return "", "", false
	}
	separator := -1
	escaped := false
	for i, r := range line {
		if escaped {
			escaped = false
			continue
		}
		if r == '\\' {
			escaped = true
			continue
		}
		if r == '=' {
			separator = i
			break
		}
	}
	if separator < 0 {
		return "", "", false
	}
	key = strings.TrimSpace(line[:separator])
	value = strings.TrimSpace(line[separator+1:])
	return key, value, key != ""
}

func decodeSyspropValue(value string) (string, bool) {
	var b strings.Builder
	for i := 0; i < len(value); i++ {
		if value[i] != '\\' {
			b.WriteByte(value[i])
			continue
		}
		if i+1 >= len(value) {
			return "", false
		}
		i++
		switch value[i] {
		case 't':
			b.WriteByte('\t')
		case 'n':
			b.WriteByte('\n')
		case 'r':
			b.WriteByte('\r')
		case 'f':
			b.WriteByte('\f')
		case 'u':
			if i+4 >= len(value) {
				return "", false
			}
			n, err := strconv.ParseUint(value[i+1:i+5], 16, 16)
			if err != nil {
				return "", false
			}
			b.WriteRune(rune(n))
			i += 4
		default:
			b.WriteByte(value[i])
		}
	}
	decoded := strings.TrimSpace(b.String())
	if decoded == "" || !utf8.ValidString(decoded) || strings.ContainsAny(decoded, "\r\n") {
		return "", false
	}
	return decoded, true
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

// ParseJinfoFlags extracts the selected garbage collector from `jinfo -flags`.
// jinfo emits one VM flag per line; all other flags are intentionally ignored.
func ParseJinfoFlags(out string) (gc *string) {
	for _, match := range jinfoGCFlag.FindAllStringSubmatch(out, -1) {
		name, ok := map[string]string{
			"G1": "G1", "Parallel": "Parallel", "Serial": "Serial",
			"Shenandoah": "Shenandoah", "Z": "ZGC",
		}[match[1]]
		if ok {
			gc = ptrString(name)
		}
	}
	return gc
}

// ParseJinfoFlagsGC parses the selected collector from `jinfo -flags` output.
func ParseJinfoFlagsGC(out string) (*string, error) {
	gc := ParseJinfoFlags(out)
	if gc == nil {
		return nil, fmt.Errorf("jinfo -flags: supported collector flag not found")
	}
	return gc, nil
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
	var pools []MemoryPool
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
		if pool, ok := parseJcmdPool(line); ok {
			pools = append(pools, pool)
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
	return Sample{UsedMB: *heapUsed, CommittedMB: *heapCommitted, Source: "jcmd", GC: gc, MetaspaceUsedMB: metaspaceUsed, MetaspaceCommittedMB: metaspaceCommitted, MemoryPools: pools}, nil
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
	var youngTimeSeconds, fullTimeSeconds, concurrentTimeSeconds, totalTimeSeconds *float64
	if v, ok := getInt("YGC"); ok {
		youngCount = &v
	}
	if v, ok := getMillis("YGCT"); ok {
		youngTime = &v
	}
	if v, ok := getValue("YGCT", idx, values); ok {
		youngTimeSeconds = &v
	}
	if v, ok := getValue("FGCT", idx, values); ok {
		fullTimeSeconds = &v
	}
	if v, ok := getValue("CGCT", idx, values); ok {
		concurrentTimeSeconds = &v
	}
	if v, ok := getValue("GCT", idx, values); ok {
		totalTimeSeconds = &v
	}
	if v, ok := getInt("FGC"); ok {
		oldCount = &v
		// jstat names this counter FGC. Keep the historical OldGCCount field
		// and expose the precise full-GC meaning separately.
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
	var pools []MemoryPool
	for _, name := range []string{"S0", "S1", "E", "O", "M", "CCS"} {
		used, usedOK := getValue(name+"U", idx, values)
		committed, committedOK := getValue(name+"C", idx, values)
		if usedOK || committedOK {
			pool := MemoryPool{Name: name, Source: "jstat"}
			if usedOK {
				pool.Used = RawValue{Value: used, Unit: "KB"}
			}
			if committedOK {
				pool.Committed = RawValue{Value: committed, Unit: "KB"}
			}
			pools = append(pools, pool)
		}
	}
	return Sample{
		UsedMB:          kbToMB(usedKB),
		CommittedMB:     kbToMB(committedKB),
		Source:          "jstat",
		MetaspaceUsedMB: metaspaceUsed, MetaspaceCommittedMB: metaspaceCommitted,
		YoungGCCount: youngCount, YoungGCTimeMs: youngTime,
		YoungGCTimeSeconds: youngTimeSeconds, FullGCTimeSeconds: fullTimeSeconds,
		ConcurrentGCTimeSeconds: concurrentTimeSeconds, TotalGCTimeSeconds: totalTimeSeconds,
		OldGCCount: oldCount, OldGCTimeMs: oldTime,
		FullGCCount: oldCount,
		ConcurrentGCCount: func() *int64 {
			v, ok := getInt("CGC")
			if !ok {
				return nil
			}
			return &v
		}(),
		MemoryPools: pools,
	}, nil
}

// ParseJstatCompiler parses the stable cumulative columns emitted by
// `jstat -compiler`. FailedType and FailedMethod are intentionally ignored.
func ParseJstatCompiler(out string) (JITMetrics, error) {
	lines := nonEmptyLines(out)
	if len(lines) < 2 {
		return JITMetrics{}, fmt.Errorf("jstat -compiler: need header and data row")
	}
	headers, values := strings.Fields(lines[0]), strings.Fields(lines[len(lines)-1])
	if len(values) < len(headers) {
		return JITMetrics{}, fmt.Errorf("jstat -compiler: column count mismatch")
	}
	idx := map[string]int{}
	for i, h := range headers {
		idx[h] = i
	}
	getInt := func(name string) *int64 {
		i, ok := idx[name]
		if !ok || i >= len(values) {
			return nil
		}
		v, err := strconv.ParseInt(values[i], 10, 64)
		if err != nil {
			return nil
		}
		return &v
	}
	getFloat := func(name string) *float64 {
		i, ok := idx[name]
		if !ok || i >= len(values) {
			return nil
		}
		v, err := strconv.ParseFloat(values[i], 64)
		if err != nil {
			return nil
		}
		return &v
	}
	m := JITMetrics{CompiledCount: getInt("Compiled"), FailedCount: getInt("Failed"), InvalidCount: getInt("Invalid"), CompilationTimeSeconds: getFloat("Time")}
	if m.CompiledCount == nil && m.FailedCount == nil && m.InvalidCount == nil && m.CompilationTimeSeconds == nil {
		return JITMetrics{}, fmt.Errorf("jstat -compiler: no supported counters")
	}
	return m, nil
}

// ParseJstatClass parses current loaded classes separately from cumulative
// unloads. jstat -class does not provide a cumulative loaded count.
func ParseJstatClass(out string) (ClassMetrics, error) {
	lines := nonEmptyLines(out)
	if len(lines) < 2 {
		return ClassMetrics{}, fmt.Errorf("jstat -class: need header and data row")
	}
	headers, values := strings.Fields(lines[0]), strings.Fields(lines[len(lines)-1])
	if len(values) < len(headers) {
		return ClassMetrics{}, fmt.Errorf("jstat -class: column count mismatch")
	}
	idx := map[string]int{}
	for i, h := range headers {
		idx[h] = i
	}
	getInt := func(name string) *int64 {
		i, ok := idx[name]
		if !ok || i >= len(values) {
			return nil
		}
		v, err := strconv.ParseInt(values[i], 10, 64)
		if err != nil {
			return nil
		}
		return &v
	}
	getFloat := func(name string) *float64 {
		i, ok := idx[name]
		if !ok || i >= len(values) {
			return nil
		}
		v, err := strconv.ParseFloat(values[i], 64)
		if err != nil {
			return nil
		}
		return &v
	}
	loaded, unloaded := getInt("Loaded"), getInt("Unloaded")
	var loadedTotal *int64
	if loaded != nil && unloaded != nil {
		total := *loaded + *unloaded
		loadedTotal = &total
	}
	return ClassMetrics{LoadedClasses: loaded, UnloadedClasses: unloaded, LoadedClassesTotal: loadedTotal, ClassTimeSeconds: getFloat("Time")}, nil
}

// ParsePerfCounterSafepoints parses cumulative HotSpot perf counters. These
// values are not STW pause time; safepointTime includes VM-side time only.
func ParsePerfCounterSafepoints(out string) (SafepointMetrics, error) {
	var m SafepointMetrics
	for _, line := range strings.Split(out, "\n") {
		parts := strings.SplitN(strings.TrimSpace(line), "=", 2)
		if len(parts) != 2 {
			continue
		}
		v, err := strconv.ParseInt(strings.TrimSpace(parts[1]), 10, 64)
		if err != nil {
			continue
		}
		switch strings.TrimSpace(parts[0]) {
		case "sun.rt.safepoints":
			m.SafepointCount = &v
		case "sun.rt.safepointTime":
			m.SafepointTimeNs = &v
		case "sun.rt.safepointSyncTime":
			m.SafepointSyncTimeNs = &v
		case "sun.rt.applicationTime":
			m.ApplicationTimeNs = &v
		}
	}
	if m.SafepointCount == nil && m.SafepointTimeNs == nil && m.SafepointSyncTimeNs == nil && m.ApplicationTimeNs == nil {
		return SafepointMetrics{}, fmt.Errorf("PerfCounter.print: safepoint counters unavailable")
	}
	return m, nil
}

var codeHeapPattern = regexp.MustCompile(`(?i)^\s*CodeHeap\s+'([^']+)'\s*:\s*size=([^ ]+)\s+used=([^ ]+)\s+max_used=([^ ]+)`)
var rawValuePattern = regexp.MustCompile(`^(-?(?:[0-9]+(?:\.[0-9]+)?))(B|K|Kb|M|Mb|G|Gb)?$`)

func parseRawValue(token string) (RawValue, bool) {
	m := rawValuePattern.FindStringSubmatch(strings.TrimSpace(token))
	if m == nil {
		return RawValue{}, false
	}
	value, err := strconv.ParseFloat(m[1], 64)
	if err != nil {
		return RawValue{}, false
	}
	unit := m[2]
	if unit == "" {
		unit = "B"
	}
	return RawValue{Value: value, Unit: unit}, true
}

// ParseJcmdCodeCache preserves each original CodeHeap name and emitted unit.
func ParseJcmdCodeCache(out string) ([]MemoryPool, error) {
	var pools []MemoryPool
	for _, line := range strings.Split(out, "\n") {
		m := codeHeapPattern.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		size, ok1 := parseRawValue(m[2])
		used, ok2 := parseRawValue(m[3])
		max, ok3 := parseRawValue(m[4])
		if !ok1 || !ok2 || !ok3 {
			continue
		}
		pools = append(pools, MemoryPool{Name: m[1], Source: "jcmd", Used: used, Committed: size, Max: max})
	}
	if len(pools) == 0 {
		return nil, fmt.Errorf("jcmd Compiler.codecache: no code heaps")
	}
	return pools, nil
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

func parseJcmdPool(line string) (MemoryPool, bool) {
	lower := strings.ToLower(line)
	if !strings.Contains(lower, "used") || !strings.Contains(lower, "committed") {
		return MemoryPool{}, false
	}
	used, usedOK := findRawToken(line, "used")
	committed, committedOK := findRawToken(line, "committed")
	if !usedOK || !committedOK {
		return MemoryPool{}, false
	}
	nameEnd := strings.Index(lower, "used")
	name := strings.TrimSpace(strings.TrimRight(line[:nameEnd], ":"))
	if name == "" || strings.Contains(strings.ToLower(name), "heap") {
		return MemoryPool{}, false
	}
	return MemoryPool{Name: name, Source: "jcmd", Used: used, Committed: committed}, true
}

func findRawToken(line, label string) (RawValue, bool) {
	lower := strings.ToLower(line)
	pos := strings.Index(lower, strings.ToLower(label))
	if pos < 0 {
		return RawValue{}, false
	}
	rest := strings.TrimLeft(line[pos+len(label):], " \t:")
	fields := strings.Fields(rest)
	if len(fields) == 0 {
		return RawValue{}, false
	}
	return parseRawValue(strings.TrimSuffix(fields[0], ","))
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
