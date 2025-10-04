package oidc

import "time"

// SigningKey holds an in-memory signing key for issuing tokens.
type SigningKey struct {
	Kid       string
	CreatedAt time.Time
}

// RefreshSession represents a persisted refresh session (minimal skeleton).
type RefreshSession struct {
	ID        int64     `db:"id"`
	UserID    int64     `db:"user_id"`
	ClientID  string    `db:"client_id"`
	ExpiresAt time.Time `db:"expires_at"`
}
