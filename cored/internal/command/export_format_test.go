package command

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestExportRejectsUnsupportedFormatsWithoutWritingFile(t *testing.T) {
	for _, tc := range []struct {
		name string
		argv []string
		want string
	}{
		{"csv", []string{"export", "--format", "csv", "--output", "OUT"}, "format csv is not supported for export"},
		{"plain", []string{"export", "--format", "plain", "--output", "OUT"}, "format plain is not supported for export"},
		{"json_flag", []string{"export", "--json", "--output", "OUT"}, "format json is not supported for export"},
		{"json_format", []string{"export", "--format", "json", "--output", "OUT"}, "format json is not supported for export"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			outPath := filepath.Join(t.TempDir(), "telemetry.out")
			argv := make([]string, len(tc.argv))
			copy(argv, tc.argv)
			for i, a := range argv {
				if a == "OUT" {
					argv[i] = outPath
				}
			}
			opts, err := ParseArgs(argv)
			if err != nil {
				t.Fatalf("ParseArgs(%v): %v", argv, err)
			}
			var stdout, stderr bytes.Buffer
			code := Run(context.Background(), opts, &stdout, &stderr)
			if code != 2 {
				t.Fatalf("exit code = %d, want 2 (stderr=%q)", code, stderr.String())
			}
			if !strings.Contains(stderr.String(), tc.want) {
				t.Fatalf("stderr = %q, want substring %q", stderr.String(), tc.want)
			}
			if _, statErr := os.Stat(outPath); !os.IsNotExist(statErr) {
				t.Fatalf("output file %s must not be created on unsupported format", outPath)
			}
		})
	}
}
