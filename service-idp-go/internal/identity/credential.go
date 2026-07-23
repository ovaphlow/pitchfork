package identity

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/database/sqlc"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/password"
)

var ErrInvalidPasswordInput = errors.New("invalid password input")
var ErrIncorrectPassword = errors.New("incorrect current password")
var ErrPasswordCredentialNotFound = errors.New("password credential not found")
var ErrPasswordUpdateConflict = errors.New("password credential changed concurrently")

type ChangePasswordInput struct {
	CurrentPassword string
	NewPassword     string
}

func ChangePassword(ctx context.Context, database *sql.DB, subjectID string, input ChangePasswordInput) error {
	newPasswordHash, err := password.Hash(input.NewPassword)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrInvalidPasswordInput, err)
	}

	transaction, err := database.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin password change transaction: %w", err)
	}
	defer transaction.Rollback()
	queries := sqlc.New(database).WithTx(transaction)

	credential, err := queries.GetPasswordCredentialBySubjectID(ctx, subjectID)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrPasswordCredentialNotFound
	}
	if err != nil {
		return fmt.Errorf("load password credential: %w", err)
	}
	matched, err := password.Verify(input.CurrentPassword, credential.PasswordHash)
	if err != nil {
		return fmt.Errorf("verify current password: %w", err)
	}
	if !matched {
		return ErrIncorrectPassword
	}
	if err := replacePassword(ctx, queries, subjectID, credential.PasswordRevision, newPasswordHash, "有效", subjectID); err != nil {
		return err
	}
	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit password change transaction: %w", err)
	}
	return nil
}

func SetTemporaryPassword(ctx context.Context, database *sql.DB, actorSubjectID string, subjectID string, temporaryPassword string) error {
	temporaryPasswordHash, err := password.Hash(temporaryPassword)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrInvalidPasswordInput, err)
	}

	transaction, err := database.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin temporary password transaction: %w", err)
	}
	defer transaction.Rollback()
	queries := sqlc.New(database).WithTx(transaction)

	credential, err := queries.GetPasswordCredentialBySubjectID(ctx, subjectID)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrPasswordCredentialNotFound
	}
	if err != nil {
		return fmt.Errorf("load password credential: %w", err)
	}
	if err := replacePassword(ctx, queries, subjectID, credential.PasswordRevision, temporaryPasswordHash, "需更新", actorSubjectID); err != nil {
		return err
	}
	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit temporary password transaction: %w", err)
	}
	return nil
}

func replacePassword(ctx context.Context, queries sqlc.Querier, subjectID string, expectedRevision int64, passwordHash string, credentialStatus string, actorSubjectID string) error {
	now := time.Now().UTC()
	updated, err := queries.UpdatePasswordCredential(ctx, sqlc.UpdatePasswordCredentialParams{
		PasswordHash:     passwordHash,
		CredentialStatus: credentialStatus,
		ChangedAt:        now,
		UpdatedAt:        now,
		SubjectID:        subjectID,
		PasswordRevision: expectedRevision,
	})
	if err != nil {
		return fmt.Errorf("update password credential: %w", err)
	}
	if updated != 1 {
		return ErrPasswordUpdateConflict
	}
	updated, err = queries.IncrementEnabledSubjectSecurityVersion(ctx, sqlc.IncrementEnabledSubjectSecurityVersionParams{
		UpdatedAt: now,
		ID:        subjectID,
		Status:    "启用",
	})
	if err != nil {
		return fmt.Errorf("increment subject security version: %w", err)
	}
	if updated != 1 {
		return ErrSubjectNotFound
	}
	if _, err := queries.RevokeActiveSessionsBySubjectID(ctx, sqlc.RevokeActiveSessionsBySubjectIDParams{
		RevokedAt:     sql.NullTime{Time: now, Valid: true},
		RevokedReason: sql.NullString{String: "凭据变更", Valid: true},
		SubjectID:     subjectID,
	}); err != nil {
		return fmt.Errorf("revoke subject sessions after password change: %w", err)
	}
	auditID, err := NewULID(now)
	if err != nil {
		return err
	}
	if err := queries.InsertAuditEvent(ctx, sqlc.InsertAuditEventParams{
		ID:              auditID,
		EventAction:     "凭据变更",
		Outcome:         "成功",
		ActorSubjectID:  sql.NullString{String: actorSubjectID, Valid: true},
		TargetSubjectID: sql.NullString{String: subjectID, Valid: true},
		RequestID:       sql.NullString{},
		SourceHash:      nil,
		Metadata:        fmt.Sprintf(`{"credential_status":%q}`, credentialStatus),
		CreatedAt:       now,
	}); err != nil {
		return fmt.Errorf("write password change audit event: %w", err)
	}
	return nil
}
