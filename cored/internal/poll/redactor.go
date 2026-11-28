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
	propFlag        = regexp.MustCompile(`^(--|-[PD])([^=\s]+)=(.*)$`)
	envAssignment   = regexp.MustCompile(`^([A-Za-z_][A-Za-z0-9_]*)=(.*)$`)
	authCredentials = regexp.MustCompile(`(?i)\b(Bearer|Basic)([\t ]+)\S+`)
	// scheme://user:pass@host
	urlCreds      = regexp.MustCompile(`([a-zA-Z][a-zA-Z0-9+.\-]*://)([^/@\s:]+):([^/@\s]+)@`)
	wsSplit       = regexp.MustCompile(`\s+`)
	logLinePieces = regexp.MustCompile(`(\s+)|(\S+)`)
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
	// Peel wrapper flags iteratively so arbitrarily nested flags do not grow the stack.
	var wrappers strings.Builder
	for {
		m := propFlag.FindStringSubmatch(token)
		prefix, key, value := "", "", ""
		if m != nil {
			prefix, key, value = m[1], m[2], m[3]
		} else if env := envAssignment.FindStringSubmatch(token); env != nil {
			key, value = env[1], env[2]
		} else {
			return wrappers.String() + maskURLCredentials(token)
		}
		if isSensitiveKey(key) {
			return wrappers.String() + prefix + key + "=" + redactMask
		}
		if prefix != "--" || !isWrapperKey(key) {
			return wrappers.String() + maskURLCredentials(token)
		}
		wrappers.WriteString(prefix + key + "=")
		token = value
	}
}

func isWrapperKey(key string) bool {
	switch key {
	case "action_env", "repo_env", "test_env", "client_env", "jvmopt", "host_jvmopt":
		return true
	}
	return false
}

// RedactLogLine mirrors Kotlin Redactor.redactLogLine.
func RedactLogLine(line string) string {
	masked := authCredentials.ReplaceAllString(maskURLCredentials(line), "${1}${2}"+redactMask)
	// Split keeping whitespace separators, like Kotlin's (?<=\s)|(?=\s).
	pieces := logLinePieces.FindAllString(masked, -1)
	for i, piece := range pieces {
		if strings.TrimSpace(piece) == "" {
			continue
		}
		pieces[i] = RedactToken(piece)
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
