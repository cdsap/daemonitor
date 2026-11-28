// Package commandline contains shared helpers for process command lines.
package commandline

import (
	"path"
	"regexp"
	"strings"
)

// ExecutableBase normalizes either path separator, case, and the Windows suffix.
func ExecutableBase(executable string) string {
	return strings.TrimSuffix(strings.ToLower(path.Base(strings.ReplaceAll(executable, `\`, "/"))), ".exe")
}

// QuotedFlag matches an equals-style flag whose value may be quoted.
func QuotedFlag(flag string) *regexp.Regexp {
	return regexp.MustCompile(regexp.QuoteMeta(flag) + `=(?:"([^"]+)"|'([^']+)'|([^\s]+))`)
}

// FlagValue returns the populated alternative of a quoted-flag match.
func FlagValue(match []string) string {
	if len(match) == 0 {
		return ""
	}
	for _, value := range match[1:] {
		if value != "" {
			return value
		}
	}
	return ""
}
