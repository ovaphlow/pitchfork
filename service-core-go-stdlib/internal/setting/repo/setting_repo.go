package repo

import (
	"context"
	"database/sql"
)

// Repo is the repository implementation for settings backed by PostgreSQL.
type Repo struct {
	db *sql.DB
}

// NewRepo constructs a new Repo with an existing *sql.DB connection.
func NewRepo(db *sql.DB) *Repo {
	return &Repo{db: db}
}

// EnsureTable ensures the settings table and its index exist.
// Fields:
// - id varchar(32) PRIMARY KEY
// - data_state jsonb
// - category varchar(32) (indexed)
// - detail jsonb
func (r *Repo) EnsureTable(ctx context.Context) error {
	// Check if table exists using to_regclass (Postgres). If it exists, skip creation.
	var tblName sql.NullString
	err := r.db.QueryRowContext(ctx, "SELECT to_regclass('public.settings')").Scan(&tblName)
	if err != nil {
		return err
	}

	if !tblName.Valid {
		// Table does not exist, create it.
		createTable := `CREATE TABLE settings (
			id varchar(32) PRIMARY KEY,
			parent_id varchar(32) DEFAULT '',
			root_id varchar(32) DEFAULT '',
			record_meta jsonb DEFAULT '{}'::jsonb,
			category varchar(32) DEFAULT '',
			metadata jsonb DEFAULT '{}'::jsonb
		)`
		if _, err := r.db.ExecContext(ctx, createTable); err != nil {
			return err
		}
	}

	// Check if index exists; use to_regclass for index name.
	var idxName sql.NullString
	err = r.db.QueryRowContext(ctx, "SELECT to_regclass('public.idx_settings_category')").Scan(&idxName)
	if err != nil {
		return err
	}
	if !idxName.Valid {
		createIndex := `CREATE INDEX idx_settings_category ON settings (category)`
		if _, err := r.db.ExecContext(ctx, createIndex); err != nil {
			return err
		}
	}

	// Create index for parent_id
	err = r.db.QueryRowContext(ctx, "SELECT to_regclass('public.idx_settings_parent_id')").Scan(&idxName)
	if err != nil {
		return err
	}
	if !idxName.Valid {
		createIndex := `CREATE INDEX idx_settings_parent_id ON settings (parent_id)`
		if _, err := r.db.ExecContext(ctx, createIndex); err != nil {
			return err
		}
	}

	// Create index for root_id
	err = r.db.QueryRowContext(ctx, "SELECT to_regclass('public.idx_settings_root_id')").Scan(&idxName)
	if err != nil {
		return err
	}
	if !idxName.Valid {
		createIndex := `CREATE INDEX idx_settings_root_id ON settings (root_id)`
		if _, err := r.db.ExecContext(ctx, createIndex); err != nil {
			return err
		}
	}

	return nil
}
