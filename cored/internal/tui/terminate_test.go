package tui

import (
	"context"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/shirou/gopsutil/v4/process"
)

const terminateHelperEnv = "DAEMONITOR_TUI_TERMINATE_HELPER"

func TestTerminateHelperProcess(t *testing.T) {
	if os.Getenv(terminateHelperEnv) != "1" {
		t.Skip("helper process only")
	}
	time.Sleep(time.Minute)
	os.Exit(0)
}

func startTerminateHelper(t *testing.T) (*exec.Cmd, int64) {
	t.Helper()
	cmd := exec.Command(os.Args[0], "-test.run=^TestTerminateHelperProcess$")
	cmd.Env = append(os.Environ(), terminateHelperEnv+"=1")
	if err := cmd.Start(); err != nil {
		t.Fatalf("start helper: %v", err)
	}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	})
	p, err := process.NewProcess(int32(cmd.Process.Pid))
	if err != nil {
		t.Fatalf("inspect helper: %v", err)
	}
	created, err := p.CreateTime()
	if err != nil {
		t.Fatalf("helper create time: %v", err)
	}
	return cmd, created
}

func TestTerminateProcessStopsMatchingProcess(t *testing.T) {
	cmd, created := startTerminateHelper(t)
	if err := TerminateProcess(context.Background(), int32(cmd.Process.Pid), created); err != nil {
		t.Fatalf("terminate: %v", err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("helper process did not exit after terminate")
	}
}

func TestTerminateProcessRefusesReusedPID(t *testing.T) {
	cmd, created := startTerminateHelper(t)
	err := TerminateProcess(context.Background(), int32(cmd.Process.Pid), created+60_000)
	if err == nil || !strings.Contains(err.Error(), "different process") {
		t.Fatalf("err=%v want start-time mismatch refusal", err)
	}
	if running, _ := process.PidExists(int32(cmd.Process.Pid)); !running {
		t.Fatal("helper was signalled despite start-time mismatch")
	}
}

func TestTerminateProcessRejectsInvalidAndSelfPID(t *testing.T) {
	if err := TerminateProcess(context.Background(), 0, 0); err == nil {
		t.Fatal("expected error for pid 0")
	}
	if err := TerminateProcess(context.Background(), int32(os.Getpid()), 0); err == nil {
		t.Fatal("expected refusal to terminate the CLI itself")
	}
}
