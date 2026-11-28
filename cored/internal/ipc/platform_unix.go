//go:build !windows

package ipc

import (
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"syscall"
)

type platform struct{}

func (platform) Listen(endpoint string) (net.Listener, error) {
	// Secure the parent before touching the endpoint: it prevents other users
	// reaching the socket between bind and chmod (also on macOS).
	dir := filepath.Dir(endpoint)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("create socket dir: %w", err)
	}
	info, err := os.Lstat(dir)
	if err != nil {
		return nil, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !info.IsDir() || !ok || stat.Uid != uint32(os.Getuid()) {
		return nil, fmt.Errorf("socket dir must be a directory owned by the current user: %s", dir)
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return nil, fmt.Errorf("restrict socket dir permissions: %w", err)
	}
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
