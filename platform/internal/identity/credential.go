package identity

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/ovaphlow/pitchfork/platform/internal/password"
)

var ErrInvalidPasswordInput = errors.New("invalid password input")
var ErrIncorrectPassword = errors.New("incorrect current password")
var ErrPasswordCredentialNotFound = errors.New("password credential not found")
var ErrPasswordUpdateConflict = errors.New("password credential changed concurrently")

type ChangePasswordInput struct {
	CurrentPassword string
	NewPassword     string
}

// passwordCredentialRow 是密码凭据的视图，password_revision 用于乐观并发控制。
type passwordCredentialRow struct {
	SubjectID        string `db:"subject_id"`
	PasswordHash     string `db:"password_hash"`
	PasswordRevision int64  `db:"password_revision"`
	CredentialStatus string `db:"credential_status"`
}

const getPasswordCredentialBySubjectID = `
SELECT subject_id, password_hash, password_revision, credential_status
FROM identity_password_credentials
WHERE subject_id = :subject_id`

const updatePasswordCredential = `
UPDATE identity_password_credentials
SET password_hash = :password_hash,
    credential_status = :credential_status,
    password_revision = password_revision + 1,
    changed_at = :changed_at,
    updated_at = :updated_at
WHERE subject_id = :subject_id AND password_revision = :password_revision`

const incrementEnabledSubjectSecurityVersion = `
UPDATE identity_subjects
SET security_version = security_version + 1, updated_at = :updated_at
WHERE id = :id AND status = :status`

const revokeActiveSessionsBySubjectID = `
UPDATE identity_sessions
SET revoked_at = :revoked_at, revoked_reason = :revoked_reason
WHERE subject_id = :subject_id AND revoked_at IS NULL`

func ChangePassword(ctx context.Context, database *sql.DB, subjectID string, input ChangePasswordInput) error {
	newPasswordHash, err := password.Hash(input.NewPassword)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrInvalidPasswordInput, err)
	}

	transaction, err := newQuerier(database).BeginTxx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin password change transaction: %w", err)
	}
	defer transaction.Rollback()

	var credential passwordCredentialRow
	err = namedGet(ctx, transaction, &credential, getPasswordCredentialBySubjectID, map[string]any{
		"subject_id": subjectID,
	})
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
	if err := replacePassword(ctx, transaction, subjectID, credential.PasswordRevision, newPasswordHash, CredentialStatusValid, subjectID); err != nil {
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

	transaction, err := newQuerier(database).BeginTxx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin temporary password transaction: %w", err)
	}
	defer transaction.Rollback()

	var credential passwordCredentialRow
	err = namedGet(ctx, transaction, &credential, getPasswordCredentialBySubjectID, map[string]any{
		"subject_id": subjectID,
	})
	switch {
	case errors.Is(err, sql.ErrNoRows):
		// 无密码主体（邮件链接注册的账号）第一次拿到密码：插入新凭据，
		// 与管理台建号时的初始凭据同形（password_revision=1、需更新）。
		now := time.Now().UTC()
		credentialID, err := NewULID(now)
		if err != nil {
			return err
		}
		if err := namedExec(ctx, transaction, createPasswordCredential, map[string]any{
			"id":                credentialID,
			"subject_id":        subjectID,
			"password_hash":     temporaryPasswordHash,
			"password_revision": 1,
			"credential_status": CredentialStatusMustUpdate,
			"changed_at":        now,
			"created_at":        now,
			"updated_at":        now,
		}); err != nil {
			return fmt.Errorf("create password credential: %w", err)
		}
		if err := applyPasswordChangeEffects(ctx, transaction, subjectID, actorSubjectID, CredentialStatusMustUpdate); err != nil {
			return err
		}
	case err != nil:
		return fmt.Errorf("load password credential: %w", err)
	default:
		if err := replacePassword(ctx, transaction, subjectID, credential.PasswordRevision, temporaryPasswordHash, CredentialStatusMustUpdate, actorSubjectID); err != nil {
			return err
		}
	}
	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit temporary password transaction: %w", err)
	}
	return nil
}

func replacePassword(ctx context.Context, queries querier, subjectID string, expectedRevision int64, passwordHash string, credentialStatus string, actorSubjectID string) error {
	now := time.Now().UTC()
	updated, err := namedExecRows(ctx, queries, updatePasswordCredential, map[string]any{
		"password_hash":     passwordHash,
		"credential_status": credentialStatus,
		"changed_at":        now,
		"updated_at":        now,
		"subject_id":        subjectID,
		"password_revision": expectedRevision,
	})
	if err != nil {
		return fmt.Errorf("update password credential: %w", err)
	}
	if updated != 1 {
		return ErrPasswordUpdateConflict
	}
	return applyPasswordChangeEffects(ctx, queries, subjectID, actorSubjectID, credentialStatus)
}

// applyPasswordChangeEffects 是「凭据变更后必须发生的事」的唯一实现：
// 递增 security_version 让旧会话失效、撤销现有会话、写审计事件。
// 换密与首次设密（无密码主体）共用，避免两处语义漂移。
func applyPasswordChangeEffects(ctx context.Context, queries querier, subjectID string, actorSubjectID string, credentialStatus string) error {
	now := time.Now().UTC()
	updated, err := namedExecRows(ctx, queries, incrementEnabledSubjectSecurityVersion, map[string]any{
		"updated_at": now,
		"id":         subjectID,
		"status":     StatusEnabled,
	})
	if err != nil {
		return fmt.Errorf("increment subject security version: %w", err)
	}
	if updated != 1 {
		return ErrSubjectNotFound
	}
	if err := namedExec(ctx, queries, revokeActiveSessionsBySubjectID, map[string]any{
		"revoked_at":     now,
		"revoked_reason": RevokedReasonCredentialChanged,
		"subject_id":     subjectID,
	}); err != nil {
		return fmt.Errorf("revoke subject sessions after password change: %w", err)
	}
	if err := insertAuditEvent(ctx, queries, auditEvent{
		Action:          AuditActionCredentialChanged,
		Outcome:         OutcomeSucceeded,
		ActorSubjectID:  actorSubjectID,
		TargetSubjectID: subjectID,
		Metadata:        fmt.Sprintf(`{"credential_status":%q}`, credentialStatus),
	}, now); err != nil {
		return fmt.Errorf("write password change audit event: %w", err)
	}
	return nil
}
