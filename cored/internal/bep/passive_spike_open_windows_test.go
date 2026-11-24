package bep

import (
	"os"

	"golang.org/x/sys/windows"
)

// This is a native Windows experiment, not the production watcher's opener.
// Go's ordinary os.Open does not request FILE_SHARE_DELETE. A passive tail
// must permit Bazel to unlink command.log while the reader is holding it.
func spikeOpenLog(path string) (*os.File, error) {
	name, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	h, err := windows.CreateFile(name, windows.GENERIC_READ,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil, windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(h), path), nil
}
