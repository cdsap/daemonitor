package poll

import (
	"regexp"
	"strings"
)

const redactMask = "***"

var (
	denyMulti = []string{"password", "secret", "token", "credential", "apikey"}
	keyWord   = regexp.MustCompile(`(^|[^a-z])key([^a-z]|$)`)
	// -Pkey=value | -Dkey=value | --key=value
	propFlag = regexp.MustCompile(`^(--|-[PD])([^=\s]+)=(.*)$`)
	// scheme://user:pass@host
	urlCreds = regexp.MustCompile(`([a-zA-Z][a-zA-Z0-9+.\-]*://)([^/@\s:]+):([^/@\s]+)@`)
	wsSplit  = regexp.MustCompile(`\s+`)
)

// RedactCommandLine mirrors Kotlin Redactor.redactCommandLine (KTD-7).
func RedactCommandLine(commandLine string) string {
	trimmed := strings.TrimSpace(commandLine)
	if trimmed == "" {
		return commandLine
	}
	parts := wsSplit.Split(trimmed, -1)
	maskNext := false
	for i, part := range parts {
		if maskNext {
			parts[i] = redactMask
			maskNext = false
			continue
		}
		parts[i] = RedactToken(part)
		if isSensitiveOption(part) {
			maskNext = true
		}
	}
	return strings.Join(parts, " ")
}

// isSensitiveOption covers the common argv form where the secret is the next
// argument rather than part of --key=value.
func isSensitiveOption(token string) bool {
	if strings.Contains(token, "=") || !strings.HasPrefix(token, "-") {
		return false
	}
	key := strings.TrimLeft(token, "-")
	if strings.HasPrefix(key, "P") || strings.HasPrefix(key, "D") {
		key = key[1:]
	}
	return isSensitiveKey(key)
}

// RedactToken mirrors Kotlin Redactor.redactToken.
func RedactToken(token string) string {
	if m := propFlag.FindStringSubmatch(token); m != nil {
		prefix, key := m[1], m[2]
		if isSensitiveKey(key) {
			return prefix + key + "=" + redactMask
		}
	}
	return maskURLCredentials(token)
}

// RedactLogLine mirrors Kotlin Redactor.redactLogLine.
func RedactLogLine(line string) string {
	urlMasked := maskURLCredentials(line)
	// Split keeping whitespace separators, like Kotlin's (?<=\s)|(?=\s).
	pieces := regexp.MustCompile(`(\s+)|(\S+)`).FindAllString(urlMasked, -1)
	for i, piece := range pieces {
		if strings.TrimSpace(piece) == "" {
			continue
		}
		if m := propFlag.FindStringSubmatch(piece); m != nil {
			prefix, key := m[1], m[2]
			if isSensitiveKey(key) {
				pieces[i] = prefix + key + "=" + redactMask
			}
		}
	}
	return strings.Join(pieces, "")
}

func isSensitiveKey(key string) bool {
	lower := strings.ToLower(key)
	for _, token := range denyMulti {
		if strings.Contains(lower, token) {
			return true
		}
	}
	return keyWord.MatchString(lower)
}

func maskURLCredentials(text string) string {
	return urlCreds.ReplaceAllString(text, "${1}"+redactMask+":"+redactMask+"@")
}
