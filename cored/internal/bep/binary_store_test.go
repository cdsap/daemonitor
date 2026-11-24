package bep_test

import (
	"bytes"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/bep"
	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func TestBinaryFixturesPersistIdenticalRows(t *testing.T) {
	for _, fixture := range []string{"build_success", "build_failed_then_incomplete"} {
		t.Run(fixture, func(t *testing.T) {
			var rows [2][]builds.Build
			for i, ext := range []string{"ndjson", "bep"} {
				data, err := os.ReadFile(filepath.Join("testdata", fixture+"."+ext))
				if err != nil {
					t.Fatal(err)
				}
				var parsed []builds.Build
				if i == 0 {
					parsed, err = bep.ParseJSONStream(bytes.NewReader(data))
				} else {
					parsed, err = bep.ParseBinaryStream(bytes.NewReader(data))
				}
				if err != nil || len(parsed) != 1 {
					t.Fatalf("%s: parsed=%v err=%v", ext, parsed, err)
				}
				db, err := store.Open(filepath.Join(t.TempDir(), "builds.sqlite"))
				if err != nil {
					t.Fatal(err)
				}
				defer db.Close()
				for _, b := range parsed {
					if err := db.InsertBuild(b); err != nil {
						t.Fatal(err)
					}
				}
				rows[i], err = db.ListBuilds(10)
				if err != nil {
					t.Fatal(err)
				}
			}
			if !reflect.DeepEqual(rows[0], rows[1]) {
				t.Fatalf("JSON rows=%+v binary rows=%+v", rows[0], rows[1])
			}
		})
	}
}
