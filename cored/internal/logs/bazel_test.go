package logs

import (
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestBazelLiveTails(t *testing.T) {
	base := t.TempDir()
	processes := []model.Process{{PID: 42, Type: "BAZEL_SERVER", BazelOutputBase: base, StartTimeMs: 1}}
	w := NewBazelWatcher()
	w.Poll(processes)
	if len(w.List()) != 0 {
		t.Fatal("absent logs should be ignored")
	}
	if err := os.Mkdir(filepath.Join(base, "server"), 0700); err != nil {
		t.Fatal(err)
	}
	write := func(source, content string) {
		t.Helper()
		if err := os.WriteFile(filepath.Join(base, filepath.FromSlash(source)), []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write("lock", "untouched")
	write("command.log", "old invocation\nrunning --token=secret\npartial --password=sec")
	write("server/jvm.out", "server https://user:pass@example.com\n")
	w.Poll(processes)
	tail, ok := w.TailFor(42, "")
	if !ok || tail.Tool != "bazel" || tail.Source != "command.log" || !reflect.DeepEqual(tail.Lines, []string{"old invocation", "running --token=***"}) {
		t.Fatalf("tail = %+v", tail)
	}
	f, err := os.OpenFile(filepath.Join(base, "command.log"), os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.WriteString("ret\n")
	if err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	w.Poll(processes)
	w.Poll(processes)
	tail, _ = w.TailFor(42, "command.log")
	if len(tail.Lines) != 3 || tail.Lines[2] != "partial --password=***" {
		t.Fatalf("partial/duplicate: %v", tail.Lines)
	}
	server, ok := w.TailFor(42, "server/jvm.out")
	if !ok || len(server.Lines) != 1 || strings.Contains(server.Lines[0], "user:pass") {
		t.Fatalf("server = %+v", server)
	}
	for _, content := range []string{"", "new\n", strings.Repeat("a", 80) + "\n", strings.Repeat("b", 80) + "\n"} {
		write("command.log", content)
		w.Poll(processes)
		tail, _ = w.TailFor(42, "")
		expected := []string{}
		if content != "" {
			expected = append(expected, strings.TrimSuffix(content, "\n"))
		}
		if !reflect.DeepEqual(tail.Lines, expected) {
			t.Fatalf("rewrite: %v", tail.Lines)
		}
	}
	if err := os.Rename(filepath.Join(base, "command.log"), filepath.Join(base, "previous.log")); err != nil {
		t.Fatal(err)
	}
	write("command.log", "rotated\n")
	w.Poll(processes)
	tail, _ = w.TailFor(42, "")
	if !reflect.DeepEqual(tail.Lines, []string{"rotated"}) {
		t.Fatalf("rotation: %v", tail.Lines)
	}
	if err := os.Remove(filepath.Join(base, "command.log")); err != nil {
		t.Fatal(err)
	}
	w.Poll(processes)
	tail, ok = w.TailFor(42, "")
	if !ok || tail.Source != "server/jvm.out" {
		t.Fatalf("missing command log fallback: %+v", tail)
	}
	if _, ok := w.TailFor(42, "command.log"); ok {
		t.Fatal("removed log retained")
	}
	write("command.log", "recreated\n")
	w.Poll(processes)
	tail, _ = w.TailFor(42, "")
	if !reflect.DeepEqual(tail.Lines, []string{"recreated"}) {
		t.Fatalf("recreated: %v", tail.Lines)
	}
	data, err := os.ReadFile(filepath.Join(base, "lock"))
	if err != nil || string(data) != "untouched" {
		t.Fatalf("lock changed: %q %v", data, err)
	}
	w.Poll(nil)
	if len(w.List()) != 0 {
		t.Fatal("stopped server retained")
	}
}

func TestBazelTailBoundsAndLongLines(t *testing.T) {
	base := t.TempDir()
	path := filepath.Join(base, "command.log")
	p := []model.Process{{PID: 7, Type: "BAZEL_SERVER", BazelOutputBase: base}}
	w := NewBazelWatcher()
	var content strings.Builder
	content.WriteString(strings.Repeat("x", defaultInitialReadBytes*2) + "\n")
	for i := 0; i < 150; i++ {
		fmt.Fprintf(&content, "line %d --token=secret\r\n", i)
	}
	if err := os.WriteFile(path, []byte(content.String()), 0600); err != nil {
		t.Fatal(err)
	}
	w.Poll(p)
	tail, _ := w.TailFor(7, "")
	if len(tail.Lines) != defaultTailLines || tail.Lines[0] != "line 50 --token=***" || tail.Lines[99] != "line 149 --token=***" {
		t.Fatalf("bounded tail: %v", tail.Lines)
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.WriteString("appended\n"); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	w.Poll(p)
	tail, _ = w.TailFor(7, "")
	if len(tail.Lines) != defaultTailLines || tail.Lines[0] != "line 51 --token=***" || tail.Lines[99] != "appended" {
		t.Fatalf("appended bound: %v", tail.Lines)
	}
	if err := os.WriteFile(path, []byte(strings.Repeat("x", defaultInitialReadBytes*2)), 0600); err != nil {
		t.Fatal(err)
	}
	w.Poll(p)
	if err := os.WriteFile(path, []byte(strings.Repeat("x", defaultInitialReadBytes*2)+"\nnext\n"), 0600); err != nil {
		t.Fatal(err)
	}
	w.Poll(p)
	tail, _ = w.TailFor(7, "")
	if !reflect.DeepEqual(tail.Lines, []string{"next"}) {
		t.Fatalf("long line suffix leaked: %v", tail.Lines)
	}
}
