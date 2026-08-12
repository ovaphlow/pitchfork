package migrations

import (
	"io/fs"
	"os"
	"strings"
	"testing"
	"testing/fstest"
)

func TestParseEmbeddedMigrations(t *testing.T) {
	list, err := Parse(Files)
	if err != nil {
		t.Fatalf("parse embedded migrations: %v", err)
	}
	if len(list) == 0 {
		t.Fatal("no embedded migrations found")
	}
	for i, migration := range list {
		if migration.Name == "" {
			t.Fatalf("migration %d: empty name", i)
		}
		if len(migration.SQL) == 0 {
			t.Fatalf("migration %06d_%s: empty SQL", migration.Version, migration.Name)
		}
		if i > 0 && migration.Version <= list[i-1].Version {
			t.Fatalf(
				"migration %06d_%s: version %d not strictly greater than %d",
				migration.Version, migration.Name, migration.Version, list[i-1].Version,
			)
		}
	}
	if list[0].Version != 1 {
		t.Fatalf("first version = %d, want 1", list[0].Version)
	}
}

func TestParseReturnsMigrationsInVersionOrder(t *testing.T) {
	fileSystem := fstest.MapFS{
		"000010_ten.sql": {Data: []byte("SELECT 10;")},
		"000002_two.sql": {Data: []byte("SELECT 2;")},
		"000001_one.sql": {Data: []byte("SELECT 1;")},
	}
	list, err := Parse(fileSystem)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(list) != 3 {
		t.Fatalf("len = %d, want 3", len(list))
	}
	versions := []int{list[0].Version, list[1].Version, list[2].Version}
	if versions[0] != 1 || versions[1] != 2 || versions[2] != 10 {
		t.Fatalf("versions = %v, want [1 2 10]", versions)
	}
	if list[0].Name != "one" || list[1].Name != "two" || list[2].Name != "ten" {
		t.Fatalf("names = %q %q %q, want one two ten", list[0].Name, list[1].Name, list[2].Name)
	}
}

func TestParseRejectsDuplicateVersion(t *testing.T) {
	fileSystem := fstest.MapFS{
		"000001_first.sql":  {Data: []byte("SELECT 1;")},
		"000001_second.sql": {Data: []byte("SELECT 1;")},
	}
	_, err := Parse(fileSystem)
	if err == nil {
		t.Fatal("expected an error for duplicate versions")
	}
}

// TestParseRejectsOutOfOrderVersions verifies that inconsistent
// zero-padding (lexicographic order disagreeing with version order) is
// rejected: lexicographically 00010 sorts before 0002 ('1' < '2'), so the
// version sequence [10, 2] is not strictly increasing.
func TestParseRejectsOutOfOrderVersions(t *testing.T) {
	fileSystem := fstest.MapFS{
		"0002_two.sql":  {Data: []byte("SELECT 2;")},
		"00010_ten.sql": {Data: []byte("SELECT 10;")},
	}
	_, err := Parse(fileSystem)
	if err == nil {
		t.Fatal("expected an error for out-of-order versions")
	}
}

func TestParseRejectsInvalidNames(t *testing.T) {
	for _, name := range []string{
		"migration.sql",       // no numeric prefix
		"000001.sql",          // no name separator
		"abc_000001_name.sql", // non-numeric prefix
	} {
		fileSystem := fstest.MapFS{name: {Data: []byte("SELECT 1;")}}
		if _, err := Parse(fileSystem); err == nil {
			t.Fatalf("file %q: expected an error", name)
		}
	}
}

func TestParseRejectsEmptySQL(t *testing.T) {
	fileSystem := fstest.MapFS{
		"000001_empty.sql": {Data: []byte("  \n\t ")},
	}
	_, err := Parse(fileSystem)
	if err == nil {
		t.Fatal("expected an error for an empty SQL body")
	}
	if !strings.Contains(err.Error(), "empty") {
		t.Fatalf("error %q does not explain the empty body", err)
	}
}

func TestParseIgnoresNonSQLFiles(t *testing.T) {
	fileSystem := fstest.MapFS{
		"000001_real.sql": {Data: []byte("SELECT 1;")},
		"README.md":       {Data: []byte("# not a migration")},
		"notes.txt":       {Data: []byte("not a migration")},
		"000002_no_ext":   {Data: []byte("SELECT 2;")},
	}
	list, err := Parse(fileSystem)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(list) != 1 || list[0].Name != "real" {
		t.Fatalf("list = %+v, want only 000001_real", list)
	}
}

// TestEmbeddedMigrationsMatchDisk verifies the embedded file set is exactly
// the *.sql files on disk in db/migrations.
func TestEmbeddedMigrationsMatchDisk(t *testing.T) {
	embeddedNames, err := sqlNames(Files)
	if err != nil {
		t.Fatalf("read embedded migrations: %v", err)
	}
	diskNames, err := sqlNames(os.DirFS("."))
	if err != nil {
		t.Fatalf("read migrations directory: %v", err)
	}
	if len(embeddedNames) != len(diskNames) {
		t.Fatalf("embedded %d files, disk has %d: embedded=%v disk=%v",
			len(embeddedNames), len(diskNames), embeddedNames, diskNames)
	}
	for i := range embeddedNames {
		if embeddedNames[i] != diskNames[i] {
			t.Fatalf("embedded file %q != disk file %q", embeddedNames[i], diskNames[i])
		}
	}
}

func sqlNames(fileSystem fs.FS) ([]string, error) {
	entries, err := fs.ReadDir(fileSystem, ".")
	if err != nil {
		return nil, err
	}
	var names []string
	for _, entry := range entries {
		if !entry.IsDir() && len(entry.Name()) > 4 && entry.Name()[len(entry.Name())-4:] == ".sql" {
			names = append(names, entry.Name())
		}
	}
	return names, nil
}
