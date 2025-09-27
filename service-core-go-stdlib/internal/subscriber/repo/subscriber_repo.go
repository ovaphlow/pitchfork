package repo

import (
	"context"
	"database/sql"
)

type SubscriberRepo struct {
	db *sql.DB
}

func NewSubscriberRepo(db *sql.DB) *SubscriberRepo {
	return &SubscriberRepo{db: db}
}

// EnsureTable creates the subscribers table if it does not already exist.
// Uses Postgres-compatible SQL (JSONB for metadata and BIGSERIAL id).
func (r *SubscriberRepo) EnsureTable(ctx context.Context) error {
	const tbl = `
	CREATE TABLE IF NOT EXISTS subscribers (
		id varchar(32) PRIMARY KEY,
		record_meta JSONB DEFAULT '{}'::jsonb,
		email varchar(32) NOT NULL DEFAULT '',
		phone varchar(16) DEFAULT '',
		metadata JSONB DEFAULT '{}'::jsonb
	);
	`
	if _, err := r.db.ExecContext(ctx, tbl); err != nil {
		return err
	}

	const idx = `
	CREATE UNIQUE INDEX IF NOT EXISTS idx_subscribers_email ON subscribers (email);
	`
	if _, err := r.db.ExecContext(ctx, idx); err != nil {
		return err
	}

	const idxPhone = `
	CREATE INDEX IF NOT EXISTS idx_subscribers_phone ON subscribers (phone);
	`
	if _, err := r.db.ExecContext(ctx, idxPhone); err != nil {
		return err
	}
	return nil
}
