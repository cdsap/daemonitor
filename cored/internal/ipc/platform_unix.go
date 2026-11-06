//go:build !windows

package ipc

import (
	"context"
	"net"
	"os"
)

type platform struct{}

func (platform) Listen(endpoint string) (net.Listener, error) {
	_ = os.Remove(endpoint)
	ln, err := net.Listen("unix", endpoint)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(endpoint, 0o600); err != nil {
		_ = ln.Close()
		_ = os.Remove(endpoint)
		return nil, err
	}
	return &unixListener{Listener: ln, path: endpoint}, nil
}

func (platform) Dial(ctx context.Context, endpoint string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, "unix", endpoint)
}

type unixListener struct {
	net.Listener
	path string
}

func (l *unixListener) Close() error {
	err := l.Listener.Close()
	_ = os.Remove(l.path)
	return err
}
