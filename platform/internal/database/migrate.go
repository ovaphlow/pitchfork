package database

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"strings"
	"time"
)

type MigrationResult struct {
	Applied int
}

func Migrate(ctx context.Context, database *sql.DB, migrationFiles fs.FS) (MigrationResult, error) {
	if _, err := database.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			version TEXT PRIMARY KEY,
			applied_at DATETIME NOT NULL
		)
	`); err != nil {
		return MigrationResult{}, fmt.Errorf("create schema migrations table: %w", err)
	}

	entries, err := fs.ReadDir(migrationFiles, ".")
	if err != nil {
		return MigrationResult{}, fmt.Errorf("read embedded migrations: %w", err)
	}

	migrationNames, err := migrationNames(entries)
	if err != nil {
		return MigrationResult{}, err
	}

	result := MigrationResult{}
	for _, migrationName := range migrationNames {
		applied, err := applyMigration(ctx, database, migrationFiles, migrationName)
		if err != nil {
			return result, err
		}
		if applied {
			result.Applied++
		}
	}
	return result, nil
}

func applyMigration(ctx context.Context, database *sql.DB, migrationFiles fs.FS, migrationName string) (bool, error) {
	version, err := migrationVersion(migrationName)
	if err != nil {
		return false, err
	}

	source, err := fs.ReadFile(migrationFiles, migrationName)
	if err != nil {
		return false, fmt.Errorf("read migration %s: %w", migrationName, err)
	}

	transaction, err := database.BeginTx(ctx, nil)
	if err != nil {
		return false, fmt.Errorf("begin migration %s: %w", version, err)
	}
	defer transaction.Rollback()

	var existingVersion string
	err = transaction.QueryRowContext(ctx, "SELECT version FROM schema_migrations WHERE version = ?", version).Scan(&existingVersion)
	if err == nil {
		return false, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return false, fmt.Errorf("check migration %s: %w", version, err)
	}

	if _, err := transaction.ExecContext(ctx, string(source)); err != nil {
		return false, fmt.Errorf("apply migration %s: %w", version, err)
	}
	if _, err := transaction.ExecContext(ctx, "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)", version, time.Now().UTC()); err != nil {
		return false, fmt.Errorf("record migration %s: %w", version, err)
	}
	if err := transaction.Commit(); err != nil {
		return false, fmt.Errorf("commit migration %s: %w", version, err)
	}
	return true, nil
}

func migrationNames(entries []fs.DirEntry) ([]string, error) {
	versions := make(map[string]string)
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}

		version, err := migrationVersion(entry.Name())
		if err != nil {
			return nil, err
		}
		if existingName, exists := versions[version]; exists {
			return nil, fmt.Errorf("migrations %q and %q share version %s", existingName, entry.Name(), version)
		}
		versions[version] = entry.Name()
		names = append(names, entry.Name())
	}
	sort.Strings(names)
	return names, nil
}

func migrationVersion(migrationName string) (string, error) {
	separatorIndex := strings.IndexByte(migrationName, '_')
	if separatorIndex <= 0 {
		return "", fmt.Errorf("migration %q must start with a numeric version and underscore", migrationName)
	}
	version := migrationName[:separatorIndex]
	for _, character := range version {
		if character < '0' || character > '9' {
			return "", fmt.Errorf("migration %q must start with a numeric version and underscore", migrationName)
		}
	}
	return version, nil
}
