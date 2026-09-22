package poll

import (
	"regexp"
	"strconv"
	"strings"
)

var (
	xmx        = regexp.MustCompile(`-Xmx(\d+)([kKmMgGtT]?)`)
	xms        = regexp.MustCompile(`-Xms(\d+)([kKmMgGtT]?)`)
	gcFlag     = regexp.MustCompile(`-XX:\+Use(\w+?)GC`)
	nonInteract = regexp.MustCompile(`(^|\s)--non-interactive(\s|$)`)
	consolePlain = regexp.MustCompile(`(^|\s)--console[=\s]plain(\s|$)`)
)

// JVMArgs mirrors Kotlin JvmArgParser (KTD-3).
type JVMArgs struct {
	MaxHeapMB *int64
	MinHeapMB *int64
	GC        *string
}

func ParseJVMArgs(commandLine string) JVMArgs {
	var out JVMArgs
	if m := xmx.FindStringSubmatch(commandLine); m != nil {
		out.MaxHeapMB = ptrInt64(toMB(m[1], m[2]))
	}
	if m := xms.FindStringSubmatch(commandLine); m != nil {
		out.MinHeapMB = ptrInt64(toMB(m[1], m[2]))
	}
	if m := gcFlag.FindStringSubmatch(commandLine); m != nil {
		out.GC = ptrString(m[1])
	}
	return out
}

func IsNonInteractive(commandLine string) bool {
	return nonInteract.MatchString(commandLine) || consolePlain.MatchString(commandLine)
}

func toMB(number, unit string) int64 {
	n, err := strconv.ParseInt(number, 10, 64)
	if err != nil {
		return 0
	}
	switch strings.ToLower(unit) {
	case "k":
		return n / 1024
	case "m", "":
		return n
	case "g":
		return n * 1024
	case "t":
		return n * 1024 * 1024
	default:
		return n
	}
}

func ptrInt64(v int64) *int64    { return &v }
func ptrString(v string) *string { return &v }
func ptrFloat64(v float64) *float64 {
	return &v
}
