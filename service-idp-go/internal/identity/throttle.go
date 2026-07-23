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

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/database/sqlc"
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

func loginThrottleLocked(ctx context.Context, queries sqlc.Querier, key loginThrottleKey, now time.Time) (bool, error) {
	throttle, err := queries.GetLoginThrottle(ctx, sqlc.GetLoginThrottleParams{
		IdentifierHash: key.identifierHash,
		SourceHash:     key.sourceHash,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("load login throttle: %w", err)
	}
	return isLoginThrottleLocked(throttle, now), nil
}

func isLoginThrottleLocked(throttle sqlc.IdentityLoginThrottle, now time.Time) bool {
	return throttle.LockedUntil.Valid && throttle.LockedUntil.Time.After(now)
}

func recordLoginFailure(ctx context.Context, database *sql.DB, settings LoginThrottleSettings, key loginThrottleKey, now time.Time) error {
	transaction, err := database.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin failed login transaction: %w", err)
	}
	defer transaction.Rollback()

	if err := recordLoginFailureInTransaction(ctx, sqlc.New(database).WithTx(transaction), settings, key, now); err != nil {
		return err
	}
	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit failed login transaction: %w", err)
	}
	return nil
}

func recordLoginFailureInTransaction(ctx context.Context, queries sqlc.Querier, settings LoginThrottleSettings, key loginThrottleKey, now time.Time) error {
	throttle, err := queries.GetLoginThrottle(ctx, sqlc.GetLoginThrottleParams{
		IdentifierHash: key.identifierHash,
		SourceHash:     key.sourceHash,
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

		lockedUntil := sql.NullTime{}
		if failedCount >= int64(settings.FailureLimit) {
			lockedUntil = sql.NullTime{Time: now.Add(settings.LockoutDuration), Valid: true}
		}
		throttleID, err := NewULID(now)
		if err != nil {
			return err
		}
		if err := queries.UpsertLoginThrottle(ctx, sqlc.UpsertLoginThrottleParams{
			ID:              throttleID,
			IdentifierHash:  key.identifierHash,
			SourceHash:      key.sourceHash,
			FailedCount:     failedCount,
			WindowStartedAt: windowStartedAt,
			LockedUntil:     lockedUntil,
			UpdatedAt:       now,
		}); err != nil {
			return fmt.Errorf("upsert login throttle: %w", err)
		}
	}

	auditID, err := NewULID(now)
	if err != nil {
		return err
	}
	metadata := `{"reason":"invalid_credentials"}`
	if locked {
		metadata = `{"reason":"throttled"}`
	}
	if err := queries.InsertAuditEvent(ctx, sqlc.InsertAuditEventParams{
		ID:              auditID,
		EventAction:     "登录",
		Outcome:         "失败",
		ActorSubjectID:  sql.NullString{},
		TargetSubjectID: sql.NullString{},
		RequestID:       sql.NullString{},
		SourceHash:      key.sourceHash,
		Metadata:        metadata,
		CreatedAt:       now,
	}); err != nil {
		return fmt.Errorf("write failed login audit event: %w", err)
	}
	return nil
}
