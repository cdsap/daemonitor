package builds

import "strings"

// AgentAttribution is a named AI agent inferred from env-var names (KTD-8).
type AgentAttribution struct {
	Agent    string
	Provider string
}

type fingerprint struct {
	agent    string
	provider string
	match    func(names map[string]struct{}) bool
}

var fingerprints = []fingerprint{
	{"Claude Code", "Anthropic", func(n map[string]struct{}) bool {
		for name := range n {
			if name == "CLAUDECODE" || strings.HasPrefix(name, "CLAUDE_CODE") || name == "CLAUDE_EFFORT" {
				return true
			}
		}
		return false
	}},
	{"Cursor", "configurable", func(n map[string]struct{}) bool {
		for name := range n {
			if strings.HasPrefix(name, "CURSOR") {
				return true
			}
		}
		return false
	}},
	{"Codex", "OpenAI", func(n map[string]struct{}) bool {
		for name := range n {
			if strings.HasPrefix(name, "CODEX") {
				return true
			}
		}
		return false
	}},
	{"Gemini CLI", "Google", func(n map[string]struct{}) bool {
		for name := range n {
			if strings.HasPrefix(name, "GEMINI") {
				return true
			}
		}
		return false
	}},
	{"Aider", "configurable", func(n map[string]struct{}) bool {
		for name := range n {
			if strings.HasPrefix(name, "AIDER") {
				return true
			}
		}
		return false
	}},
}

// DetectAgent mirrors Kotlin AgentDetector.detect.
func DetectAgent(envNames []string, ambientEnvNames map[string]struct{}) *AgentAttribution {
	if len(envNames) == 0 {
		return nil
	}
	ambient := make(map[string]struct{}, len(ambientEnvNames))
	for n := range ambientEnvNames {
		ambient[strings.ToUpper(n)] = struct{}{}
	}
	names := make(map[string]struct{})
	for _, n := range envNames {
		u := strings.ToUpper(n)
		if _, ok := ambient[u]; ok {
			continue
		}
		names[u] = struct{}{}
	}
	if len(names) == 0 {
		return nil
	}
	for _, fp := range fingerprints {
		if fp.match(names) {
			return &AgentAttribution{Agent: fp.agent, Provider: fp.provider}
		}
	}
	for name := range names {
		if name == "AI_AGENT" || strings.HasPrefix(name, "AI_") {
			return &AgentAttribution{Agent: "AI agent (unrecognized)", Provider: "unknown"}
		}
	}
	return nil
}
