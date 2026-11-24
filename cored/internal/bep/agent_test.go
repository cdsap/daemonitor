package bep

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestBEPAgentEnvNames(t *testing.T) {
	for _, metadata := range []string{
		`{"structuredCommandLine":{"sections":[{"optionList":{"option":[{"optionName":"client_env","optionValue":"CODEX_SANDBOX=private-test-value"}]}}]}}`,
		`{"unstructuredCommandLine":{"args":["--client_env=CODEX_SANDBOX=private-test-value"]}}`,
		`{"optionsParsed":{"cmdLine":["--client_env=CODEX_SANDBOX=private-test-value"]}}`,
	} {
		state := &partial{}
		body := `{"started":{"uuid":"agent","startTimeMillis":"100"}}` + "\n" + metadata + "\n"
		closed, pending, err := ConsumeJSONBytes(state, nil, []byte(body))
		if err != nil || len(closed) != 0 || len(pending) != 0 {
			t.Fatalf("consume: %v %v", closed, err)
		}
		snapshot, _ := json.Marshal(state)
		if strings.Contains(string(snapshot), "private-test-value") {
			t.Fatal("environment value retained in parser state")
		}
		closed, _, err = ConsumeJSONBytes(state, nil, []byte(`{"finished":{"finishTimeMillis":"200"}}`+"\n"))
		if err != nil || len(closed) != 1 || closed[0].Agent != "Codex" || closed[0].AgentProvider != "OpenAI" {
			t.Fatalf("closed=%+v err=%v", closed, err)
		}
		persisted, _ := json.Marshal(closed)
		if strings.Contains(string(persisted), "private-test-value") {
			t.Fatal("environment value leaked into build")
		}
	}
}

func TestBEPEnvAttributionIsolation(t *testing.T) {
	for _, tc := range []struct{ event, want string }{
		{`{"optionsParsed":{"cmdLine":["--client_env=PATH=CODEX_SANDBOX"]}}`, ""},
		{`{"optionsParsed":{"cmdLine":["--client_env=CLAUDECODE=hidden","--client_env=AI_AGENT=hidden"]}}`, ""},
		{`{"optionsParsed":{"cmdLine":["--client_env=CLAUDECODE=hidden","--client_env=CURSOR_TRACE_ID=hidden"]}}`, "Cursor"},
		{`{"optionsParsed":{"cmdLine":["--action_env=CODEX_SANDBOX=hidden","--repo_env=GEMINI_CLI=hidden"]}}`, ""},
		{`{"buildMetadata":{"metadata":{"CODEX_SANDBOX":"hidden","note":"CLAUDECODE"}}}`, ""},
		{`{"optionsParsed":{"cmdLine":["--client_env","AIDER_MODEL=hidden"]}}`, "Aider"},
	} {
		state := &partial{ambientEnvNames: map[string]struct{}{"claudecode": {}, "AI_AGENT": {}}}
		body := `{"started":{"uuid":"first","startTimeMillis":"100"}}` + "\n" + tc.event + "\n" + `{"finished":{"finishTimeMillis":"200"}}` + "\n" +
			`{"started":{"uuid":"second","startTimeMillis":"300"}}` + "\n" + `{"finished":{"finishTimeMillis":"400"}}` + "\n"
		closed, _, err := ConsumeJSONBytes(state, nil, []byte(body))
		if err != nil || len(closed) != 2 || closed[0].Agent != tc.want || closed[1].Agent != "" {
			t.Fatalf("closed=%+v err=%v", closed, err)
		}
	}
}

func TestNewBuildDiscardsIncompleteAgentMetadata(t *testing.T) {
	body := `{"started":{"uuid":"incomplete"}}` + "\n" + `{"optionsParsed":{"cmdLine":["--client_env=CODEX_SANDBOX=hidden"]}}` + "\n" +
		`{"started":{"uuid":"terminal"}}` + "\n" + `{"finished":{"finishTimeMillis":"200"}}` + "\n"
	got, err := ParseJSONStream(strings.NewReader(body))
	if err != nil || len(got) != 1 || got[0].Agent != "" {
		t.Fatalf("got=%+v err=%v", got, err)
	}
}
