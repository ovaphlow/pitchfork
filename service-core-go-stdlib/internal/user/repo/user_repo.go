package repo

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/user/entity"
)

// UserRepo provides data access for users table using sqlx.
type UserRepo struct {
	db *sqlx.DB
}

func NewUserRepo(db *sqlx.DB) *UserRepo { return &UserRepo{db: db} }

// EnsureTable creates the users table if not exists (idempotent).
// This is a convenience for early development; prefer migrations in production.
func (r *UserRepo) EnsureTable(ctx context.Context) error {
	const ddl = `
CREATE EXTENSION IF NOT EXISTS citext;
CREATE TABLE IF NOT EXISTS users (
  id BIGSERIAL PRIMARY KEY,
  username TEXT UNIQUE,
  email CITEXT UNIQUE,
  email_verified BOOLEAN NOT NULL DEFAULT false,
  phone_number TEXT UNIQUE,
  phone_verified BOOLEAN NOT NULL DEFAULT false,
  password_hash TEXT,
  password_algo TEXT,
  password_updated_at TIMESTAMPTZ,
  must_reset_password BOOLEAN NOT NULL DEFAULT false,
  status TEXT NOT NULL DEFAULT 'active',
  login_failed_attempts INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMPTZ,
  last_login_at TIMESTAMPTZ,
	user_type TEXT,
  version BIGINT NOT NULL DEFAULT 1,
  security_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  attributes JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deactivated_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_user_type ON users(user_type);
-- tenant_id removed (single-tenant mode); index dropped if existed via migration, kept out of EnsureTable.
`
	_, err := r.db.ExecContext(ctx, ddl)
	return err
}

// Create inserts a new user row (minimal fields). Returns new ID.
func (r *UserRepo) Create(ctx context.Context, u *entity.User) (int64, error) {
	q := `INSERT INTO users (username,email,email_verified,phone_number,phone_verified,password_hash,password_algo,must_reset_password,status,user_type,version,security_metadata,attributes)
		  VALUES (:username,:email,:email_verified,:phone_number,:phone_verified,:password_hash,:password_algo,:must_reset_password,:status,:user_type,:version,COALESCE(:security_metadata,'{}'::jsonb),COALESCE(:attributes,'{}'::jsonb)) RETURNING id`
	// normalize JSONB raw fields
	secRaw := json.RawMessage("{}")
	if len(u.SecurityMetadataRaw) > 0 {
		secRaw = json.RawMessage(u.SecurityMetadataRaw)
	}
	attrRaw := json.RawMessage("{}")
	if len(u.AttributesRaw) > 0 {
		attrRaw = json.RawMessage(u.AttributesRaw)
	}
	params := map[string]any{
		"username":            u.Username,
		"email":               u.Email,
		"email_verified":      u.EmailVerified,
		"phone_number":        u.PhoneNumber,
		"phone_verified":      u.PhoneVerified,
		"password_hash":       u.PasswordHash,
		"password_algo":       u.PasswordAlgo,
		"must_reset_password": u.MustResetPassword,
		"status":              u.Status,
		"user_type":           u.UserType,
		"version":             u.Version,
		"security_metadata":   secRaw,
		"attributes":          attrRaw,
	}
	stmt, err := r.db.NamedQueryContext(ctx, q, params)
	if err != nil {
		return 0, err
	}
	defer stmt.Close()
	if stmt.Next() {
		if err := stmt.Scan(&u.ID); err != nil {
			return 0, err
		}
		return u.ID, nil
	}
	return 0, errors.New("no id returned")
}

// GetByEmail returns a user matched by email (case-insensitive due to citext) or sql.ErrNoRows.
func (r *UserRepo) GetByEmail(ctx context.Context, email string) (*entity.User, error) {
	const q = `SELECT id, username, email, email_verified, phone_number, phone_verified,
		password_hash, password_algo, password_updated_at, must_reset_password,
		status, login_failed_attempts, locked_until, last_login_at, user_type,
		version, security_metadata, attributes, created_at, updated_at, deactivated_at
	  FROM users WHERE email=$1`
	var row entity.User
	// We reuse GetByID style but simple scan into struct fields not tagged; fallback to manual mapping if needed later.
	// For now we leverage sqlx struct field name mapping (CamelCase->snake_case).
	if err := r.db.GetContext(ctx, &row, q, email); err != nil {
		return nil, err
	}
	return &row, nil
}

// GetByUsername fetches by username.
func (r *UserRepo) GetByUsername(ctx context.Context, username string) (*entity.User, error) {
	const q = `SELECT id, username, email, email_verified, phone_number, phone_verified,
		password_hash, password_algo, password_updated_at, must_reset_password,
		status, login_failed_attempts, locked_until, last_login_at, user_type,
		version, security_metadata, attributes, created_at, updated_at, deactivated_at
	  FROM users WHERE username=$1`
	var row entity.User
	if err := r.db.GetContext(ctx, &row, q, username); err != nil {
		return nil, err
	}
	return &row, nil
}

// GetByID fetches a full user row.
func (r *UserRepo) GetByID(ctx context.Context, id int64) (*entity.User, error) {
	const q = `SELECT id, username, email, email_verified, phone_number, phone_verified,
				password_hash, password_algo, password_updated_at, must_reset_password,
				status, login_failed_attempts, locked_until, last_login_at, user_type,
				version, security_metadata, attributes, created_at, updated_at, deactivated_at
			FROM users WHERE id=$1`
	var row struct {
		ID                  int64      `db:"id"`
		Username            *string    `db:"username"`
		Email               *string    `db:"email"`
		EmailVerified       bool       `db:"email_verified"`
		PhoneNumber         *string    `db:"phone_number"`
		PhoneVerified       bool       `db:"phone_verified"`
		PasswordHash        *string    `db:"password_hash"`
		PasswordAlgo        *string    `db:"password_algo"`
		PasswordUpdatedAt   *time.Time `db:"password_updated_at"`
		MustResetPassword   bool       `db:"must_reset_password"`
		Status              string     `db:"status"`
		LoginFailedAttempts int        `db:"login_failed_attempts"`
		LockedUntil         *time.Time `db:"locked_until"`
		LastLoginAt         *time.Time `db:"last_login_at"`
		UserType            *string    `db:"user_type"`
		Version             int64      `db:"version"`
		SecurityMetadata    []byte     `db:"security_metadata"`
		Attributes          []byte     `db:"attributes"`
		CreatedAt           time.Time  `db:"created_at"`
		UpdatedAt           time.Time  `db:"updated_at"`
		DeactivatedAt       *time.Time `db:"deactivated_at"`
	}
	if err := r.db.GetContext(ctx, &row, q, id); err != nil {
		return nil, err
	}
	return &entity.User{
		ID:                  row.ID,
		Username:            row.Username,
		Email:               row.Email,
		EmailVerified:       row.EmailVerified,
		PhoneNumber:         row.PhoneNumber,
		PhoneVerified:       row.PhoneVerified,
		PasswordHash:        row.PasswordHash,
		PasswordAlgo:        row.PasswordAlgo,
		PasswordUpdatedAt:   row.PasswordUpdatedAt,
		MustResetPassword:   row.MustResetPassword,
		Status:              row.Status,
		LoginFailedAttempts: row.LoginFailedAttempts,
		LockedUntil:         row.LockedUntil,
		LastLoginAt:         row.LastLoginAt,
		UserType:            row.UserType,
		Version:             row.Version,
		SecurityMetadataRaw: row.SecurityMetadata,
		AttributesRaw:       row.Attributes,
		CreatedAt:           row.CreatedAt,
		UpdatedAt:           row.UpdatedAt,
		DeactivatedAt:       row.DeactivatedAt,
	}, nil
}

// GetMinimalAuthView returns only the fields needed for token claim hydration.
func (r *UserRepo) GetMinimalAuthView(ctx context.Context, id int64) (*entity.MinimalAuthView, error) {
	const q = `SELECT id, user_type, version, email, email_verified FROM users WHERE id=$1`
	var v entity.MinimalAuthView
	if err := r.db.GetContext(ctx, &v, q, id); err != nil {
		return nil, err
	}
	return &v, nil
}

// IncrementFailedLogin increments the failure counter atomically and returns new value.
func (r *UserRepo) IncrementFailedLogin(ctx context.Context, id int64) (int, error) {
	const q = `UPDATE users SET login_failed_attempts = login_failed_attempts + 1, updated_at=NOW() WHERE id=$1 RETURNING login_failed_attempts`
	var v int
	if err := r.db.GetContext(ctx, &v, q, id); err != nil {
		return 0, err
	}
	return v, nil
}

// LockIfThreshold locks the user if attempts >= threshold and currently active.
func (r *UserRepo) LockIfThreshold(ctx context.Context, id int64, threshold int, lockMinutes int) (bool, error) {
	const q = `UPDATE users SET status='locked', locked_until = NOW() + ($2 || ' minutes')::interval, updated_at=NOW()
              WHERE id=$1 AND status='active' AND login_failed_attempts >= $3 RETURNING 1`
	var one int
	err := r.db.GetContext(ctx, &one, q, id, lockMinutes, threshold)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		return false, err
	}
	return true, nil
}

// ResetLoginSuccess resets failure metrics on successful authentication.
func (r *UserRepo) ResetLoginSuccess(ctx context.Context, id int64) error {
	const q = `UPDATE users SET login_failed_attempts=0, last_login_at=NOW(), locked_until=NULL, updated_at=NOW() WHERE id=$1`
	_, err := r.db.ExecContext(ctx, q, id)
	return err
}

// BumpVersion increments version for token invalidation.
func (r *UserRepo) BumpVersion(ctx context.Context, id int64) error {
	const q = `UPDATE users SET version = version + 1, updated_at=NOW() WHERE id=$1`
	_, err := r.db.ExecContext(ctx, q, id)
	return err
}

// UnlockIfExpired sets status back to active if locked_until passed.
func (r *UserRepo) UnlockIfExpired(ctx context.Context, id int64) (bool, error) {
	const q = `UPDATE users SET status='active', locked_until=NULL, updated_at=NOW()
               WHERE id=$1 AND status='locked' AND locked_until IS NOT NULL AND locked_until < NOW() RETURNING 1`
	var one int
	err := r.db.GetContext(ctx, &one, q, id)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		return false, err
	}
	return true, nil
}

// MustResetPasswordFlag sets or clears must_reset_password.
func (r *UserRepo) MustResetPasswordFlag(ctx context.Context, id int64, flag bool) error {
	const q = `UPDATE users SET must_reset_password=$2, updated_at=NOW() WHERE id=$1`
	_, err := r.db.ExecContext(ctx, q, id, flag)
	return err
}

// UpdatePassword updates password hash & algo and bumps version (optional) for security.
func (r *UserRepo) UpdatePassword(ctx context.Context, id int64, hash, algo string, bumpVersion bool) error {
	if bumpVersion {
		const q = `UPDATE users SET password_hash=$2, password_algo=$3, password_updated_at=NOW(), version=version+1, updated_at=NOW(), must_reset_password=false WHERE id=$1`
		_, err := r.db.ExecContext(ctx, q, id, hash, algo)
		return err
	}
	const q = `UPDATE users SET password_hash=$2, password_algo=$3, password_updated_at=NOW(), updated_at=NOW(), must_reset_password=false WHERE id=$1`
	_, err := r.db.ExecContext(ctx, q, id, hash, algo)
	return err
}

// Deactivate marks a user as disabled.
func (r *UserRepo) Deactivate(ctx context.Context, id int64) error {
	const q = `UPDATE users SET status='disabled', deactivated_at=NOW(), updated_at=NOW() WHERE id=$1`
	_, err := r.db.ExecContext(ctx, q, id)
	return err
}

// Reactivate resets a disabled user to active.
func (r *UserRepo) Reactivate(ctx context.Context, id int64) error {
	const q = `UPDATE users SET status='active', deactivated_at=NULL, updated_at=NOW() WHERE id=$1`
	_, err := r.db.ExecContext(ctx, q, id)
	return err
}
