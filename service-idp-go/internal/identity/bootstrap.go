package identity

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/password"
)

const getRoleIDByCode = `
SELECT id
FROM identity_roles
WHERE role_code = :role_code`

const assignSubjectRole = `
INSERT INTO identity_subject_roles(id, subject_id, role_id, granted_by_subject_id, created_at)
VALUES (
    :id, :subject_id, :role_id, :granted_by_subject_id, :created_at
)`

const createRoleIfAbsent = `
INSERT INTO identity_roles(id, role_code, display_name, description, created_at, updated_at)
VALUES (
    :id, :role_code, :display_name, :description, :created_at, :updated_at
)
ON CONFLICT(role_code) DO NOTHING`

type BootstrapInput struct {
	Identifier string
	Password   string
}

func EnsureBootstrap(ctx context.Context, database *sql.DB, input BootstrapInput) (bool, error) {
	transaction, err := newQuerier(database).BeginTxx(ctx, nil)
	if err != nil {
		return false, fmt.Errorf("begin bootstrap transaction: %w", err)
	}
	defer transaction.Rollback()

	now := time.Now().UTC()
	if err := seedRoles(ctx, transaction, now); err != nil {
		return false, err
	}

	var subjectCount int64
	if err := namedGet(ctx, transaction, &subjectCount, countSubjects, nil); err != nil {
		return false, fmt.Errorf("count identity subjects: %w", err)
	}
	if subjectCount > 0 {
		if err := transaction.Commit(); err != nil {
			return false, fmt.Errorf("commit existing bootstrap state: %w", err)
		}
		return false, nil
	}

	identifier, err := normalizeAccountIdentifier(input.Identifier)
	if err != nil {
		return false, fmt.Errorf("validate bootstrap identifier: %w", err)
	}
	passwordHash, err := password.Hash(input.Password)
	if err != nil {
		return false, fmt.Errorf("hash bootstrap password: %w", err)
	}

	subjectID, err := NewULID(now)
	if err != nil {
		return false, err
	}
	identifierID, err := NewULID(now)
	if err != nil {
		return false, err
	}
	credentialID, err := NewULID(now)
	if err != nil {
		return false, err
	}
	grantID, err := NewULID(now)
	if err != nil {
		return false, err
	}
	if err := namedExec(ctx, transaction, createSubject, map[string]any{
		"id":               subjectID,
		"status":           StatusEnabled,
		"security_version": 1,
		"metadata":         "{}",
		"created_at":       now,
		"updated_at":       now,
	}); err != nil {
		return false, fmt.Errorf("create bootstrap subject: %w", err)
	}
	if err := namedExec(ctx, transaction, createProfile, map[string]any{
		"subject_id":   subjectID,
		"display_name": "系统管理员",
		"created_at":   now,
		"updated_at":   now,
	}); err != nil {
		return false, fmt.Errorf("create bootstrap profile: %w", err)
	}
	if err := namedExec(ctx, transaction, createIdentifier, map[string]any{
		"id":               identifierID,
		"subject_id":       subjectID,
		"identifier_type":  IdentifierTypeAccount,
		"identifier_value": identifier,
		"normalized_value": identifier,
		"identifier_usage": IdentifierUsagePrimaryLogin,
		"status":           StatusEnabled,
		"created_at":       now,
		"updated_at":       now,
	}); err != nil {
		return false, fmt.Errorf("create bootstrap identifier: %w", err)
	}
	if err := namedExec(ctx, transaction, createPasswordCredential, map[string]any{
		"id":                credentialID,
		"subject_id":        subjectID,
		"password_hash":     passwordHash,
		"password_revision": 1,
		"credential_status": CredentialStatusValid,
		"changed_at":        now,
		"created_at":        now,
		"updated_at":        now,
	}); err != nil {
		return false, fmt.Errorf("create bootstrap credential: %w", err)
	}

	var adminRoleID string
	if err := namedGet(ctx, transaction, &adminRoleID, getRoleIDByCode, map[string]any{
		"role_code": RoleCodeAdministrator,
	}); err != nil {
		return false, fmt.Errorf("find identity admin role: %w", err)
	}
	if err := namedExec(ctx, transaction, assignSubjectRole, map[string]any{
		"id":                    grantID,
		"subject_id":            subjectID,
		"role_id":               adminRoleID,
		"granted_by_subject_id": nil,
		"created_at":            now,
	}); err != nil {
		return false, fmt.Errorf("grant bootstrap administrator role: %w", err)
	}
	if err := insertAuditEvent(ctx, transaction, auditEvent{
		Action:          AuditActionSubjectCreated,
		Outcome:         OutcomeSucceeded,
		TargetSubjectID: subjectID,
		Metadata:        `{"actor_source":"bootstrap"}`,
	}, now); err != nil {
		return false, fmt.Errorf("write bootstrap audit event: %w", err)
	}

	if err := transaction.Commit(); err != nil {
		return false, fmt.Errorf("commit bootstrap transaction: %w", err)
	}
	return true, nil
}

func seedRoles(ctx context.Context, queries querier, now time.Time) error {
	roles := []struct {
		Code        string
		DisplayName string
		Description string
	}{
		{Code: RoleCodeAdministrator, DisplayName: "身份管理员", Description: "管理身份、凭据、角色、会话和恢复操作。"},
		{Code: RoleCodeAuditReader, DisplayName: "审计查看者", Description: "查看运行概览和不可变审计事件。"},
	}
	for _, role := range roles {
		roleID, err := NewULID(now)
		if err != nil {
			return err
		}
		if err := namedExec(ctx, queries, createRoleIfAbsent, map[string]any{
			"id":           roleID,
			"role_code":    role.Code,
			"display_name": role.DisplayName,
			"description":  role.Description,
			"created_at":   now,
			"updated_at":   now,
		}); err != nil {
			return fmt.Errorf("seed role %s: %w", role.Code, err)
		}
	}
	return nil
}

func normalizeAccountIdentifier(value string) (string, error) {
	value = strings.TrimSpace(value)
	if len(value) < 3 || len(value) > 64 {
		return "", fmt.Errorf("account identifier must contain 3 to 64 characters")
	}

	normalized := strings.ToLower(value)
	if strings.Contains(normalized, "@") {
		return normalizeEmailIdentifier(normalized)
	}

	for _, character := range normalized {
		if (character >= 'a' && character <= 'z') ||
			(character >= '0' && character <= '9') ||
			character == '_' || character == '-' || character == '.' {
			continue
		}
		return "", fmt.Errorf("account identifier contains an unsupported character")
	}
	return normalized, nil
}

func normalizeEmailIdentifier(value string) (string, error) {
	if strings.Count(value, "@") != 1 {
		return "", fmt.Errorf("email account identifier must contain one @")
	}

	localPart, domain, _ := strings.Cut(value, "@")
	if !validEmailLocalPart(localPart) || !validEmailDomain(domain) {
		return "", fmt.Errorf("email account identifier is invalid")
	}
	return value, nil
}

func validEmailLocalPart(value string) bool {
	if value == "" || strings.HasPrefix(value, ".") || strings.HasSuffix(value, ".") || strings.Contains(value, "..") {
		return false
	}
	for _, character := range value {
		if (character >= 'a' && character <= 'z') ||
			(character >= '0' && character <= '9') ||
			character == '_' || character == '-' || character == '.' {
			continue
		}
		return false
	}
	return true
}

func validEmailDomain(value string) bool {
	labels := strings.Split(value, ".")
	if len(labels) < 2 {
		return false
	}
	for _, label := range labels {
		if label == "" || strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") {
			return false
		}
		for _, character := range label {
			if (character >= 'a' && character <= 'z') ||
				(character >= '0' && character <= '9') || character == '-' {
				continue
			}
			return false
		}
	}
	return true
}
