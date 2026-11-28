package heap_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/heap"
)

// Verifies TargetJDKDir lookup precedence after #583/#592: explicit JDKDir >
// TargetJDKDir (embedded target JDK) > system JAVA_HOME / PATH.
func TestBazelSpikeTargetJDKLookup(t *testing.T) {
	for _, tc := range []struct {
		name                       string
		system, target, configured bool
		want                       string
	}{
		{"embedded target wins over system JAVA_HOME", true, true, false, "embedded"},
		{"target selected without system tools", false, true, false, "embedded"},
		{"explicit JDK wins", true, true, true, "configured"},
		{"stripped embedded JRE falls back", true, false, false, "system"},
		{"no diagnostic tools", false, false, false, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			// Isolate resolution from the host JDK and PATH. Runner below never executes files.
			t.Setenv("PATH", root)
			t.Setenv("JAVA_HOME", filepath.Join(root, "system"))
			for label, enabled := range map[string]bool{"system": tc.system, "embedded": tc.target, "configured": tc.configured} {
				if !enabled {
					continue
				}
				bin := filepath.Join(root, label, "bin")
				if err := os.MkdirAll(bin, 0o755); err != nil {
					t.Fatal(err)
				}
				for _, name := range []string{"jcmd", "jstat", "jinfo"} {
					if runtime.GOOS == "windows" {
						name += ".exe"
					}
					if err := os.WriteFile(filepath.Join(bin, name), nil, 0o755); err != nil {
						t.Fatal(err)
					}
				}
			}
			p := heap.NewProber()
			if tc.configured {
				p.JDKDir = filepath.Join(root, "configured")
			}
			p.TargetJDKDir = func(context.Context, int32) string { return filepath.Join(root, "embedded") }
			calls := 0
			p.Runner = func(_ context.Context, name string, _ ...string) (string, error) {
				calls++
				if tc.want == "" || filepath.Dir(filepath.Dir(name)) != filepath.Join(root, tc.want) {
					t.Errorf("unexpected tool path %q", name)
				}
				return "", errors.New("unsupported option")
			}
			_, err := p.SampleFor(context.Background(), 42, 1000)
			if err == nil {
				t.Fatal("expected unavailable sample")
			}
			if tc.want == "" {
				if calls != 0 || heap.FailureCategory(err) != heap.FailureMissingTool {
					t.Fatalf("calls=%d error=%v", calls, err)
				}
				diagnostics := heap.FailureDiagnostics(err)
				if len(diagnostics) != 2 {
					t.Fatalf("diagnostics=%v", diagnostics)
				}
				for _, d := range diagnostics {
					if d.Category != heap.FailureMissingTool {
						t.Fatalf("diagnostic=%+v", d)
					}
				}
			} else if calls == 0 {
				t.Fatal("tool resolution was not exercised")
			}
		})
	}
}
