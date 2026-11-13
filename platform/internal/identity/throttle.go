package identity

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

type LoginThrottleSettings struct {
	Secret          []byte
	FailureLimit    int
	Window          time.Duration
	LockoutDuration time.Duration
}

type loginThrottleKey struct {
	identifierHash []byte
	sourceHash     []byte
}

// loginThrottleRow 是登录限流记录的视图。locked_until 可空，用指针表达。
type loginThrottleRow struct {
	ID              string     `db:"id"`
	IdentifierHash  []byte     `db:"identifier_hash"`
	SourceHash      []byte     `db:"source_hash"`
	FailedCount     int64      `db:"failed_count"`
	WindowStartedAt time.Time  `db:"window_started_at"`
	LockedUntil     *time.Time `db:"locked_until"`
	UpdatedAt       time.Time  `db:"updated_at"`
}

const getLoginThrottle = `
SELECT id, identifier_hash, source_hash, failed_count, window_started_at, locked_until, updated_at
FROM identity_login_throttles
WHERE identifier_hash = :identifier_hash AND source_hash = :source_hash`

const upsertLoginThrottle = `
INSERT INTO identity_login_throttles(
    id, identifier_hash, source_hash, failed_count, window_started_at, locked_until, updated_at
) VALUES (
    :id, :identifier_hash, :source_hash, :failed_count, :window_started_at, :locked_until, :updated_at
)
ON CONFLICT(identifier_hash, source_hash) DO UPDATE SET
    failed_count = excluded.failed_count,
    window_started_at = excluded.window_started_at,
    locked_until = excluded.locked_until,
    updated_at = excluded.updated_at`

func (settings LoginThrottleSettings) validate() error {
	if len(settings.Secret) < 32 {
		return fmt.Errorf("login throttle secret must contain at least 32 bytes")
	}
	if settings.FailureLimit <= 0 {
		return fmt.Errorf("login throttle failure limit must be positive")
	}
	if settings.Window <= 0 {
		return fmt.Errorf("login throttle window must be positive")
	}
	if settings.LockoutDuration <= 0 {
		return fmt.Errorf("login throttle lockout duration must be positive")
	}
	return nil
}

func newLoginThrottleKey(settings LoginThrottleSettings, identifier string, sourceAddress string) loginThrottleKey {
	return loginThrottleKey{
		identifierHash: hmacSHA256(settings.Secret, strings.ToLower(strings.TrimSpace(identifier))),
		sourceHash:     hmacSHA256(settings.Secret, normalizedSourceAddress(sourceAddress)),
	}
}

func hmacSHA256(secret []byte, value string) []byte {
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(value))
	return mac.Sum(nil)
}

func normalizedSourceAddress(value string) string {
	if normalized := strings.TrimSpace(value); normalized != "" {
		return normalized
	}
	return "unknown"
}

func loginThrottleLocked(ctx context.Context, queries querier, key loginThrottleKey, now time.Time) (bool, error) {
	var throttle loginThrottleRow
	err := namedGet(ctx, queries, &throttle, getLoginThrottle, map[string]any{
		"identifier_hash": key.identifierHash,
		"source_hash":     key.sourceHash,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("load login throttle: %w", err)
	}
	return isLoginThrottleLocked(throttle, now), nil
}

func isLoginThrottleLocked(throttle loginThrottleRow, now time.Time) bool {
	return throttle.LockedUntil != nil && throttle.LockedUntil.After(now)
}

func recordLoginFailure(ctx context.Context, database *sql.DB, settings LoginThrottleSettings, key loginThrottleKey, now time.Time) error {
	transaction, err := newQuerier(database).BeginTxx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin failed login transaction: %w", err)
	}
	defer transaction.Rollback()

	if err := recordLoginFailureInTransaction(ctx, transaction, settings, key, now); err != nil {
		return err
	}
	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit failed login transaction: %w", err)
	}
	return nil
}

func recordLoginFailureInTransaction(ctx context.Context, queries querier, settings LoginThrottleSettings, key loginThrottleKey, now time.Time) error {
	var throttle loginThrottleRow
	err := namedGet(ctx, queries, &throttle, getLoginThrottle, map[string]any{
		"identifier_hash": key.identifierHash,
		"source_hash":     key.sourceHash,
	})
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return fmt.Errorf("load login throttle for failure: %w", err)
	}

	locked := err == nil && isLoginThrottleLocked(throttle, now)
	if !locked {
		failedCount := int64(1)
		windowStartedAt := now
		if err == nil && now.Before(throttle.WindowStartedAt.Add(settings.Window)) {
			failedCount = throttle.FailedCount + 1
			windowStartedAt = throttle.WindowStartedAt
		}

		var lockedUntil *time.Time
		if failedCount >= int64(settings.FailureLimit) {
			lockedUntil = ref(now.Add(settings.LockoutDuration))
		}
		throttleID, err := NewULID(now)
		if err != nil {
			return err
		}
		if err := namedExec(ctx, queries, upsertLoginThrottle, map[string]any{
			"id":                throttleID,
			"identifier_hash":   key.identifierHash,
			"source_hash":       key.sourceHash,
			"failed_count":      failedCount,
			"window_started_at": windowStartedAt,
			"locked_until":      lockedUntil,
			"updated_at":        now,
		}); err != nil {
			return fmt.Errorf("upsert login throttle: %w", err)
		}
	}

	metadata := `{"reason":"invalid_credentials"}`
	if locked {
		metadata = `{"reason":"throttled"}`
	}
	if err := insertAuditEvent(ctx, queries, auditEvent{
		Action:     AuditActionLogin,
		Outcome:    OutcomeFailed,
		SourceHash: key.sourceHash,
		Metadata:   metadata,
	}, now); err != nil {
		return fmt.Errorf("write failed login audit event: %w", err)
	}
	return nil
}
