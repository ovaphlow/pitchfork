package database

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite"
)

func OpenSQLite(ctx context.Context, databasePath string) (*sql.DB, error) {
	if databasePath == "" {
		return nil, fmt.Errorf("SQLite database path is required")
	}
	if databasePath != ":memory:" {
		if err := os.MkdirAll(filepath.Dir(databasePath), 0o750); err != nil {
			return nil, fmt.Errorf("create SQLite database directory: %w", err)
		}
	}

	database, err := sql.Open("sqlite", databasePath)
	if err != nil {
		return nil, fmt.Errorf("open SQLite database: %w", err)
	}
	database.SetMaxOpenConns(1)
	database.SetMaxIdleConns(1)

	if err := configureSQLite(ctx, database); err != nil {
		database.Close()
		return nil, err
	}
	return database, nil
}

func configureSQLite(ctx context.Context, database *sql.DB) error {
	for _, statement := range []string{
		"PRAGMA foreign_keys = ON",
		"PRAGMA busy_timeout = 5000",
		"PRAGMA journal_mode = WAL",
		"PRAGMA synchronous = FULL",
		"PRAGMA trusted_schema = OFF",
		"PRAGMA wal_autocheckpoint = 1000",
		"PRAGMA journal_size_limit = 67108864",
	} {
		if _, err := database.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("configure SQLite with %q: %w", statement, err)
		}
	}
	return nil
}
