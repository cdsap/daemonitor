package builds

import "testing"

func TestDetectAgentParity(t *testing.T) {
	for _, tc := range []struct{ name, agent, provider string }{
		{"CLAUDECODE", "Claude Code", "Anthropic"},
		{"CLAUDE_CODE_SESSION_ID", "Claude Code", "Anthropic"},
		{"CLAUDE_EFFORT", "Claude Code", "Anthropic"},
		{"cursor_trace_id", "Cursor", "configurable"},
		{"CODEX_SANDBOX", "Codex", "OpenAI"},
		{"GEMINI_CLI", "Gemini CLI", "Google"},
		{"AIDER_MODEL", "Aider", "configurable"},
		{"AI_AGENT", "AI agent (unrecognized)", "unknown"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := DetectAgent([]string{"PATH", tc.name}, nil)
			if got == nil || got.Agent != tc.agent || got.Provider != tc.provider {
				t.Fatalf("got=%+v", got)
			}
			if got := DetectAgent([]string{tc.name}, map[string]struct{}{tc.name: {}}); got != nil {
				t.Fatalf("ambient attributed: %+v", got)
			}
		})
	}
	if DetectAgent([]string{"CLAUDECODE"}, map[string]struct{}{"claudecode": {}}) != nil || DetectAgent([]string{"PATH"}, nil) != nil {
		t.Fatal("ambient or ordinary names attributed")
	}
}

func TestDetectAgentProcess(t *testing.T) {
	for _, tc := range []struct {
		argv []string
		want string
	}{
		{[]string{"/usr/local/bin/claude", "--resume"}, "Claude Code"},
		{[]string{"/Applications/Cursor.app/Contents/MacOS/Cursor"}, "Cursor"},
		{[]string{"/usr/local/bin/codex"}, "Codex"},
		{[]string{"cursor-agent"}, "Cursor"},
		{[]string{"node", "/opt/bin/claude"}, "Claude Code"},
		{[]string{"node", "/opt/node_modules/@anthropic-ai/claude-code/cli.js"}, "Claude Code"},
		{[]string{"node", "/opt/node_modules/@google/gemini-cli/dist/index.js"}, "Gemini CLI"},
		{[]string{"/bin/gemini"}, "Gemini CLI"},
		{[]string{"python3", "-m", "aider"}, "Aider"},
		{[]string{"/venv/bin/aider"}, "Aider"},
		{[]string{"bash", "-c", "echo codex claude aider"}, ""},
		{[]string{"node", "build.js", "--message=claude"}, ""},
		{[]string{"/work/claude/project/bash"}, ""},
		{[]string{"echo", "codex"}, ""},
		{nil, ""},
	} {
		got := DetectAgentProcess(tc.argv)
		if tc.want == "" {
			if got != nil {
				t.Errorf("%v attributed: %+v", tc.argv, got)
			}
		} else if got == nil || got.Agent != tc.want {
			t.Errorf("%v got=%+v want=%s", tc.argv, got, tc.want)
		}
	}
}
