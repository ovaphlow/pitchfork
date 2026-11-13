package migrate

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io/fs"
	"log/slog"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// advisoryLockKey serialises concurrent runners; the value is arbitrary but
// must stay stable across releases. "agora" in ASCII.
const advisoryLockKey int64 = 0x61676F7261

const createTableSQL = `
CREATE TABLE IF NOT EXISTS schema_migrations (
    version    TEXT PRIMARY KEY,
    checksum   TEXT NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)`

// migration is one embedded SQL file.
type migration struct {
	version   string
	statement string
	checksum  string
}

// Run applies every not-yet-applied *.sql file in fsys, in lexical order. Each
// file runs in its own transaction, so a failure leaves earlier files applied
// and the failing file untouched.
func Run(ctx context.Context, pool *pgxpool.Pool, fsys fs.FS, logger *slog.Logger) error {
	if logger == nil {
		logger = slog.Default()
	}

	migrations, err := load(fsys)
	if err != nil {
		return err
	}

	conn, err := pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("acquire migration connection: %w", err)
	}
	defer conn.Release()

	if _, err := conn.Exec(ctx, "SELECT pg_advisory_lock($1)", advisoryLockKey); err != nil {
		return fmt.Errorf("acquire migration lock: %w", err)
	}
	defer func() {
		unlockCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		if _, err := conn.Exec(unlockCtx, "SELECT pg_advisory_unlock($1)", advisoryLockKey); err != nil {
			logger.Warn("release migration lock failed", "error", err)
		}
	}()

	if _, err := conn.Exec(ctx, createTableSQL); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	applied, err := appliedChecksums(ctx, conn)
	if err != nil {
		return err
	}

	for _, item := range migrations {
		if previous, ok := applied[item.version]; ok {
			if previous != item.checksum {
				return fmt.Errorf("migration %s changed after being applied", item.version)
			}
			continue
		}
		if err := apply(ctx, conn, item); err != nil {
			return err
		}
		logger.Info("migration applied", "version", item.version)
	}
	return nil
}

// load reads and sorts the migration files, rejecting malformed names.
func load(fsys fs.FS) ([]migration, error) {
	entries, err := fs.ReadDir(fsys, ".")
	if err != nil {
		return nil, fmt.Errorf("read migrations: %w", err)
	}

	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasSuffix(name, ".sql") {
			continue
		}
		names = append(names, name)
	}
	sort.Strings(names)

	migrations := make([]migration, 0, len(names))
	for _, name := range names {
		content, err := fs.ReadFile(fsys, name)
		if err != nil {
			return nil, fmt.Errorf("read migration %s: %w", name, err)
		}
		if strings.TrimSpace(string(content)) == "" {
			return nil, fmt.Errorf("migration %s is empty", name)
		}
		sum := sha256.Sum256(content)
		migrations = append(migrations, migration{
			version:   strings.TrimSuffix(name, ".sql"),
			statement: string(content),
			checksum:  hex.EncodeToString(sum[:]),
		})
	}
	return migrations, nil
}

func appliedChecksums(ctx context.Context, conn *pgxpool.Conn) (map[string]string, error) {
	rows, err := conn.Query(ctx, "SELECT version, checksum FROM schema_migrations")
	if err != nil {
		return nil, fmt.Errorf("read schema_migrations: %w", err)
	}
	defer rows.Close()

	applied := make(map[string]string)
	for rows.Next() {
		var version, checksum string
		if err := rows.Scan(&version, &checksum); err != nil {
			return nil, fmt.Errorf("scan schema_migrations: %w", err)
		}
		applied[version] = checksum
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("read schema_migrations: %w", err)
	}
	return applied, nil
}

func apply(ctx context.Context, conn *pgxpool.Conn, item migration) error {
	tx, err := conn.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin migration %s: %w", item.version, err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// DDL needs the simple protocol: the extended protocol allows exactly one
	// statement per call.
	if _, err := tx.Exec(ctx, item.statement, pgx.QueryExecModeSimpleProtocol); err != nil {
		return fmt.Errorf("apply migration %s: %w", item.version, err)
	}
	if _, err := tx.Exec(ctx,
		"INSERT INTO schema_migrations (version, checksum) VALUES ($1, $2)",
		item.version, item.checksum,
	); err != nil {
		return fmt.Errorf("record migration %s: %w", item.version, err)
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit migration %s: %w", item.version, err)
	}
	return nil
}
