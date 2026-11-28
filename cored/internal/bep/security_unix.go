//go:build unix

package bep

import (
	"context"
	"os"
	"syscall"

	"github.com/shirou/gopsutil/v4/process"
	"golang.org/x/sys/unix"
)

func openSinkFile(path string) (*os.File, error) {
	// Nonblocking prevents a regular-file-to-FIFO replacement from hanging
	// before the descriptor can be checked. No-follow rejects symlink swaps.
	fd, err := unix.Open(path, unix.O_RDONLY|unix.O_NONBLOCK|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(fd), path), nil
}

func ownedFile(info os.FileInfo) bool {
	stat, ok := info.Sys().(*syscall.Stat_t)
	return ok && stat.Uid == uint32(os.Geteuid())
}

func ownedProcess(ctx context.Context, p *process.Process) bool {
	uids, err := p.UidsWithContext(ctx)
	return err == nil && ownedUIDs(uids, uint32(os.Geteuid()))
}

func ownedUIDs(uids []uint32, uid uint32) bool {
	if len(uids) == 0 {
		return false
	}
	for _, owner := range uids {
		if owner != uid {
			return false
		}
	}
	return true
}
