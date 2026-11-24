package bep

// Issue #579: executable experiments, deliberately outside the production watcher.
// See docs/plans/2026-11-23-bazel-passive-output-base-spike.md. These tests
// characterize fixtures and completion hazards; they do not enable ingestion.
import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/poll"
)

type spikeTrace struct {
	Metadata struct {
		BuildID string `json:"build_id"`
		Version string `json:"bazel_version"`
		Date    string `json:"date"`
		Start   *int64 `json:"profile_start_ts"`
		Finish  *int64 `json:"profile_finish_ts"`
	} `json:"otherData"`
	Events []struct {
		Name      string                     `json:"name"`
		Category  string                     `json:"cat"`
		Phase     string                     `json:"ph"`
		Timestamp float64                    `json:"ts"`
		Duration  float64                    `json:"dur"`
		Args      map[string]json.RawMessage `json:"args"`
	} `json:"traceEvents"`
}

// A bounded, complete-snapshot decoding experiment. A watcher must additionally
// enforce ownership, path containment and before/after file identity checks.
func spikeDecodeProfile(compressed []byte, compressedLimit, decodedLimit int64) (spikeTrace, error) {
	var trace spikeTrace
	if int64(len(compressed)) > compressedLimit {
		return trace, fmt.Errorf("compressed limit")
	}
	z, err := gzip.NewReader(bytes.NewReader(compressed))
	if err != nil {
		return trace, err
	}
	defer z.Close()
	// Read to EOF, not just the JSON closing brace: gzip validates its trailer
	// on EOF. Close alone does not check truncation or CRC errors.
	data, err := io.ReadAll(io.LimitReader(z, decodedLimit+1))
	if err != nil {
		return trace, err
	}
	if int64(len(data)) > decodedLimit {
		return trace, fmt.Errorf("decoded limit")
	}
	err = json.Unmarshal(data, &trace)
	return trace, err
}

func spikeFixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "passive", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func spikeGzip(t *testing.T, data []byte) []byte {
	t.Helper()
	var b bytes.Buffer
	z := gzip.NewWriter(&b)
	if _, err := z.Write(data); err != nil {
		t.Fatal(err)
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func TestPassiveSpikeProfileShapes(t *testing.T) {
	for _, version := range []string{"7", "8", "9"} {
		t.Run(version, func(t *testing.T) {
			trace, err := spikeDecodeProfile(spikeGzip(t, spikeFixture(t, "bazel-"+version+".profile.json")), 16<<20, 64<<20)
			if err != nil {
				t.Fatal(err)
			}
			if !strings.HasPrefix(trace.Metadata.Version, "release "+version+".") || trace.Metadata.BuildID == "" || trace.Metadata.Date == "" {
				t.Fatalf("missing identity: %+v", trace.Metadata)
			}
			if version == "7" {
				if trace.Metadata.Start != nil || trace.Metadata.Finish == nil || *trace.Metadata.Finish != 1795435200000 {
					t.Fatal("7.x metadata changed")
				}
			} else if trace.Metadata.Finish != nil || trace.Metadata.Start == nil || *trace.Metadata.Start != 1795435200123 {
				t.Fatal("8/9 metadata changed")
			}
			var heap, cpu, concurrency, gcUS, criticalUS, executionUS float64
			var executionStart float64
			for _, e := range trace.Events {
				if e.Phase == "C" {
					for key, target := range map[string]*float64{"memory": &heap, "cpu": &cpu, "action": &concurrency} {
						if raw, ok := e.Args[key]; ok {
							var value float64
							if err := json.Unmarshal(raw, &value); err != nil {
								t.Fatal(err)
							}
							if value > *target {
								*target = value
							}
						}
					}
				}
				if e.Category == "gc notification" && e.Phase == "X" {
					gcUS += e.Duration
				}
				if e.Category == "critical path component" && e.Phase == "X" {
					criticalUS += e.Duration
				}
				if e.Category == "build phase marker" && e.Phase == "i" {
					if e.Name == "Build artifacts" {
						executionStart = e.Timestamp
					}
					if e.Name == "Complete build" {
						executionUS = e.Timestamp - executionStart
					}
				}
			}
			if heap != 128.5 || cpu != 1.5 || concurrency != 2.5 || gcUS != 2000 || criticalUS != 600000 || executionUS != 650000 {
				t.Fatalf("units/phase shape: heap=%v cpu=%v actions=%v gc=%v critical=%v execution=%v", heap, cpu, concurrency, gcUS, criticalUS, executionUS)
			}
		})
	}
}

func TestPassiveSpikeRejectsIncompleteAndOversizedProfiles(t *testing.T) {
	plain := spikeFixture(t, "bazel-8.profile.json")
	valid := spikeGzip(t, plain)
	badCRC := bytes.Clone(valid)
	badCRC[len(badCRC)-8] ^= 0xff
	for name, data := range map[string][]byte{
		"partial header":   valid[:5],
		"missing trailer":  valid[:len(valid)-8],
		"bad CRC":          badCRC,
		"incomplete JSON":  spikeGzip(t, plain[:len(plain)-4]),
		"trailing JSON":    spikeGzip(t, append(bytes.Clone(plain), []byte(` {}`)...)),
		"trailing garbage": append(bytes.Clone(valid), []byte("garbage")...),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := spikeDecodeProfile(data, 16<<20, 64<<20); err == nil {
				t.Fatal("accepted incomplete/corrupt profile")
			}
		})
	}
	for _, limits := range [][2]int64{{int64(len(valid) - 1), 64 << 20}, {16 << 20, int64(len(plain) - 1)}} {
		if _, err := spikeDecodeProfile(valid, limits[0], limits[1]); err == nil {
			t.Fatal("read limit ignored")
		}
	}
	if _, err := spikeDecodeProfile(valid, int64(len(valid)), int64(len(plain))); err != nil {
		t.Fatalf("exact limits: %v", err)
	}
}

func TestPassiveSpikeStableSizeDoesNotMeanComplete(t *testing.T) {
	path := filepath.Join(t.TempDir(), "command.profile.gz")
	data := spikeGzip(t, spikeFixture(t, "bazel-7.profile.json"))
	if err := os.WriteFile(path, data[:len(data)-8], 0600); err != nil {
		t.Fatal(err)
	}
	before, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	after, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if !os.SameFile(before, after) || before.Size() != after.Size() || !before.ModTime().Equal(after.ModTime()) {
		t.Fatal("fixture changed unexpectedly")
	}
	partial, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := spikeDecodeProfile(partial, 16<<20, 64<<20); err == nil {
		t.Fatal("stable incomplete gzip accepted")
	}
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	complete, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := spikeDecodeProfile(complete, 16<<20, 64<<20); err != nil {
		t.Fatalf("retry after completion: %v", err)
	}
}

func TestPassiveSpikeCommandLogReplacement(t *testing.T) {
	path := filepath.Join(t.TempDir(), "command.log")
	old := spikeFixture(t, "command-success.txt")
	if err := os.WriteFile(path, old, 0600); err != nil {
		t.Fatal(err)
	}
	f, err := spikeOpenLog(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	before, err := f.Stat()
	if err != nil {
		t.Fatal(err)
	}
	// Reproduce Bazel's unlink + recreate while a read handle is open. On
	// Windows this is a native sharing probe; failure is a failed gate, not a skip.
	if err := os.Remove(path); err != nil {
		t.Fatalf("reader blocked command.log replacement: %v", err)
	}
	if runtime.GOOS == "windows" {
		// Delete sharing permits deletion, but DeleteFile can leave the path
		// delete-pending until handles close. Immediate Bazel recreation needs
		// its own native test; do not hold a passive reader across polls.
		if err := f.Close(); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(path, old, 0600); err != nil {
		t.Fatal(err)
	}
	after, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if after.Size() != before.Size() || os.SameFile(before, after) {
		t.Fatal("same-size replacement not distinguished by file identity")
	}
	// A shorter overwrite must reset offsets and pending lines as well.
	if err := os.WriteFile(path, []byte("INFO: next command\n"), 0600); err != nil {
		t.Fatal(err)
	}
	truncated, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if truncated.Size() >= after.Size() {
		t.Fatal("truncation fixture did not shrink")
	}
}

func TestPassiveSpikeLogEvidenceAndRedaction(t *testing.T) {
	for _, tc := range []struct{ file, outcome string }{
		{"command-success.txt", "Build completed successfully"},
		{"command-failed.txt", "FAILED: Build did NOT complete successfully"},
	} {
		log := string(spikeFixture(t, tc.file))
		for _, marker := range []string{"INFO: Invocation ID:", "INFO: Elapsed time:", "Critical Path:", tc.outcome} {
			if !strings.Contains(log, marker) {
				t.Fatalf("%s missing %s", tc.file, marker)
			}
		}
		// CRLF must not change recognition of the complete lines.
		normalized := strings.ReplaceAll(log, "\r\n", "\n")
		for _, line := range strings.Split(strings.ReplaceAll(normalized, "\n", "\r\n"), "\n") {
			if got := poll.RedactLogLine(strings.TrimSuffix(line, "\r")); strings.ContainsRune(got, '\r') {
				t.Fatal("CRLF handling")
			}
		}
	}
	line := "INFO: --remote_password=fixture-value https://fixture-user:fixture-pass@example.invalid/build"
	if got := poll.RedactLogLine(line); got != "INFO: --remote_password=*** https://***:***@example.invalid/build" {
		t.Fatalf("redaction: %s", got)
	}
	// Characterize the existing limitation. Arbitrary application output is
	// not safe to retain just because RedactLogLine was called.
	unsupported := "Authorization: Bearer fixture-value"
	if poll.RedactLogLine(unsupported) != unsupported {
		t.Fatal("update report: redactor now handles free-text bearer values")
	}
	jvm := string(spikeFixture(t, "jvm-out.txt"))
	if !strings.Contains(jvm, "GC(0)") || strings.Contains(jvm, "Invocation ID:") {
		t.Fatal("JVM diagnostics are not invocation boundaries")
	}
}
