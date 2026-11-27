//go:build !unix

package bep

import (
	"context"
	"os"

	"github.com/shirou/gopsutil/v4/process"
)

func openSinkFile(path string) (*os.File, error) { return os.Open(path) }

// Unix UID checks do not apply on these platforms. Path and descriptor
// validation still enforce regular files, identity, and byte limits.
func ownedFile(_ os.FileInfo) bool                            { return true }
func ownedProcess(_ context.Context, _ *process.Process) bool { return true }
