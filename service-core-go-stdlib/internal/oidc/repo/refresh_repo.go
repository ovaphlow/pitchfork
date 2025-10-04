package repo

import (
	"context"
	"time"

	"github.com/jmoiron/sqlx"
)

// NOTE: expected table schema (Postgres example):
// CREATE TABLE oidc_refresh_sessions (
//   token TEXT PRIMARY KEY,
//   id BIGSERIAL,
//   user_id BIGINT NOT NULL,
//   client_id TEXT,
//   expires_at TIMESTAMP WITH TIME ZONE NOT NULL
// );

type RefreshRepo struct {
	db *sqlx.DB
}

func NewRefreshRepo(db *sqlx.DB) *RefreshRepo {
	return &RefreshRepo{db: db}
}

func (r *RefreshRepo) Save(ctx context.Context, token string, userID int64, clientID string, expiresAt time.Time) (int64, error) {
	// try to insert; return id or error
	query := `INSERT INTO oidc_refresh_sessions (token, user_id, client_id, expires_at) VALUES ($1, $2, $3, $4) RETURNING id`
	var id int64
	row := r.db.QueryRowxContext(ctx, query, token, userID, clientID, expiresAt)
	if err := row.Scan(&id); err != nil {
		return 0, err
	}
	return id, nil
}

func (r *RefreshRepo) Get(ctx context.Context, token string) (int64, int64, string, time.Time, error) {
	var id int64
	var userID int64
	var clientID string
	var expiresAt time.Time
	query := `SELECT id, user_id, client_id, expires_at FROM oidc_refresh_sessions WHERE token = $1`
	row := r.db.QueryRowxContext(ctx, query, token)
	if err := row.Scan(&id, &userID, &clientID, &expiresAt); err != nil {
		return 0, 0, "", time.Time{}, err
	}
	return id, userID, clientID, expiresAt, nil
}

func (r *RefreshRepo) Delete(ctx context.Context, token string) error {
	_, err := r.db.ExecContext(ctx, `DELETE FROM oidc_refresh_sessions WHERE token = $1`, token)
	return err
}
