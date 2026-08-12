// Package migrations embeds the ordered SQL migrations of prototyped and
// parses them into a version-ordered list. Files follow the naming
// convention NUMBER_name.sql with zero-padded, strictly increasing version
// numbers (000001_init.sql, 000002_...sql). Parsing is pure in-memory and
// never touches a database.
package migrations

import (
	"fmt"
	"io/fs"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Migration is a single ordered database migration.
type Migration struct {
	Version int
	Name    string
	SQL     string
}

// migrationNamePattern matches NUMBER_name.sql files. The number may be
// zero-padded to any width, but consistent padding keeps the filesystem
// (lexicographic) order equal to the version order.
var migrationNamePattern = regexp.MustCompile(`^(\d+)_(.+)\.sql$`)

// Parse reads every *.sql file of the given filesystem, validates the
// naming convention, and returns the migrations in strictly increasing
// version order. Duplicate versions, lexicographic order disagreeing with
// version order (inconsistent zero-padding), invalid names, and empty SQL
// bodies are errors. Non-SQL entries are ignored.
func Parse(files fs.FS) ([]Migration, error) {
	entries, err := fs.ReadDir(files, ".")
	if err != nil {
		return nil, fmt.Errorf("read migrations: %w", err)
	}

	var names []string
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		names = append(names, entry.Name())
	}
	// Sort explicitly by name: embed.FS lists entries in lexicographic
	// order, but other fs.FS implementations (e.g. fstest.MapFS in tests)
	// do not guarantee any order. Migration files must be named so that
	// lexicographic order equals version order.
	sort.Strings(names)

	var migrations []Migration
	for _, name := range names {
		match := migrationNamePattern.FindStringSubmatch(name)
		if match == nil {
			return nil, fmt.Errorf("migration %q: invalid name, want NUMBER_name.sql", name)
		}
		version, err := strconv.Atoi(match[1])
		if err != nil {
			return nil, fmt.Errorf("migration %q: invalid version number", name)
		}
		body, err := fs.ReadFile(files, name)
		if err != nil {
			return nil, fmt.Errorf("migration %q: read: %w", name, err)
		}
		if strings.TrimSpace(string(body)) == "" {
			return nil, fmt.Errorf("migration %q: SQL body is empty", name)
		}
		migrations = append(migrations, Migration{Version: version, Name: match[2], SQL: string(body)})
	}

	// Names are sorted above, so the traversal order is the execution
	// order. Versions must be unique and strictly increasing in that
	// order; a decrease (or a tie) means the numeric prefixes are not
	// consistently zero-padded.
	for i := 1; i < len(migrations); i++ {
		previous, current := migrations[i-1], migrations[i]
		if current.Version <= previous.Version {
			return nil, fmt.Errorf(
				"migration %q: version %d must be strictly greater than the previous version %d",
				current.Name, current.Version, previous.Version,
			)
		}
	}
	return migrations, nil
}
