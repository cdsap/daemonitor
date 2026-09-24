package builds

import "strings"

var ideEnvMarkers = []string{
	"VSCODE", "INTELLIJ", "JETBRAINS", "TERMINAL_EMULATOR", "CURSOR", "PYCHARM", "ANDROID_STUDIO",
}
var terminalEnvMarkers = []string{"TERM_PROGRAM", "TERM_SESSION_ID", "ITERM_SESSION_ID"}

var ideProc = []string{"idea", "studio", "pycharm", "webstorm", "goland", "cursor", "code"}
var terminalProc = []string{"zsh", "bash", "fish", "tmux", "login", "terminal", "iterm"}

// DetectSource mirrors Kotlin SourceDetector.detect (env names primary, ancestry optional).
func DetectSource(envNames []string, ancestry []string) Source {
	names := make([]string, len(envNames))
	for i, n := range envNames {
		names[i] = strings.ToUpper(n)
	}
	for _, n := range names {
		for _, m := range ideEnvMarkers {
			if strings.Contains(n, m) {
				return SourceIDE
			}
		}
	}
	for _, n := range names {
		for _, m := range terminalEnvMarkers {
			if strings.Contains(n, m) {
				return SourceTerminal
			}
		}
	}
	procs := make([]string, len(ancestry))
	for i, p := range ancestry {
		procs[i] = strings.ToLower(p)
	}
	for _, p := range procs {
		for _, m := range ideProc {
			if strings.Contains(p, m) {
				return SourceIDE
			}
		}
	}
	for _, p := range procs {
		for _, m := range terminalProc {
			if strings.Contains(p, m) {
				return SourceTerminal
			}
		}
	}
	return SourceUnknown
}
