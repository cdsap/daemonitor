package platform

import (
	"os"
	"sync"
)

var (
	hostIDOnce sync.Once
	hostID     string
)

// LocalHostID returns a cheap, stable-enough host label for process instance IDs.
// It uses the OS hostname when available and an empty string otherwise.
func LocalHostID() string {
	hostIDOnce.Do(func() {
		name, err := os.Hostname()
		if err != nil {
			return
		}
		hostID = name
	})
	return hostID
}
