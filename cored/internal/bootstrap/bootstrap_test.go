package bootstrap_test

import (
	"errors"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/bootstrap"
)

func TestConnectionErrorMessage(t *testing.T) {
	err := &bootstrap.Error{
		Socket: "/tmp/x.sock",
		Err:    errors.New("connection refused"),
		Hint:   "Start daemonitor-cored or check --socket.",
	}
	msg := err.Error()
	if !strings.Contains(msg, "/tmp/x.sock") {
		t.Fatalf("missing socket: %s", msg)
	}
	if !strings.Contains(msg, "connection refused") {
		t.Fatalf("missing cause: %s", msg)
	}
	if !strings.Contains(msg, "Start daemonitor-cored") {
		t.Fatalf("missing hint: %s", msg)
	}
}
