package tui

import (
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

type detailRoundTripFunc func(*http.Request) (*http.Response, error)

func (f detailRoundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func TestFetchDetailsSkipsGradleEndpointsForBazelServer(t *testing.T) {
	var paths []string
	c := &client.Client{HTTP: &http.Client{Transport: detailRoundTripFunc(func(req *http.Request) (*http.Response, error) {
		paths = append(paths, req.URL.Path)
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader(`{}`)),
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})}}
	m := NewModel(Config{Client: c})
	cmd := m.fetchDetails(model.Process{PID: 11, StartTimeMs: 100, Type: "BAZEL_SERVER"})
	if cmd == nil {
		t.Fatal("expected detail fetch command")
	}
	cmd()

	for _, path := range paths {
		if strings.HasPrefix(path, "/v1/daemon-logs") || path == "/v1/builds" {
			t.Fatalf("Bazel details requested Gradle-only endpoint %q; all requests: %v", path, paths)
		}
	}
	for _, want := range []string{"/v1/processes/history", "/v1/host", "/v1/capabilities"} {
		if !containsPath(paths, want) {
			t.Fatalf("Bazel details omitted shared endpoint %q; requests: %v", want, paths)
		}
	}
}

func containsPath(paths []string, want string) bool {
	for _, path := range paths {
		if path == want {
			return true
		}
	}
	return false
}
