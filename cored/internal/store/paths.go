package store

import (
	"os"
	"path/filepath"
	"runtime"

	"github.com/cdsap/daemonitor/cored/internal/ipc"
)

// DefaultWatcherDBPath returns the app WatcherDatabase path for this OS, matching Kotlin
// AppDirectories.discover().databasePath (…/Daemonitor/watcher.db).
func DefaultWatcherDBPath() string {
	return filepath.Join(DefaultAppSupportDir(), "watcher.db")
}

// DefaultSocketPath is the local IPC endpoint for daemonitor-cored.
// On macOS/Linux this is a Unix-domain socket beside the app DB; on Windows it is a
// named-pipe path (\\.\pipe\daemonitor-core). --socket / -socket accept either form.
func DefaultSocketPath() string {
	return defaultSocketPath(runtime.GOOS, DefaultAppSupportDir())
}

func defaultSocketPath(goos, appSupport string) string {
	if goos == "windows" {
		return ipc.DefaultWindowsPipePath
	}
	return filepath.Join(appSupport, "daemonitor-core.sock")
}

// DefaultAppSupportDir matches Kotlin AppDirectories.appSupportDir.
func DefaultAppSupportDir() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = os.TempDir()
	}
	return appSupportDir(home)
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
