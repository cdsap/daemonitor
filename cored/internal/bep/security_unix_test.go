//go:build unix

package bep

import (
	"os"
	"path/filepath"
	"syscall"
	"testing"

	"github.com/shirou/gopsutil/v4/process"
	"golang.org/x/sys/unix"
)

func TestWatcherRejectsSymlinksAndFIFO(t *testing.T) {
	dir := t.TempDir()
	regular := filepath.Join(dir, "regular")
	if err := os.WriteFile(regular, []byte("{}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "link")
	if err := os.Symlink(regular, link); err != nil {
		t.Fatal(err)
	}
	fifo := filepath.Join(dir, "fifo")
	if err := unix.Mkfifo(fifo, 0600); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{link, fifo, "/dev/null"} {
		tf := &trackedFile{sink: Sink{Path: path, Format: FormatJSON}}
		budget := int64(maxPollBytes)
		if _, err := NewWatcher().readFile(tf, &budget); err == nil || tf.offset != 0 || budget != maxPollBytes {
			t.Errorf("unsafe sink %s: err=%v offset=%d budget=%d", path, err, tf.offset, budget)
		}
	}
	// These flags also protect the interval between Lstat and descriptor validation.
	if f, err := openSinkFile(link); err == nil {
		f.Close()
		t.Fatal("opener followed a symlink")
	}
	f, err := openSinkFile(fifo)
	if err != nil {
		t.Fatal(err)
	}
	f.Close() // Must open without waiting for a writer.
}

type ownerInfo struct {
	os.FileInfo
	stat syscall.Stat_t
}

func (i ownerInfo) Sys() any { return &i.stat }

func TestBEPUnixOwnership(t *testing.T) {
	uid := uint32(os.Geteuid())
	if !ownedFile(ownerInfo{stat: syscall.Stat_t{Uid: uid}}) || ownedFile(ownerInfo{stat: syscall.Stat_t{Uid: uid + 1}}) {
		t.Fatal("file ownership must match current effective UID")
	}
	for _, tc := range []struct {
		uids []uint32
		want bool
	}{{nil, false}, {[]uint32{uid}, true}, {[]uint32{uid, uid, uid}, true}, {[]uint32{uid + 1}, false}, {[]uint32{uid, uid + 1}, false}} {
		if got := ownedUIDs(tc.uids, uid); got != tc.want {
			t.Errorf("ownedUIDs(%v) = %v", tc.uids, got)
		}
	}
	p, err := process.NewProcess(int32(os.Getpid()))
	if err != nil {
		t.Fatal(err)
	}
	if !ownedProcess(t.Context(), p) {
		t.Fatal("current process must be eligible")
	}
}

func TestWatcherRejectsForeignOwnedFile(t *testing.T) {
	// A system binary provides a real foreign-owned regular file without
	// requiring privileges to change the owner of a test fixture.
	path := "/bin/cat"
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || ownedFile(info) {
		t.Skip("no foreign-owned regular system binary available")
	}
	tf := &trackedFile{sink: Sink{Path: path, Format: FormatJSON}}
	budget := int64(maxPollBytes)
	if _, err := NewWatcher().readFile(tf, &budget); err == nil || tf.offset != 0 || budget != maxPollBytes {
		t.Fatalf("foreign-owned sink: err=%v offset=%d budget=%d", err, tf.offset, budget)
	}
}
