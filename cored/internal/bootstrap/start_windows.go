//go:build windows

package bootstrap

import (
	"os/exec"
	"syscall"
)

// StartCored launches daemonitor-cored detached from the TUI stdio.
func StartCored(binary, socket, db string) error {
	cmd := exec.Command(binary, "-socket", socket, "-db", db)
	cmd.Stdout = nil
	cmd.Stderr = nil
	cmd.Stdin = nil
	cmd.SysProcAttr = &syscall.SysProcAttr{
		CreationFlags: 0x00000200, // CREATE_NEW_PROCESS_GROUP
		HideWindow:    true,
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	_ = cmd.Process.Release()
	return nil
}
