package migrate

import (
	"testing"
	"testing/fstest"
)

func TestLoadSortsAndHashes(t *testing.T) {
	fsys := fstest.MapFS{
		"0002_second.sql": &fstest.MapFile{Data: []byte("SELECT 2;")},
		"0001_first.sql":  &fstest.MapFile{Data: []byte("SELECT 1;")},
		"README.md":       &fstest.MapFile{Data: []byte("ignored")},
	}

	migrations, err := load(fsys)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if len(migrations) != 2 {
		t.Fatalf("len = %d, want 2 (non-SQL files must be ignored)", len(migrations))
	}
	if migrations[0].version != "0001_first" || migrations[1].version != "0002_second" {
		t.Fatalf("order = %q, %q", migrations[0].version, migrations[1].version)
	}
	if migrations[0].checksum == "" || migrations[0].checksum == migrations[1].checksum {
		t.Fatalf("checksums must be non-empty and distinct: %q, %q", migrations[0].checksum, migrations[1].checksum)
	}

	again, err := load(fsys)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if again[0].checksum != migrations[0].checksum {
		t.Fatal("checksum must be stable across loads")
	}
}

func TestLoadRejectsEmptyMigration(t *testing.T) {
	fsys := fstest.MapFS{
		"0001_empty.sql": &fstest.MapFile{Data: []byte("  \n\t")},
	}
	if _, err := load(fsys); err == nil {
		t.Fatal("expected an error for an empty migration")
	}
}

func TestLoadDetectsContentDrift(t *testing.T) {
	fresh, err := load(fstest.MapFS{
		"0001_first.sql": &fstest.MapFile{Data: []byte("SELECT 1;")},
	})
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	edited, err := load(fstest.MapFS{
		"0001_first.sql": &fstest.MapFile{Data: []byte("SELECT 1; -- edited")},
	})
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if fresh[0].checksum == edited[0].checksum {
		t.Fatal("editing an applied migration must change its checksum")
	}
}
