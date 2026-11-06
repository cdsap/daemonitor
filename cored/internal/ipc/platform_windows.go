//go:build windows

package ipc

import (
	"context"
	"fmt"
	"net"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
)

type platform struct{}

func (platform) Listen(endpoint string) (net.Listener, error) {
	sddl, err := currentUserPipeSDDL()
	if err != nil {
		return nil, err
	}
	return winio.ListenPipe(endpoint, &winio.PipeConfig{
		SecurityDescriptor: sddl,
	})
}

func (platform) Dial(ctx context.Context, endpoint string) (net.Conn, error) {
	return winio.DialPipeContext(ctx, endpoint)
}

func currentUserPipeSDDL() (string, error) {
	sid, err := currentUserSID()
	if err != nil {
		return "", fmt.Errorf("resolve current user SID for named pipe ACL: %w", err)
	}
	return pipeSDDLForUserSID(sid), nil
}

func currentUserSID() (string, error) {
	token := windows.GetCurrentProcessToken()
	user, err := token.GetTokenUser()
	if err != nil {
		return "", err
	}
	return user.User.Sid.String(), nil
}
