package entity

import "time"

// User represents an account row in the `users` table (see docs/user_system.md).
// Only includes fields needed at service layer; large JSON or auxiliary tables can extend this.
type User struct {
	ID                  int64
	Username            *string
	Email               *string
	EmailVerified       bool
	PhoneNumber         *string
	PhoneVerified       bool
	PasswordHash        *string
	PasswordAlgo        *string
	PasswordUpdatedAt   *time.Time
	MustResetPassword   bool
	Status              string // active / locked / disabled
	LoginFailedAttempts int
	LockedUntil         *time.Time
	LastLoginAt         *time.Time
	UserType            *string
	Version             int64
	SecurityMetadataRaw []byte // optional JSONB raw (risk_score etc.)
	AttributesRaw       []byte // optional JSONB raw
	CreatedAt           time.Time
	UpdatedAt           time.Time
	DeactivatedAt       *time.Time
}

// MinimalAuthView is the minimal projection required for token claim hydration.
type MinimalAuthView struct {
	ID            int64
	UserType      *string
	Version       int64
	Email         *string
	EmailVerified bool
}
