package store

import (
	"os"
	"path/filepath"
	"runtime"
)

// DefaultWatcherDBPath returns the app WatcherDatabase path for this OS, matching Kotlin
// AppDirectories.discover().databasePath (…/Daemonitor/watcher.db).
//
// Use with daemonitor-cored -db so the core and CLI/desktop open the same file under WAL.
func DefaultWatcherDBPath() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = os.TempDir()
	}
	return filepath.Join(appSupportDir(home), "watcher.db")
}

func appSupportDir(home string) string {
	switch runtime.GOOS {
	case "windows":
		if local := os.Getenv("LOCALAPPDATA"); local != "" {
			return filepath.Join(local, "Daemonitor")
		}
		return filepath.Join(home, "AppData", "Local", "Daemonitor")
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "Daemonitor")
	default:
		if xdg := os.Getenv("XDG_DATA_HOME"); xdg != "" {
			return filepath.Join(xdg, "Daemonitor")
		}
		return filepath.Join(home, ".local", "share", "Daemonitor")
	}
}
