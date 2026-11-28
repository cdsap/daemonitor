package bep

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/builds"
)

func TestBazelClientAncestry(t *testing.T) {
	for _, tc := range []struct {
		agent, provider string
		argv            []string
	}{
		{"Claude Code", "Anthropic", []string{"claude"}},
		{"Cursor", "configurable", []string{"/Applications/Cursor.app/Contents/MacOS/Cursor"}},
		{"Codex", "OpenAI", []string{"codex"}},
		{"Gemini CLI", "Google", []string{"node", "/opt/@google/gemini-cli/dist/index.js"}},
		{"Aider", "configurable", []string{"python3", "-m", "aider"}},
	} {
		for _, client := range []string{"bazel", "bazelisk"} {
			read := func(_ context.Context, pid int32) (int32, []string, error) {
				switch pid {
				case 40:
					return 30, []string{client, "build", "//:app"}, nil
				case 30:
					return 20, []string{"bash"}, nil
				case 20:
					return 1, tc.argv, nil
				}
				return 0, nil, errors.New("gone")
			}
			got := bazelClientAgent(t.Context(), 40, read)
			if got == nil || got.Agent != tc.agent || got.Provider != tc.provider {
				t.Fatalf("got=%+v", got)
			}
		}
	}
}

func TestBazelClientAncestryStops(t *testing.T) {
	for _, tc := range []struct {
		parent int32
		err    error
	}{{9, nil}, {0, errors.New("denied")}, {1, nil}} {
		calls := 0
		got := bazelClientAgent(t.Context(), 9, func(context.Context, int32) (int32, []string, error) {
			calls++
			return tc.parent, []string{"bash"}, tc.err
		})
		if got != nil || calls != 1 {
			t.Fatalf("got=%+v calls=%d", got, calls)
		}
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if got := bazelClientAgent(ctx, 9, func(context.Context, int32) (int32, []string, error) {
		t.Fatal("read after cancel")
		return 0, nil, nil
	}); got != nil {
		t.Fatal(got)
	}
}

func TestWatcherClientAttributionSurvivesExitAndDoesNotLeak(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bep.json")
	sink := Sink{Path: path, Format: FormatJSON, ClientPID: 40}
	w := NewWatcher()
	w.ambientEnvNames = map[string]struct{}{"CLAUDECODE": {}}
	w.observeClient(clientObservation{Sink: sink, Agent: &builds.AgentAttribution{Agent: "Claude Code", Provider: "Anthropic"}, StartTimeMs: 90, ObservedAtMs: 150})
	// An ancestor may disappear while its original client is still alive.
	w.observeClient(clientObservation{Sink: sink, StartTimeMs: 90, ObservedAtMs: 150})
	tf := w.files[path]
	body := `{"started":{"uuid":"agent","startTimeMillis":"100"}}` + "\n" + `{"optionsParsed":{"cmdLine":["--client_env=CLAUDECODE=hidden"]}}` + "\n"
	if err := os.WriteFile(path, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}
	budget := int64(maxPollBytes)
	closed, err := w.readFile(tf, &budget)
	if err != nil || len(closed) != 0 {
		t.Fatalf("closed=%v err=%v", closed, err)
	}
	// No further process observation: the client and its ancestors can exit.
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.WriteString(`{"finished":{"finishTimeMillis":"200"}}` + "\n" +
		`{"started":{"uuid":"terminal","startTimeMillis":"300"}}` + "\n" + `{"finished":{"finishTimeMillis":"400"}}` + "\n")
	if err != nil {
		t.Fatal(err)
	}
	if err = f.Close(); err != nil {
		t.Fatal(err)
	}
	closed, err = w.readFile(tf, &budget)
	if err != nil || len(closed) != 2 || closed[0].Agent != "Claude Code" || closed[0].AgentProvider != "Anthropic" || closed[1].Agent != "" {
		t.Fatalf("closed=%+v err=%v", closed, err)
	}
	// A subsequent client reusing the sink has independent ancestry.
	w.observeClient(clientObservation{Sink: sink, StartTimeMs: 500, ObservedAtMs: 600})
	if w.files[path].client.Agent != nil {
		t.Fatal("previous agent leaked to new client")
	}
}

func TestWatcherAmbientNames(t *testing.T) {
	t.Setenv("CLAUDECODE", "private-test-value")
	w := NewWatcher()
	if _, ok := w.ambientEnvNames["CLAUDECODE"]; !ok {
		t.Fatal("missing ambient name")
	}
	path := filepath.Join(t.TempDir(), "bep.json")
	body := `{"started":{"uuid":"ambient","startTimeMillis":"100"}}` + "\n" + `{"optionsParsed":{"cmdLine":["--client_env=CLAUDECODE=hidden"]}}` + "\n" + `{"finished":{"finishTimeMillis":"200"}}` + "\n"
	if err := os.WriteFile(path, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}
	budget := int64(maxPollBytes)
	closed, err := w.readFile(&trackedFile{sink: Sink{Path: path, Format: FormatJSON}}, &budget)
	if err != nil || len(closed) != 1 || closed[0].Agent != "" {
		t.Fatalf("closed=%+v err=%v", closed, err)
	}
}
