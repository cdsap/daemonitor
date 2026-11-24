//go:build !windows

package bep

import (
	"os"
	"path/filepath"
	"testing"
)

func spikeOpenLog(path string) (*os.File, error) {
	return os.Open(path)
}

func TestPassiveSpikeProfileSymlinkSwitch(t *testing.T) {
	base := t.TempDir()
	for _, name := range []string{"command-first.profile.gz", "command-next.profile.gz"} {
		if err := os.WriteFile(filepath.Join(base, name), []byte("same size"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	link := filepath.Join(base, "command.profile.gz")
	if err := os.Symlink("command-first.profile.gz", link); err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(link)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	opened, err := f.Stat()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(link); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("command-next.profile.gz", link); err != nil {
		t.Fatal(err)
	}
	current, err := os.Stat(link)
	if err != nil {
		t.Fatal(err)
	}
	if opened.Size() != current.Size() || os.SameFile(opened, current) {
		t.Fatal("symlink retarget must invalidate the snapshot despite equal sizes")
	}
}
