package api

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestObservationSessionOpenLimits(t *testing.T) {
	for _, tt := range []struct {
		name         string
		sessionName  string
		processCount int
		fromSnapshot bool
		wantStatus   int
	}{
		{"name below limit", strings.Repeat("a", 255), 1, false, http.StatusCreated},
		{"name at limit", strings.Repeat("a", 256), 1, false, http.StatusCreated},
		{"name over limit", strings.Repeat("a", 257), 1, false, http.StatusBadRequest},
		{"multibyte name at limit", strings.Repeat("é", 128), 1, false, http.StatusCreated},
		{"multibyte name over byte limit", strings.Repeat("é", 129), 1, false, http.StatusBadRequest},
		{"processes below limit", "session", 255, false, http.StatusCreated},
		{"processes at limit", "session", 256, false, http.StatusCreated},
		{"processes over limit", "session", 257, false, http.StatusBadRequest},
		{"snapshot at limit", "session", 256, true, http.StatusCreated},
		{"snapshot over limit", "session", 257, true, http.StatusBadRequest},
	} {
		t.Run(tt.name, func(t *testing.T) {
			st, err := store.Open(filepath.Join(t.TempDir(), "core.sqlite"))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = st.Close() })
			srv := &Server{Store: st}
			req := sessions.OpenRequest{Name: tt.sessionName, HostID: "test-host", Association: sessions.AssociationConfirmed}
			for i := 0; i < tt.processCount; i++ {
				if tt.fromSnapshot {
					srv.last.Processes = append(srv.last.Processes, model.Process{PID: int32(i + 1), StartTimeMs: 1})
				} else {
					req.Processes = append(req.Processes, sessions.ProcessRef{PID: int64(i + 1), StartTimeMs: 1})
				}
			}
			body, err := json.Marshal(req)
			if err != nil {
				t.Fatal(err)
			}
			w := httptest.NewRecorder()
			srv.handleObservationSessionOpen(w, httptest.NewRequest(http.MethodPost, "/v1/observation-sessions/open", bytes.NewReader(body)))
			if w.Code != tt.wantStatus {
				t.Fatalf("status=%d want %d: %s", w.Code, tt.wantStatus, w.Body.String())
			}
			response, err := st.CloseObservationSession(req.Name, time.Now().UnixMilli())
			if tt.wantStatus == http.StatusBadRequest {
				if !errors.Is(err, sql.ErrNoRows) {
					t.Fatalf("rejected session was persisted: err=%v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if response.Name != req.Name {
				t.Fatalf("persisted name=%q want %q", response.Name, req.Name)
			}
		})
	}
}
